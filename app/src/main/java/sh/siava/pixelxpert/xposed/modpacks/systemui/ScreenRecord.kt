package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.lang.reflect.Method

@SystemUIModPack
class ScreenRecord(context: Context) : XposedModPack(context) {

    companion object {
        private var insecureScreenRecord = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        insecureScreenRecord = Xprefs.getBoolean("InsecureScreenRecord", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        ReflectedClass.of(MediaProjection::class.java)
            .before("createVirtualDisplay")
            .run { runParam ->
                val method = runParam.method as? Method
                if (insecureScreenRecord && method?.parameterCount == 8) {
                    val flags = runParam.args.getOrNull(4) as? Int ?: return@run
                    runParam.args[4] = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                }
            }
    }
}
