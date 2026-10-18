package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.modpacks.systemui.BatteryDataProvider.Companion.isCharging
import sh.siava.pixelxpert.xposed.utils.StringFormatter
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools
import sh.siava.pixelxpert.xposed.utils.toolkit.ColorUtils.getColorAttrDefaultColor
import java.lang.ref.WeakReference
import java.util.regex.Pattern

@SystemUIModPack
class KeyguardMods(context: Context) : XposedModPack(context) {

    private var maxChargingCurrent = 0f
    private var maxChargingVoltage = 0f
    private var temperature = 0f

    private val carrierStringFormatter = StringFormatter()
    private val clockStringFormatter = StringFormatter()
    private var composeKGMiddleCustomTextView: TextView? = null
    private var keyguardRootView: ViewGroup? = null
    private var colorExtractor: Any? = null
    private var dozing = false
    private var supportsDarkText = false
    private var hideLockScreenUserAvatar = false

    companion object {
        const val EXTRA_MAX_CHARGING_CURRENT: String = "max_charging_current"
        const val EXTRA_MAX_CHARGING_VOLTAGE: String = "max_charging_voltage"
        const val EXTRA_TEMPERATURE: String = "temperature"

        private const val WALLPAPER_DIM_AMOUNT_DIMMED: Float = 0.6f
        private val isRenderingShortcut = ThreadLocal.withInitial { false }

        @JvmField
        var KeyGuardDimAmount: Float = -1f

        private var showChargingInfo = false
        private var customCarrierTextEnabled = false
        private var customCarrierText = ""
        private var carrierTextController: Any? = null
        private var isCarrierCallbackHooked = false

        private var kgMiddleCustomText = ""
        private var disableUnlockHintAnimation = false
        private var temperatureUnitF = false
        private var transparentBGcolor = false
        private var forceAODwCharging = false
        private var animateFlashlight = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        disableUnlockHintAnimation = Xprefs.getBoolean("DisableUnlockHintAnimation", false)
        kgMiddleCustomText = Xprefs.getString("KGMiddleCustomText", "") ?: ""
        customCarrierTextEnabled = Xprefs.getBoolean("carrierTextMod", false)
        customCarrierText = Xprefs.getString("carrierTextValue", "") ?: ""
        showChargingInfo = Xprefs.getBoolean("ShowChargingInfo", false)
        temperatureUnitF = Xprefs.getBoolean("TemperatureUnitF", false)
        hideLockScreenUserAvatar = Xprefs.getBoolean("HideLockScreenUserAvatar", false)
        forceAODwCharging = Xprefs.getBoolean("ForceAODwCharging", false)
        KeyGuardDimAmount = Xprefs.getSliderFloat("KeyGuardDimAmount", -1f) / 100f
        transparentBGcolor = Xprefs.getBoolean("KeyguardBottomButtonsTransparent", false)
        animateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false)

        if (key.isNotEmpty()) {
            when (key[0]) {
                "KGMiddleCustomText" -> updateMiddleTexts()
                "carrierTextValue", "carrierTextMod" -> {
                    if (customCarrierTextEnabled) {
                        setCarrierText()
                    } else {
                        try {
                            val manager = try {
                                getObjectField(carrierTextController, "mCarrierTextManager")
                            } catch (_: Throwable) {
                                getObjectField(carrierTextController, "carrierTextManager")
                            }
                            if (manager != null) {
                                callMethod(manager, "updateCarrierText")
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    @SuppressLint("DiscouragedApi", "DefaultLocale")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val carrierTextControllerClass = ReflectedClass.of("com.android.keyguard.CarrierTextController")
        val keyguardIndicationControllerClass = ReflectedClass.of("com.android.systemui.statusbar.KeyguardIndicationController")
        val scrimControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimController")
        val scrimStateEnum = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimState")
        val keyguardStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.KeyguardStatusBarView")
        val centralSurfacesImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.CentralSurfacesImpl")
        val notificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController")
        val ambientDisplayConfigurationClass = ReflectedClass.of("android.hardware.display.AmbientDisplayConfiguration")
        val defaultShortcutsSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection")
        val smartspaceSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection")
        val defaultNotificationStackScrollLayoutSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.DefaultNotificationStackScrollLayoutSection")
        val keyguardIndicationControllerGoogleClass = ReflectedClass.of("com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle")
        val shortcutElementProviderClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.composable.elements.ShortcutElementProvider")
        val notificationShadeWindowViewClass = ReflectedClass.of("com.android.systemui.shade.NotificationShadeWindowView")
        val backgroundKtClass = ReflectedClass.of("androidx.compose.foundation.BackgroundKt")
        val iconKtClass = ReflectedClass.of("androidx.compose.material3.IconKt")
        val keyguardQuickAffordanceViewBinderClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder")
        val keyguardQuickAffordanceViewClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.view.KeyguardQuickAffordanceView")

        notificationShadeWindowViewClass
            .after("onAttachedToWindow")
            .run { runParam ->
                val thisView = runParam.thisObject as? ViewGroup ?: return@run
                ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(thisView.findViewById(idOf("start_button")))
                ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(thisView.findViewById(idOf("end_button")))
            }

        // Compose-based shortcuts transparency
        shortcutElementProviderClass?.before("KeyguardShortcut")?.run { isRenderingShortcut.set(true) }
        shortcutElementProviderClass?.after("KeyguardShortcut")?.run { isRenderingShortcut.set(false) }

        backgroundKtClass
            .before(Pattern.compile("background.*|m.*background.*"))
            .run { runParam ->
                if (transparentBGcolor && isRenderingShortcut.get() && runParam.args.size == 3 && runParam.args[1] is Long) {
                    runParam.args[1] = 0L // Color.Transparent
                }
            }

        iconKtClass
            .before(Pattern.compile("Icon.*|m.*Icon.*"))
            .run { runParam ->
                if (transparentBGcolor && isRenderingShortcut.get() && runParam.args.size >= 4 && runParam.args[3] is Long) {
                    val wallpaperTextColorAccent = getColorAttrDefaultColor(
                        mContext,
                        mContext.resources.getIdentifier("wallpaperTextColorAccent", "attr", mContext.packageName)
                    )
                    runParam.args[3] = (wallpaperTextColorAccent.toLong()) shl 32
                }
            }

        keyguardQuickAffordanceViewBinderClass?.after("bind")?.run { runParam ->
            ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(runParam.args.firstOrNull())
        }

        keyguardQuickAffordanceViewClass?.afterConstruction()?.run { runParam ->
            ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(runParam.thisObject)
        }

        keyguardQuickAffordanceViewClass?.after("setBackground")?.run { runParam ->
            ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(runParam.thisObject)
        }

        ReflectedClass.of(CameraManager::class.java)
            .before("setTorchMode")
            .run { runParam ->
                if (SystemUtils.isInternalTorchCall) return@run
                if (animateFlashlight) {
                    val enabled = runParam.args.getOrNull(1) as? Boolean ?: false
                    SystemUtils.setFlash(enabled, true)
                    runParam.result = null
                }
            }

        ReflectedClass.of(CameraManager::class.java)
            .before("turnOnTorchWithStrengthLevel")
            .run { runParam ->
                if (SystemUtils.isInternalTorchCall) return@run
                if (animateFlashlight) {
                    val level = runParam.args.getOrNull(1) as? Int ?: return@run
                    if (!SystemUtils.isFlashOn()) {
                        SystemUtils.setFlash(true, level, true)
                        runParam.result = null
                    }
                }
            }

        defaultShortcutsSectionClass
            .after("addViews")
            .run { runParam ->
                val res = mContext.resources
                val root = runParam.args[0]
                val endId = res.getIdentifier("end_button", "id", mContext.packageName)
                val startId = res.getIdentifier("start_button", "id", mContext.packageName)

                ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(callMethod(root, "findViewById", endId))
                ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(callMethod(root, "findViewById", startId))
            }

        defaultNotificationStackScrollLayoutSectionClass
            .after("applyConstraints")
            .run { runParam ->
                val constraintSet = runParam.args[0]
                val textView = composeKGMiddleCustomTextView ?: return@run

                callMethod(
                    constraintSet,
                    "connect",
                    textView.id,
                    ConstraintSet.BOTTOM,
                    idOf("nssl_placeholder"),
                    ConstraintSet.TOP
                )
            }

        smartspaceSectionClass
            .after("addViews")
            .run(this::onSmartspaceViewsAdded)

        try {
            ReflectedClass.of("com.android.systemui.keyguard.ui.view.KeyguardRootView")
                .after("<init>")
                .run { runParam ->
                    val view = runParam.thisObject as? View ?: return@run
                    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            keyguardRootView = v as? ViewGroup
                            createMiddleTextViews()
                            updateMiddleTexts()
                        }
                        override fun onViewDetachedFromWindow(v: View) {}
                    })
                }
        } catch (_: Throwable) {}

        ambientDisplayConfigurationClass
            .after("alwaysOnEnabled")
            .run { runParam ->
                if (forceAODwCharging) {
                    val current = runParam.result as? Boolean ?: false
                    runParam.result = current || isCharging()
                }
            }

        notificationPanelViewControllerClass
            .before("startUnlockHintAnimation")
            .run { runParam ->
                if (disableUnlockHintAnimation) runParam.result = null
            }

        centralSurfacesImplClass
            .afterConstruction()
            .run { runParam ->
                colorExtractor = getObjectField(runParam.thisObject, "mColorExtractor")
            }

        keyguardStatusBarViewClass
            .after("updateVisibilities")
            .run { runParam ->
                val multiUserAvatar = getObjectField(runParam.thisObject, "mMultiUserAvatar") as? View ?: return@run
                val isUserSwitcherEnabled = getBooleanField(runParam.thisObject, "mIsUserSwitcherEnabled")
                multiUserAvatar.visibility = if (!hideLockScreenUserAvatar && isUserSwitcherEnabled) View.VISIBLE else View.GONE
            }

        keyguardIndicationControllerGoogleClass
            .after("computePowerIndication")
            .run { runParam ->
                if (showChargingInfo) {
                    val result = runParam.result as? String ?: ""
                    val shownTemperature = if (temperatureUnitF) (temperature * 1.8f) + 32f else temperature
                    val unit = if (temperatureUnitF) "F" else "C"

                    runParam.result = String.format(
                        "%s\n%.1fW (%.1fV, %.1fA) • %.0fº%s",
                        result,
                        maxChargingCurrent * maxChargingVoltage,
                        maxChargingVoltage,
                        maxChargingCurrent,
                        shownTemperature,
                        unit
                    )
                }
            }

        BatteryDataProvider.registerStatusCallback { _, batteryStatusIntent ->
            maxChargingCurrent = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / 1000000f
            maxChargingVoltage = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / 1000000f
            temperature = batteryStatusIntent.getIntExtra(EXTRA_TEMPERATURE, 0) / 10f
        }

        scrimControllerClass
            .before(Pattern.compile("scheduleUpdate.*"))
            .run { runParam ->
                if (KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1) return@run

                setObjectField(runParam.thisObject, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount)
                val constants = scrimStateEnum.clazz?.enumConstants ?: emptyArray()
                for (constant in constants) {
                    if (constant != null) {
                        setObjectField(constant, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount)
                    }
                }
            }

        ReflectedClass.of(WallpaperManager::class.java)
            .after("getWallpaperDimAmount")
            .run { runParam ->
                if (KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1 || runParam.result == WALLPAPER_DIM_AMOUNT_DIMMED) return@run
                runParam.result = (325 * KeyGuardDimAmount - 60) / 255f
            }

        carrierStringFormatter.registerCallback(this::setCarrierText)

        val carrierInitHook: ReflectedClass.ReflectionConsumer = ReflectedClass.ReflectionConsumer { runParam ->
            carrierTextController = runParam.thisObject
            val carrierTextCallback = try {
                getObjectField(carrierTextController, "mCarrierTextCallback")
            } catch (_: Throwable) {
                getObjectField(carrierTextController, "carrierTextCallback")
            }
            setCarrierText()
            if (!isCarrierCallbackHooked && carrierTextCallback != null) {
                ReflectedClass.of(carrierTextCallback.javaClass)
                    .before("updateCarrierInfo")
                    .run { runParam1 ->
                        if (customCarrierTextEnabled) {
                            runParam1.result = null
                        }
                    }
                isCarrierCallbackHooked = true
            }
        }

        carrierTextControllerClass.after("onInit").run(carrierInitHook)
        carrierTextControllerClass.after("onViewAttached").run(carrierInitHook)

        keyguardIndicationControllerClass
            .after("updateDeviceEntryIndication")
            .run { runParam ->
                val newDozing = getObjectField(runParam.thisObject, "mDozing") as? Boolean ?: false
                if (dozing != newDozing) {
                    dozing = newDozing
                    setMiddleColor()
                }
            }
    }

    private fun createMiddleTextViews() {
        if (composeKGMiddleCustomTextView == null) {
            composeKGMiddleCustomTextView = TextView(mContext).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                maxLines = 2
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                letterSpacing = 0.03f
                id = View.generateViewId()
                z = 100f
            }
        }
    }

    private fun setMiddleColor() {
        colorExtractor?.let { extractor ->
            val colors = callMethod(extractor, "getColors", WallpaperManager.FLAG_LOCK)
            if (colors != null) {
                supportsDarkText = callMethod(colors, "supportsDarkText") as? Boolean ?: false
            }
        }
        val color = if (dozing || !supportsDarkText) Color.WHITE else Color.BLACK

        try {
            composeKGMiddleCustomTextView?.setShadowLayer(1f, 1f, 1f, if (color == Color.BLACK) Color.TRANSPARENT else Color.BLACK)
            composeKGMiddleCustomTextView?.setTextColor(color)
        } catch (_: Throwable) {}
    }

    private fun setCarrierText() {
        if (!customCarrierTextEnabled) return

        try {
            val view = getObjectField(carrierTextController, "mView") as? TextView ?: return
            view.post {
                view.text = carrierStringFormatter.formatString(customCarrierText)
            }
        } catch (_: Throwable) {}
    }

    private fun updateMiddleTexts() {
        val text: CharSequence? = if (kgMiddleCustomText.isNotEmpty()) {
            clockStringFormatter.formatString(kgMiddleCustomText)
        } else {
            null
        }

        try {
            setMiddleTextCompose(text)
        } catch (_: Throwable) {}
    }

    private fun setMiddleTextCompose(text: CharSequence?) {
        val rootView = keyguardRootView ?: return
        val textView = composeKGMiddleCustomTextView ?: return
        rootView.post {
            if (text == null) {
                rootView.removeView(textView)
            } else {
                ReflectionTools.reAddView(rootView, textView)
                textView.text = text
            }
        }
    }

    private fun onSmartspaceViewsAdded(param: HookHelper.RunParam) {
        try {
            createMiddleTextViews()
            try {
                val smartSpaceContainer = getObjectField(param.thisObject, "dateView") as? LinearLayout
                keyguardRootView = smartSpaceContainer?.rootView?.findViewById(idOf("keyguard_root_view"))
            } catch (_: Throwable) {}

            updateMiddleTexts()
            setMiddleColor()
        } catch (_: Throwable) {}
    }

    class ControlledLaunchableImageViewBackgroundDrawable(
        private val mDrawable: Drawable,
        parentView: ImageView
    ) : Drawable() {
        private val parentImageViewReference: WeakReference<ImageView> = WeakReference(parentView)
        private val mContext: Context = parentView.context

        companion object {
            @JvmStatic
            fun captureDrawable(view: Any?) {
                val imageView = view as? ImageView ?: return
                try {
                    val background = imageView.background ?: return
                    if (background is ControlledLaunchableImageViewBackgroundDrawable) return

                    imageView.background = ControlledLaunchableImageViewBackgroundDrawable(background.current.mutate(), imageView)
                } catch (_: Throwable) {}
            }
        }

        override fun mutate(): Drawable {
            val parent = parentImageViewReference.get() ?: return this
            return ControlledLaunchableImageViewBackgroundDrawable(mDrawable.mutate(), parent)
        }

        override fun setTint(tintColor: Int) {
            if (!transparentBGcolor) {
                mDrawable.setTint(tintColor)
            } else {
                mDrawable.setTint(Color.TRANSPARENT)
            }
        }

        override fun setTintList(tintList: ColorStateList?) {
            if (transparentBGcolor) {
                mDrawable.setTint(Color.TRANSPARENT)

                val parentView = parentImageViewReference.get()
                val parentDrawable = parentView?.drawable
                if (parentDrawable != null) {
                    @SuppressLint("DiscouragedApi")
                    val wallpaperTextColorAccent = getColorAttrDefaultColor(
                        mContext,
                        mContext.resources.getIdentifier("wallpaperTextColorAccent", "attr", mContext.packageName)
                    )
                    parentDrawable.setTint(wallpaperTextColorAccent)
                }
            } else {
                mDrawable.setTintList(tintList)
            }
        }

        override fun draw(canvas: Canvas) {
            mDrawable.draw(canvas)
        }

        override fun jumpToCurrentState() {
            mDrawable.jumpToCurrentState()
        }

        override fun setAlpha(alpha: Int) {
            mDrawable.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            if (!transparentBGcolor) {
                mDrawable.colorFilter = colorFilter
            }
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            @Suppress("DEPRECATION")
            return mDrawable.opacity
        }

        override fun getPadding(padding: Rect): Boolean {
            return mDrawable.getPadding(padding)
        }

        override fun getMinimumHeight(): Int {
            return mDrawable.minimumHeight
        }

        override fun getMinimumWidth(): Int {
            return mDrawable.minimumWidth
        }

        override fun isStateful(): Boolean {
            return mDrawable.isStateful
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            return mDrawable.setVisible(visible, restart)
        }

        override fun getOutline(outline: Outline) {
            mDrawable.getOutline(outline)
        }

        override fun isProjected(): Boolean {
            return mDrawable.isProjected
        }

        override fun setBounds(bounds: Rect) {
            mDrawable.bounds = bounds
        }

        override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
            mDrawable.setBounds(left, top, right, bottom)
        }

        override fun getCurrent(): Drawable {
            return mDrawable.current
        }

        override fun setState(stateSet: IntArray): Boolean {
            return mDrawable.state = stateSet
        }

        override fun getIntrinsicWidth(): Int {
            return mDrawable.intrinsicWidth
        }

        override fun getIntrinsicHeight(): Int {
            return mDrawable.intrinsicHeight
        }
    }
}
