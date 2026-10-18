package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.os.Binder
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@FrameworkModPack
class SystemScreenRecord(context: Context) : XposedModPack(context) {

    companion object {
        private var insecureScreenRecord = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        insecureScreenRecord = Xprefs.getBoolean("InsecureScreenRecord", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val displayManagerServiceClass = ReflectedClass.of("com.android.server.display.DisplayManagerService")

            displayManagerServiceClass
                .before("canProjectSecureVideo")
                .run { hookParam ->
                    try {
                        val thisObj = hookParam.thisObject ?: return@run
                        if (insecureScreenRecord && callMethod(thisObj, "validatePackageName", Binder.getCallingUid(), Constants.SYSTEM_UI_PACKAGE) == true) {
                            hookParam.result = true
                        }
                    } catch (ignored: Throwable) {
                    }
                }
        } catch (ignored: Throwable) {
        }
    }
}
