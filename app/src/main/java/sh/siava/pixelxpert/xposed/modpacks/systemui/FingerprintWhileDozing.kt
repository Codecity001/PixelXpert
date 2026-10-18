package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@SystemUIModPack
class FingerprintWhileDozing(context: Context) : XposedModPack(context) {

    companion object {
        private var fingerprintWhileDozing = true
    }

    override fun onPreferenceUpdated(vararg key: String) {
        fingerprintWhileDozing = Xprefs.getBoolean("fingerprintWhileDozing", true)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val keyguardUpdateMonitorClass = ReflectedClass.of("com.android.keyguard.KeyguardUpdateMonitor")

        keyguardUpdateMonitorClass
            .before("shouldListenForFingerprint")
            .run { runParam ->
                if (fingerprintWhileDozing) return@run

                val isInteractive = getBooleanField(runParam.thisObject, "mDeviceInteractive")
                if (!isInteractive) {
                    runParam.result = false
                }
            }
    }
}
