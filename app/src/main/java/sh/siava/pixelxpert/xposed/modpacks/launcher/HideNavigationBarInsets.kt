package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import android.graphics.Insets
import android.view.WindowManager
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.lang.reflect.Array

@LauncherModPack
class HideNavigationBarInsets(context: Context) : XposedModPack(context) {

    private var hideNavbarInsets = false

    override fun onPreferenceUpdated(vararg key: String) {
        hideNavbarInsets = Xprefs.getBoolean("HideNavbarInsets", false)
        if (key.isNotEmpty() && key[0] == "HideNavbarInsets") {
            SystemUtils.killSelf()
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val taskbarActivityContextClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarActivityContext")
            taskbarActivityContextClass
                .before("notifyUpdateLayoutParams")
                .run { hookParam ->
                    if (!hideNavbarInsets) return@run

                    val thisObj = hookParam.thisObject ?: return@run
                    val layoutParams = getObjectField<WindowManager.LayoutParams>(thisObj, "mWindowLayoutParams")
                    transformLayoutParams(layoutParams)

                    val rotationParams = layoutParams?.let {
                        getObjectField<Array<WindowManager.LayoutParams>>(it, "paramsForRotation")
                    } ?: return@run

                    for (rotationLayoutParams in rotationParams) {
                        transformLayoutParams(rotationLayoutParams)
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun transformLayoutParams(layoutParams: WindowManager.LayoutParams?) {
        if (layoutParams == null) return

        val providedInsets = getObjectField<Any>(layoutParams, "providedInsets") ?: return
        val length = Array.getLength(providedInsets)
        for (i in 0 until length) {
            val insetsFrame = Array.get(providedInsets, i) ?: continue
            if (!insetsFrame.toString().contains("type=navigationBars")) continue

            try {
                callMethod<Any>(insetsFrame, "setInsetsSize", Insets.NONE)
            } catch (ignored: Throwable) {
            }
        }
    }
}
