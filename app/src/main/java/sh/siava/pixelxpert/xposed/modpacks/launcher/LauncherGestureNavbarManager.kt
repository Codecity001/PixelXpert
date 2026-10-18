package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.math.roundToInt

@LauncherModPack
class LauncherGestureNavbarManager(context: Context) : XposedModPack(context) {

    companion object {
        private var navPillColorAccent = false
        private var widthFactor = 1f
        private var gesPillHeightFactor = 100
    }

    private var mColorReplaced = false
    private var mStashedHandleLightColor = 0
    private var mStashedHandleDarkColor = 0
    private var mIsHooked = false

    override fun onPreferenceUpdated(vararg key: String) {
        navPillColorAccent = Xprefs.getBoolean("navPillColorAccent", false)

        widthFactor = Xprefs.getSliderInt("GesPillWidthModPos", 50) * 0.02f
        gesPillHeightFactor = Xprefs.getSliderInt("GesPillHeightFactor", 100)

        if (Xprefs.getBoolean("HideNavbar", false)) {
            widthFactor = 0f
        }

        if (mIsHooked && key.isNotEmpty() && listOf(
                "GesPillWidthModPos",
                "GesPillHeightFactor",
                "HideNavbar",
                "navPillColorAccent"
            ).contains(key[0])
        ) {
            SystemUtils.doubleToggleDarkMode()
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val stashedHandleViewClass = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.StashedHandleView")
            val taskbarActivityContextClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarActivityContext")

            mIsHooked = true

            taskbarActivityContextClass
                .after("init")
                .run { hookParam ->
                    val controllers = hookParam.thisObject?.let { getObjectField(it, "mControllers") } ?: return@run
                    val stashedHandleViewController = getObjectField(controllers, "stashedHandleViewController") ?: return@run

                    setAdditionalInstanceField(
                        stashedHandleViewController,
                        "OriginalStashedHandleHeight",
                        getObjectField(stashedHandleViewController, "mStashedHandleHeight")
                    )

                    val origWidth = getIntField(stashedHandleViewController, "mStashedHandleWidth")
                    setObjectField(
                        stashedHandleViewController,
                        "mStashedHandleWidth",
                        (widthFactor * origWidth).roundToInt()
                    )

                    val origHeight = getAdditionalInstanceField(stashedHandleViewController, "OriginalStashedHandleHeight") as? Int ?: 0
                    setObjectField(
                        stashedHandleViewController,
                        "mStashedHandleHeight",
                        (origHeight * gesPillHeightFactor / 100f).roundToInt()
                    )
                }

            stashedHandleViewClass
                .afterConstruction()
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    mStashedHandleLightColor = getObjectField(thisObj, "mStashedHandleLightColor") as? Int ?: 0
                    mStashedHandleDarkColor = getObjectField(thisObj, "mStashedHandleDarkColor") as? Int ?: 0
                }

            stashedHandleViewClass
                .before("updateHandleColor")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    if (navPillColorAccent || mColorReplaced) {
                        val lightColor = if (navPillColorAccent) {
                            mContext.resources.getColor(android.R.color.system_accent1_200, mContext.theme)
                        } else {
                            mStashedHandleLightColor
                        }
                        val darkColor = if (navPillColorAccent) {
                            mContext.resources.getColor(android.R.color.system_accent1_600, mContext.theme)
                        } else {
                            mStashedHandleDarkColor
                        }
                        setObjectField(thisObj, "mStashedHandleLightColor", lightColor)
                        setObjectField(thisObj, "mStashedHandleDarkColor", darkColor)
                        mColorReplaced = true
                    }
                }
        } catch (ignored: Throwable) {
        }
    }
}
