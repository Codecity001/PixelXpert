package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import android.os.UserHandle
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@LauncherModPack
class PixelXpertIconUpdater(context: Context) : XposedModPack(context) {

    private var launcherModel: Any? = null

    override fun onPreferenceUpdated(vararg key: String) {}

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val launcherModelClass = ReflectedClass.of("com.android.launcher3.LauncherModel")
            val baseActivityClass = ReflectedClass.of("com.android.launcher3.BaseActivity")

            baseActivityClass
                .after("onResume")
                .run {
                    try {
                        val model = launcherModel ?: return@run
                        callMethod<Any>(model, "onAppIconChanged", BuildConfig.APPLICATION_ID, UserHandle.getUserHandleForUid(0))
                    } catch (ignored: Throwable) {
                    }
                }

            launcherModelClass
                .afterConstruction()
                .run { hookParam ->
                    launcherModel = hookParam.thisObject
                }
        } catch (ignored: Throwable) {
        }
    }
}
