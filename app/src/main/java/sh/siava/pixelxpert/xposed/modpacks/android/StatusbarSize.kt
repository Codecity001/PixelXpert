package sh.siava.pixelxpert.xposed.modpacks.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.Surface
import android.view.View
import android.view.WindowManager
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setIntField
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

@FrameworkModPack
@LauncherModPack
@SystemUIModPack
class StatusbarSize(context: Context) : XposedModPack(context) {

    companion object {
        private const val BOUNDS_POSITION_TOP = 1

        @JvmField
        var sizeFactor: Int = 100

        @JvmField
        var noCutoutEnabled: Boolean = false

        private var sNoCutoutHookInstalled = false
        private var sNoCutoutHookSuppressed = false

        @JvmStatic
        fun isNoCutoutLayoutActive(context: Context?): Boolean {
            if (!noCutoutEnabled || sNoCutoutHookSuppressed || context == null) return false
            return runCatching {
                context.display != null && context.display?.cutout == null
            }.getOrDefault(false)
        }

        @JvmStatic
        fun installEarlyNoCutoutHook(classLoader: ClassLoader?, enabled: Boolean) {
            if (sNoCutoutHookSuppressed) return
            noCutoutEnabled = enabled
            if (sNoCutoutHookInstalled) return

            runCatching {
                val wmDisplayCutoutClass = ReflectedClass.of("com.android.server.wm.utils.WmDisplayCutout", classLoader)
                val displayCutoutClass = ReflectedClass.of("android.view.DisplayCutout", classLoader)
                val noCutout = getStaticObjectField(displayCutoutClass.clazz, "NO_CUTOUT")

                wmDisplayCutoutClass
                    .before("getDisplayCutout")
                    .run { hookParam ->
                        if (noCutoutEnabled) {
                            hookParam.setResult(noCutout)
                        }
                    }

                sNoCutoutHookInstalled = true
            }.onFailure { t ->
                Logger.log("StatusbarSize: failed to install early display cutout hook", t)
            }
        }

        @JvmStatic
        fun suppressNoCutoutHookForBoot() {
            sNoCutoutHookSuppressed = true
            noCutoutEnabled = false
        }

        @SuppressLint("DiscouragedApi", "InternalInsetResource")
        private fun getBaseStatusBarHeight(context: Context, landscape: Boolean): Int {
            val configuration = Configuration(context.resources.configuration).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
            }
            val resources = context.createConfigurationContext(configuration).resources
            var id = resources.getIdentifier(
                if (landscape) "status_bar_height_landscape" else "status_bar_height_portrait",
                "dimen",
                "android"
            )
            if (id == 0) {
                id = resources.getIdentifier("status_bar_height", "dimen", "android")
            }
            return if (id == 0) 0 else resources.getDimensionPixelSize(id)
        }

        private fun isLandscape(context: Context): Boolean {
            return context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }

        private fun isLandscapeForRotation(context: Context, targetRotation: Int): Boolean {
            return runCatching {
                val currentRotation = context.display?.rotation ?: 0
                val changesOrientation = ((currentRotation - targetRotation + 4) % 2) != 0
                isLandscape(context) != changesOrientation
            }.getOrDefault(targetRotation == Surface.ROTATION_90 || targetRotation == Surface.ROTATION_270)
        }
    }

    private var edited = false
    private var mForceApplyHeight = false

    override fun onPreferenceUpdated(vararg keys: String) {
        noCutoutEnabled = !sNoCutoutHookSuppressed && XPrefs.getBoolean("noCutoutEnabled", false)

        mForceApplyHeight = XPrefs.getBoolean("allScreenRotations", false) ||
                XPrefs.getBoolean("systemIconsMultiRow", false) ||
                XPrefs.getBoolean("notificationAreaMultiRow", false)

        sizeFactor = XPrefs.getSliderInt("statusbarHeightFactor", 100)
    }

    private fun shouldApplyHeight(context: Context?): Boolean {
        return sizeFactor != 100 || edited || mForceApplyHeight || isNoCutoutLayoutActive(context)
    }

    private fun getStatusBarHeight(context: Context, landscape: Boolean): Int {
        val baseHeight = getBaseStatusBarHeight(context, landscape)
        return Math.round(baseHeight * sizeFactor / 100f)
    }

    private fun getControllerContext(statusBarWindowController: Any?): Context {
        if (statusBarWindowController != null) {
            runCatching {
                val ctx = getObjectField(statusBarWindowController, "mContext")
                if (ctx is Context) return ctx
            }
        }
        return mContext
    }

    private fun applyCachedStatusBarHeight(statusBarWindowController: Any?) {
        if (statusBarWindowController == null) return
        val context = getControllerContext(statusBarWindowController)
        val landscape = isLandscape(context)
        if (!shouldApplyHeight(context)) return

        val height = getStatusBarHeight(context, landscape)
        if (height > 0) {
            setIntField(statusBarWindowController, "mBarHeight", height)
        }
    }

    private fun applyStatusBarLayoutParams(layoutParams: Any?, context: Context, rotation: Int?) {
        if (!shouldApplyHeight(context) || layoutParams !is WindowManager.LayoutParams) return

        if (layoutParams.type == WindowManager.LayoutParams.TYPE_STATUS_BAR) {
            val landscape = if (rotation == null) isLandscape(context) else isLandscapeForRotation(context, rotation)
            if (shouldApplyHeight(context)) {
                val height = getStatusBarHeight(context, landscape)
                applyStatusBarLayoutParams(layoutParams, height)
            }
            applyStatusBarLayoutParams(getLayoutParamsArray(layoutParams, "paramsForRotation"), context)
        }
    }

    private fun applyStatusBarLayoutParams(paramsForRotation: Array<WindowManager.LayoutParams>?, context: Context) {
        if (paramsForRotation == null) return
        for (rotation in paramsForRotation.indices) {
            val rotationParams = paramsForRotation[rotation] ?: continue
            if (!shouldApplyHeight(context)) continue
            val landscape = isLandscapeForRotation(context, rotation)
            val height = getStatusBarHeight(context, landscape)
            applyStatusBarLayoutParams(rotationParams, height)
        }
    }

    private fun applyStatusBarLayoutParams(layoutParams: WindowManager.LayoutParams, height: Int) {
        if (height <= 0) return
        if (layoutParams.height != WindowManager.LayoutParams.MATCH_PARENT) {
            layoutParams.height = height
        }
        applyInsetsFrameProviders(getObjectArray(layoutParams, "providedInsets"), height)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getLayoutParamsArray(target: Any, fieldName: String): Array<WindowManager.LayoutParams>? {
        return runCatching {
            getObjectField(target, fieldName) as? Array<WindowManager.LayoutParams>
        }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun getObjectArray(target: Any, fieldName: String): Array<Any?>? {
        return runCatching {
            getObjectField(target, fieldName) as? Array<Any?>
        }.getOrNull()
    }

    private fun applyInsetsFrameProviders(providers: Array<Any?>?, height: Int) {
        if (providers == null) return
        val insets = Insets.of(0, height, 0, 0)
        for (provider in providers) {
            if (provider == null) continue
            runCatching {
                callMethod(provider, "setInsetsSize", insets)
            }
        }
    }

    private fun syncStatusBarLayoutParams(statusBarWindowController: Any?, updateWindow: Boolean) {
        if (statusBarWindowController == null) return
        runCatching {
            val context = getControllerContext(statusBarWindowController)
            if (!shouldApplyHeight(context)) return

            applyCachedStatusBarHeight(statusBarWindowController)
            applyStatusBarLayoutParams(getObjectField(statusBarWindowController, "mLpChanged"), context, null)

            val layoutParams = getObjectField(statusBarWindowController, "mLp")
            applyStatusBarLayoutParams(layoutParams, context, null)

            if (updateWindow && layoutParams is WindowManager.LayoutParams) {
                val windowManager = getObjectField(statusBarWindowController, "mWindowManager")
                val statusBarWindowView = getObjectField(statusBarWindowController, "mStatusBarWindowView")
                if (windowManager != null && statusBarWindowView is View) {
                    callMethod(windowManager, "updateViewLayout", statusBarWindowView, layoutParams)
                }
            }
        }
    }

    override fun onPackageLoaded(param: PackageReadyParam) {
        runCatching {
            if (XPLauncher.isSystemServer) {
                runCatching {
                    installEarlyNoCutoutHook(param.classLoader, noCutoutEnabled)

                    val wmDisplayCutoutClass = ReflectedClass.of("com.android.server.wm.utils.WmDisplayCutout", param.classLoader)
                    wmDisplayCutoutClass
                        .after("getDisplayCutout")
                        .run { hookParam ->
                            if (noCutoutEnabled) return@run
                            if (sizeFactor >= 100 && !edited) return@run
                            if (!shouldApplyHeight(mContext)) return@run

                            val displayCutout = hookParam.getResult<DisplayCutout?>() ?: return@run
                            val targetHeight = getStatusBarHeight(mContext, isLandscape(mContext))
                            if (targetHeight <= 0) return@run

                            val mBounds = getObjectField(displayCutout, "mBounds")
                            val mRects = getObjectField(mBounds, "mRects") as? Array<Rect> ?: return@run
                            val boundTop = mRects.getOrNull(BOUNDS_POSITION_TOP) ?: return@run
                            boundTop.bottom = minOf(boundTop.bottom, targetHeight)

                            val mSafeInsets = getObjectField(displayCutout, "mSafeInsets") as? Rect ?: return@run
                            mSafeInsets.top = minOf(mSafeInsets.top, targetHeight)
                        }
                }
            }

            val currentHeightConsumer = ReflectedClass.ReflectionConsumer { hookParam ->
                runCatching {
                    var context = mContext
                    val firstArg = hookParam.args.getOrNull(0)
                    if (firstArg is Context) {
                        context = firstArg
                    } else if (hookParam.thisObject != null) {
                        context = getControllerContext(hookParam.thisObject)
                    }
                    if (!shouldApplyHeight(context)) return@runCatching
                    val height = getStatusBarHeight(context, isLandscape(context))
                    if (height <= 0) return@runCatching
                    edited = true
                    hookParam.setResult(height)
                }
            }

            val rotationHeightConsumer = ReflectedClass.ReflectionConsumer { hookParam ->
                runCatching {
                    val context = hookParam.args.getOrNull(0) as? Context ?: return@runCatching
                    val rotation = hookParam.args.getOrNull(1) as? Int ?: return@runCatching
                    if (!shouldApplyHeight(context)) return@runCatching
                    val height = getStatusBarHeight(context, isLandscapeForRotation(context, rotation))
                    if (height <= 0) return@runCatching
                    edited = true
                    hookParam.setResult(height)
                }
            }

            runCatching {
                val systemBarUtilsClass = ReflectedClass.of("com.android.internal.policy.SystemBarUtils", param.classLoader)
                systemBarUtilsClass.before("getStatusBarHeight").run(currentHeightConsumer)
                systemBarUtilsClass.before("getStatusBarHeightForRotation").run(rotationHeightConsumer)
            }

            runCatching {
                val statusBarWindowControllerImplClass = ReflectedClass.ofIfPossible(
                    "com.android.systemui.statusbar.window.StatusBarWindowControllerImpl",
                    param.classLoader
                )

                val cachedStatusBarHeightConsumer = ReflectedClass.ReflectionConsumer { hookParam ->
                    runCatching { applyCachedStatusBarHeight(hookParam.thisObject) }
                }

                statusBarWindowControllerImplClass.afterConstruction().run(cachedStatusBarHeightConsumer)
                statusBarWindowControllerImplClass.before("attach").run(cachedStatusBarHeightConsumer)
                statusBarWindowControllerImplClass.before("applyHeight").run(cachedStatusBarHeightConsumer)
                statusBarWindowControllerImplClass.before("refreshStatusBarHeight").run(cachedStatusBarHeightConsumer)
                statusBarWindowControllerImplClass.before("getStatusBarHeight").run(currentHeightConsumer)
                statusBarWindowControllerImplClass.after("attach").run { hookParam -> syncStatusBarLayoutParams(hookParam.thisObject, true) }
                statusBarWindowControllerImplClass.after("apply").run { hookParam -> syncStatusBarLayoutParams(hookParam.thisObject, true) }
                statusBarWindowControllerImplClass.after("applyHeight").run { hookParam -> syncStatusBarLayoutParams(hookParam.thisObject, false) }
                statusBarWindowControllerImplClass.after("refreshStatusBarHeight").run { hookParam -> syncStatusBarLayoutParams(hookParam.thisObject, true) }

                val layoutParamsConsumer = ReflectedClass.ReflectionConsumer { hookParam ->
                    val context = getControllerContext(hookParam.thisObject)
                    val rotation = hookParam.args.getOrNull(0) as? Int
                    applyStatusBarLayoutParams(hookParam.getResult<Any?>(), context, rotation)
                }

                statusBarWindowControllerImplClass.after("getBarLayoutParams").run(layoutParamsConsumer)
                statusBarWindowControllerImplClass.after("getBarLayoutParamsForRotation").run(layoutParamsConsumer)
            }
        }
    }
}
