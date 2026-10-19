package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import de.robv.android.xposed.XposedHelpers.getBooleanField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Controls whether the fingerprint sensor keeps listening while the device is
 * dozing.
 *
 * When the `fingerprintWhileDozing` preference is on (the default) the pack
 * stays out of the way entirely. When it is off, the hook forces
 * `KeyguardUpdateMonitor.shouldListenForFingerprint` to answer false whenever
 * `mDeviceInteractive` is false, so AOD never polls the sensor - which is what
 * users asking for the battery saving actually want.
 */
@SystemUIModPack
class FingerprintWhileDozing(context: Context) : XposedModPack(context) {

	companion object {
		private var fingerprintWhileDozing = true
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		fingerprintWhileDozing = Xprefs.getBoolean("fingerprintWhileDozing", true)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val KeyguardUpdateMonitorClass = ReflectedClass.of("com.android.keyguard.KeyguardUpdateMonitor")

		KeyguardUpdateMonitorClass
			.before("shouldListenForFingerprint")
			.run { param ->
				if (fingerprintWhileDozing) return@run

				if (!getBooleanField(param.thisObject, "mDeviceInteractive")) {
					param.setResult(false)
				}
			}
	}
}
