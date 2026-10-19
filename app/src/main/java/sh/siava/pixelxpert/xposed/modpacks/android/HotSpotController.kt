package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Pushes the module's hotspot settings into every soft access point the
 * framework builds. `SoftApConfiguration` is assembled field by field by the
 * wifi stack and exposes no setter for any of this, so the pack hooks its
 * constructor and overwrites the four fields the preferences own right after
 * the object exists: hidden SSID, whether client control needs approval, the
 * shutdown timeout and the client cap.
 *
 * The constructor hook is installed once per load and then reads the
 * companion's cached values, so a preference change is picked up by the next
 * configuration the framework creates. Each
 * field is only written when the preference is meaningful (timeout and client
 * cap above zero, approval toggle on), which is what keeps a disabled
 * preference from clamping the framework's own defaults.
 */
@FrameworkModPack
class HotSpotController(context: Context) : XposedModPack(context) {

	companion object {
		private var hotSpotTimeoutMillis = 0L
		private var hotSpotHideSSID = false
		private var hotSpotMaxClients = 0
		private var hotspotDisableApproval = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		val clients = Xprefs.getSliderInt("hotSpotMaxClients", 0)

		hotSpotTimeoutMillis = (Xprefs.getSliderFloat("hotSpotTimeoutSecs", 0f) * 1000L).toLong()
		hotSpotHideSSID = Xprefs.getBoolean("hotSpotHideSSID", false)
		hotspotDisableApproval = Xprefs.getBoolean("hotspotDisableApproval", false)
		hotSpotMaxClients = clients
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			val SoftApConfiguration = ReflectedClass.of("android.net.wifi.SoftApConfiguration")

			SoftApConfiguration
				.afterConstruction()
				.run { param ->
					setObjectField(param.thisObject, "mHiddenSsid", hotSpotHideSSID)

					if (hotspotDisableApproval) {
						setObjectField(param.thisObject, "mClientControlByUser", false)
					}

					if (hotSpotTimeoutMillis > 0) {
						setObjectField(param.thisObject, "mShutdownTimeoutMillis", hotSpotTimeoutMillis)
					}

					if (hotSpotMaxClients > 0) {
						setObjectField(param.thisObject, "mMaxNumberOfClients", hotSpotMaxClients)
					}
				}
		} catch (ignored: Throwable) {
		}
	}
}
