package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Handler
import android.os.Looper
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.findFieldIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setAdditionalInstanceField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.GoogleMonochromeIconFactory
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@LauncherModPack
class LauncherThemedIcons(context: Context) : XposedModPack(context) {

    companion object {
        private var forceThemedLauncherIcons = false
    }

    private var mIconBitmapSize = 0
    private var launcherAppState: Any? = null

    override fun onPreferenceUpdated(vararg key: String) {
        forceThemedLauncherIcons = Xprefs.getBoolean("ForceThemedLauncherIcons", false)

        if (key.isNotEmpty() && key[0] == "ForceThemedLauncherIcons") {
            reloadIcons()
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val baseIconFactoryClass = ReflectedClass.of("com.android.launcher3.icons.BaseIconFactory")
            val launcherAppStateClass = ReflectedClass.of("com.android.launcher3.LauncherAppState")

            if (findFieldIfExists(baseIconFactoryClass.clazz, "mIconBitmapSize") == null) {
                // Built-in generator present - no need to patch
                Xprefs.edit().putBoolean("DisableThemedIconsPref", true).apply()
                return
            }

            Xprefs.edit().putBoolean("DisableThemedIconsPref", false).apply()

            launcherAppStateClass
                .afterConstruction()
                .run { hookParam ->
                    launcherAppState = hookParam.thisObject
                }

            baseIconFactoryClass
                .afterConstruction()
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    mIconBitmapSize = getIntField(thisObj, "mIconBitmapSize")
                }

            ReflectedClass.of(AdaptiveIconDrawable::class.java)
                .after("getMonochrome")
                .run { hookParam ->
                    try {
                        if (hookParam.result == null && forceThemedLauncherIcons) {
                            for (stackTraceElement in Thread.currentThread().stackTrace) {
                                if (stackTraceElement.methodName == "getIcon") {
                                    return@run
                                }
                            }

                            val thisObj = hookParam.thisObject ?: return@run
                            var mono = getAdditionalInstanceField(thisObj, "mMonoFactoryPX") as? GoogleMonochromeIconFactory
                            if (mono == null) {
                                val drawable = thisObj as? AdaptiveIconDrawable ?: return@run
                                mono = GoogleMonochromeIconFactory(drawable, mIconBitmapSize)
                                setAdditionalInstanceField(thisObj, "mMonoFactoryPX", mono)
                            }
                            hookParam.result = mono
                        }
                    } catch (ignored: Throwable) {
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun reloadIcons() {
        val las = launcherAppState ?: return
        val iconCache = getObjectField(las, "iconCache") ?: return

        Handler(Looper.getMainLooper()).post {
            try {
                callMethod(getObjectField(iconCache, "cache"), "clear")
                callMethod(getObjectField(iconCache, "iconDb"), "clear")
                callMethod(getObjectField(las, "model"), "forceReload")
            } catch (ignored: Throwable) {
            }
        }
    }
}
