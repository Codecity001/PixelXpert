package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import androidx.core.content.res.ResourcesCompat
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.AlertSlider
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.getFlashlightLevel
import sh.siava.pixelxpert.xposed.utils.SystemUtils.getMaxFlashLevel
import sh.siava.pixelxpert.xposed.utils.SystemUtils.isFlashOn
import sh.siava.pixelxpert.xposed.utils.SystemUtils.registerFlashlightLevelListener
import sh.siava.pixelxpert.xposed.utils.SystemUtils.unregisterFlashlightLevelListener
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlin.concurrent.thread
import kotlin.math.roundToInt

@SystemUIModPack
class FlashlightTile(context: Context) : XposedModPack(context) {

    private var leveledFlashTile = false
    private var animateFlashlight = false
    private var mTile: Any? = null
    private var mLevelDrawable: LevelDrawable? = null

    private val mFlashLevelListener = SystemUtils.ChangeListener { newVal ->
        val tile = mTile
        if (tile != null) {
            mLevelDrawable?.updateState(newVal)
            callMethod(tile, "refreshState")
        }
    }

    override fun onPreferenceUpdated(vararg key: String) {
        leveledFlashTile = Xprefs.getBoolean("leveledFlashTile", false)
        animateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false)

        if (leveledFlashTile) {
            if (mLevelDrawable == null) {
                mLevelDrawable = LevelDrawable(mContext).apply {
                    init(0, SystemUtils.getMaxFlashLevel(), R.drawable.qs_flashlight_on, R.drawable.qs_fashlight_on_outline, 0.1f, 0.1f, 0.3f)
                    updateState(getFlashlightLevel(Xprefs.getInt("flashPCT", 50) / 100f))
                }
            }
            registerFlashlightLevelListener(mFlashLevelListener)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val flashlightTileClass = ReflectedClass.of("com.android.systemui.qs.tiles.FlashlightTile")
            val qsTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl")
            val drawableIconClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIcon")
            val flashlightRepositoryImplClass = ReflectedClass.of("com.android.systemui.flashlight.data.repository.FlashlightRepositoryImpl")
            val flashlightTileWithLevelClass = ReflectedClass.of("com.android.systemui.qs.tiles.FlashlightTileWithLevel")

            ReflectedClass.of(CameraManager::class.java)
                .before("turnOnTorchWithStrengthLevel")
                .run { hookParam ->
                    if (animateFlashlight) {
                        val level = hookParam.args.getOrNull(1) as? Int ?: return@run
                        SystemUtils.setFlash(true, level, !isFlashOn())
                        hookParam.result = false
                    }
                }

            flashlightTileWithLevelClass
                .after("handleUpdateState")
                .run { hookParam ->
                    if (leveledFlashTile) {
                        if (mTile == null) {
                            mTile = hookParam.thisObject
                        }

                        val state = hookParam.args.getOrNull(0) ?: return@run
                        val isEnabled = getObjectField(state, "value") as? Boolean ?: false

                        if (isEnabled && mLevelDrawable != null) {
                            val icon = drawableIconClass.clazz.constructors[0].newInstance(mLevelDrawable)
                            setObjectField(state, "icon", icon)
                        }
                    }
                }

            flashlightRepositoryImplClass
                .after("loadFlashlightInfo")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    if (leveledFlashTile) {
                        val getCurrentUserIdMethodName = flashlightRepositoryImplClass.findMethods(
                            Pattern.compile("getCurrentUserId.*", Pattern.CASE_INSENSITIVE)
                        ).firstOrNull()?.name ?: return@run

                        val currentUserId = callMethod<Int>(thisObj, getCurrentUserIdMethodName) ?: return@run

                        @Suppress("UNCHECKED_CAST")
                        val defaultEnabledLevelForUser = getObjectField(thisObj, "defaultEnabledLevelForUser") as? ConcurrentHashMap<Int, Int>
                        defaultEnabledLevelForUser?.put(currentUserId, getFlashlightLevel(Xprefs.getInt("flashPCT", 50) / 100f))
                    }
                }

            flashlightRepositoryImplClass
                .after("setLevel")
                .run { hookParam ->
                    if (leveledFlashTile) {
                        val persist = hookParam.args.getOrNull(1) as? Boolean ?: false
                        if (persist) {
                            val level = hookParam.args.getOrNull(0) as? Int ?: return@run
                            thread {
                                Xprefs.edit().putInt("flashPCT", ((level * 100f) / getMaxFlashLevel()).roundToInt()).apply()
                            }
                        }
                    }
                }

            flashlightTileClass
                .before("newTileState")
                .run { hookParam ->
                    if (mTile == null) {
                        mTile = hookParam.thisObject
                    }
                }

            flashlightTileClass
                .before("handleClick")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    val state = getObjectField(thisObj, "mState") ?: return@run
                    val handlesSecondary = getObjectField(state, "handlesSecondaryClick") as? Boolean ?: false

                    if (!handlesSecondary || !leveledFlashTile) return@run

                    if (handleFlashLongClick()) {
                        hookParam.result = null
                    }
                }

            flashlightTileClass
                .after("handleUpdateState")
                .run { hookParam ->
                    val state = hookParam.args.getOrNull(0) ?: return@run

                    setObjectField(state, "handlesSecondaryClick", leveledFlashTile)

                    if (leveledFlashTile) {
                        val isEnabled = getObjectField(state, "value") as? Boolean ?: false

                        if (isEnabled && mLevelDrawable != null) {
                            val icon = drawableIconClass.clazz.constructors[0].newInstance(mLevelDrawable)
                            setObjectField(state, "icon", icon)
                        }

                        val subTitle = XPLauncher.moduleResources.getString(R.string.sliding_tile_subtitle)
                        setObjectField(state, "secondaryLabel", subTitle)
                    }
                }

            qsTileImplClass
                .before("handleSecondaryClick")
                .run { hookParam ->
                    if (leveledFlashTile && hookParam.thisObject == mTile) {
                        val tile = mTile ?: return@run
                        ReflectedMethod.ofName(flashlightTileClass, "handleClick").invokeOriginal(tile, hookParam.args.getOrNull(0))
                        hookParam.result = null
                    }
                }

            flashlightTileClass
                .before("handleLongClick")
                .run { hookParam ->
                    if (leveledFlashTile && handleFlashLongClick()) {
                        hookParam.result = null
                    }
                }

            flashlightTileClass
                .after("newTileState")
                .run { hookParam ->
                    hookParam.result?.let { setObjectField(it, "handlesLongClick", true) }
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun handleFlashLongClick(): Boolean {
        showAlertSlider()
        return true
    }

    private fun showAlertSlider() {
        StatusbarGestures.collapseQSPanel()
        val flashlightSliderCallback = object : AlertSlider.SliderEventCallback {
            override fun onStartTrackingTouch(slider: Any) {}

            override fun onStopTrackingTouch(slider: Any) {
                val value = callMethod<Float>(slider, "getValue") ?: return

                thread {
                    Xprefs.edit().putInt("flashPCT", ((value * 100f) / getMaxFlashLevel()).roundToInt()).apply()
                }

                if (!isFlashOn()) {
                    SystemUtils.setFlash(true, animateFlashlight)
                }
            }

            override fun onValueChange(slider: Any, value: Float, fromUser: Boolean) {
                thread {
                    if (isFlashOn()) {
                        SystemUtils.setFlash(true, value.roundToInt(), false)
                    }
                }
            }

            override fun onCreate(alertSlider: AlertSlider) {
                registerFlashlightLevelListener(alertSlider)
            }

            override fun onDismiss(alertSlider: AlertSlider) {
                unregisterFlashlightLevelListener(alertSlider)
            }
        }

        val alertSlider = object : AlertSlider(
            mContext,
            getFlashlightLevel(Xprefs.getInt("flashPCT", 50) / 100f),
            1,
            getMaxFlashLevel(),
            1,
            flashlightSliderCallback
        ) {
            override fun onChanged(newVal: Int) {
                setSliderCurrentValue(newVal)
            }
        }

        alertSlider.show()
    }

    class LevelDrawable(private val mContext: Context) : Drawable() {
        private var mCurrentLevel = 0
        private var mMinLevel = 0
        private var mMaxLevel = 0
        private var mFilledResId = 0
        private var mOutlineResId = 0
        private var mPaddingTopPercent = 0f
        private var mPaddingBottomPercent = 0f
        private var mOutlineDeadZoneTopPercent = 0f

        private var mLoadedFilledDrawable: Drawable? = null
        private var mLoadedOutlineDrawable: Drawable? = null

        fun init(
            minLevel: Int,
            maxLevel: Int,
            filledResId: Int,
            outlineResId: Int,
            paddingTopPercent: Float,
            paddingBottomPercent: Float,
            outlineDeadZoneTopPercent: Float
        ) {
            this.mMinLevel = minLevel
            this.mMaxLevel = maxLevel
            this.mPaddingTopPercent = paddingTopPercent
            this.mPaddingBottomPercent = paddingBottomPercent
            this.mOutlineDeadZoneTopPercent = outlineDeadZoneTopPercent

            if (this.mLoadedFilledDrawable == null || this.mFilledResId != filledResId) {
                this.mLoadedFilledDrawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources, filledResId, mContext.theme)
            }
            this.mFilledResId = filledResId

            if (this.mLoadedOutlineDrawable == null || this.mOutlineResId != outlineResId) {
                this.mLoadedOutlineDrawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources, outlineResId, mContext.theme)
            }
            this.mOutlineResId = outlineResId

            invalidateSelf()
        }

        fun updateState(currentLevel: Int) {
            if (this.mCurrentLevel != currentLevel) {
                this.mCurrentLevel = currentLevel
                invalidateSelf()
            }
        }

        override fun draw(canvas: Canvas) {
            val filled = mLoadedFilledDrawable ?: return
            val outline = mLoadedOutlineDrawable ?: return
            val bounds = bounds
            val width = bounds.width()
            val height = bounds.height()

            if (width <= 0 || height <= 0) return

            val overallPaddingTopPx = (height * mPaddingTopPercent).toInt()
            val overallPaddingBottomPx = (height * mPaddingBottomPercent).toInt()
            val contentActualHeight = height - overallPaddingTopPx - overallPaddingBottomPx

            var outlineDeadZonePx = (contentActualHeight * mOutlineDeadZoneTopPercent).toInt()
            if (outlineDeadZonePx < 0) outlineDeadZonePx = 0

            val effectiveProgressHeight = contentActualHeight - outlineDeadZonePx

            var levelPercentage = 0f
            if (mMaxLevel > mMinLevel) {
                levelPercentage = (mCurrentLevel - mMinLevel).toFloat() / (mMaxLevel - mMinLevel).toFloat()
            }
            levelPercentage = levelPercentage.coerceIn(0f, 1f)

            val filledPartOfEffectiveProgressHeight = (effectiveProgressHeight * levelPercentage).toInt()
            val outlinePartOfEffectiveProgressHeight = effectiveProgressHeight - filledPartOfEffectiveProgressHeight

            val splitYAbsolute = overallPaddingTopPx + outlineDeadZonePx + outlinePartOfEffectiveProgressHeight

            canvas.save()
            canvas.clipRect(0, splitYAbsolute, width, height - overallPaddingBottomPx)
            filled.bounds = bounds
            filled.draw(canvas)
            canvas.restore()

            canvas.save()
            canvas.clipRect(0, overallPaddingTopPx, width, splitYAbsolute)
            outline.bounds = bounds
            outline.draw(canvas)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            mLoadedFilledDrawable?.alpha = alpha
            mLoadedOutlineDrawable?.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            mLoadedFilledDrawable?.colorFilter = colorFilter
            mLoadedOutlineDrawable?.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = mLoadedFilledDrawable?.intrinsicWidth ?: 0

        override fun getIntrinsicHeight(): Int = mLoadedFilledDrawable?.intrinsicHeight ?: 0
    }
}
