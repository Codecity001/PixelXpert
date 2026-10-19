package sh.siava.pixelxpert.xposed.modpacks

import android.content.Context
import android.graphics.Color
import android.os.RemoteException
import io.github.libxposed.api.XposedModuleInterface
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.ModuleFolderOperations
import sh.siava.pixelxpert.xposed.utils.StringFormatter

/**
 * The SystemUI-process home of the module's "misc" settings. It hooks nothing;
 * instead it is the fan-out sink for every preference change: it pushes the
 * network-stats formatting state (colors, week/month start, start time) into
 * [StringFormatter] and then runs the requested root commands - display size
 * override, volume steps, SysUI tuner, and the VoLTE / hotspot debug overrides
 * - either on the matching key change or all of them at startup.
 *
 * Two quirks are deliberately preserved: the `"force_volte"` case falls through
 * into `"force_hotspot"` (Java `switch` without `break`), and the
 * `Xprefs == null` guard stays even though the Kotlin handle is `lateinit`
 * rather than a nulled Java field.
 */
@SystemUIModPack
class MiscSettings(context: Context) : XposedModPack(context) {

	override fun onPreferenceUpdated(vararg key: String?) {

		val netstatColorful = Xprefs.getBoolean("networkStatsColorful", false)

		val NetStatsStartMonthStart = Xprefs.getSliderInt("NetworkStatsMonthStart", 1)

		StringFormatter.RXColor = if (netstatColorful) Xprefs.getInt("networkStatDLColor", Color.GREEN) else null
		StringFormatter.TXColor = if (netstatColorful) Xprefs.getInt("networkStatULColor", Color.RED) else null
		StringFormatter.NetStatStartBase = Xprefs.getString("NetworkStatsStartBase", "0")!!.toInt()
		StringFormatter.NetStatsStartTime = LocalTime.parse(Xprefs.getString("NetworkStatsStartTime", "0:0"), DateTimeFormatter.ofPattern("H:m"))

		StringFormatter.NetStatsDayOf = if (StringFormatter.NetStatStartBase == StringFormatter.NET_STAT_TYPE_MONTH) {
			NetStatsStartMonthStart
		} else {
			Xprefs.getString("NetworkStatsWeekStart", "1")!!.toInt()
		}

		StringFormatter.refreshAll()

		if (key.isNotEmpty()) {
			// A Java `switch` throws on a null key, so `!!` keeps that behaviour.
			when (key[0]!!) {
				"sysui_tuner" -> updateSysUITuner()
				"volumeStps" -> setVolumeSteps()
				"force_volte" -> {
					force_volte()
					// No `break` in the original: this case falls through.
					force_hotspot()
				}
				"force_hotspot" -> force_hotspot()
			}
		} else {
			setDisplayOverride()

			updateSysUITuner()

			setVolumeSteps()

			force_volte()

			force_hotspot()
		}
	}

	private fun force_hotspot() {
		if (Xprefs.getBoolean("force_hotspot", false)) {
			XPLauncher.enqueueProxyCommand { proxy -> proxy.runRootCommand("cmd wifi force-country-code enabled US") }
		}
	}

	private fun force_volte() {
		if (Xprefs.getBoolean("force_volte", false)) {
			XPLauncher.enqueueProxyCommand { proxy -> proxy.runRootCommand("setprop persist.dbg.volte_avail_ovr 1; setprop persist.dbg.vonr_avail_ovr 1") }
		}
	}

	private fun setDisplayOverride() {
		if (!Xprefs.getBoolean("displayOverrideEnabled", false)) return

		val displayOverride = Xprefs.getSliderFloat("displayOverride", 100f) / 100f
		XPLauncher.enqueueProxyCommand { proxy ->
			try {
				val sizeResult = proxy.runRootCommand("wm size")[0]

				val physicalSizes = sizeResult.replace("Physical size: ", "").split("x")
				val w = physicalSizes[0].toInt()
				val h = physicalSizes[1].toInt()

				val overrideW = Math.round(w * displayOverride)
				val overrideH = Math.round(h * displayOverride)

				proxy.runRootCommand("wm size ${overrideW}x${overrideH}")
			} catch (e: RemoteException) {
				throw RuntimeException(e)
			}
		}
	}

	private fun setVolumeSteps() {
		val volumeStps = Xprefs.getSliderInt("volumeStps", 0)

		ModuleFolderOperations.applyVolumeSteps(volumeStps, XPrefs.MagiskRoot, false)
	}

	private fun updateSysUITuner() {
		XPLauncher.enqueueProxyCommand { proxy ->
			try {
				val SysUITunerEnabled = Xprefs.getBoolean("sysui_tuner", false)
				val mode = if (SysUITunerEnabled) "enable" else "disable"

				proxy.runRootCommand("pm $mode com.android.systemui/.tuner.TunerActivity")
			} catch (ignored: Exception) {
			}
		}
	}

	/** No hooks here; the pack only reacts to preference changes. */
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
	}
}
