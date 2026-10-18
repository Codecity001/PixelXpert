package sh.siava.pixelxpert.xposed.modpacks

import android.content.Context
import android.graphics.Color
import android.os.RemoteException
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.ModuleFolderOperations
import sh.siava.pixelxpert.xposed.utils.StringFormatter
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@SystemUIModPack
class MiscSettings(context: Context) : XposedModPack(context) {

    override fun onPreferenceUpdated(vararg key: String) {
        val netstatColorful = Xprefs.getBoolean("networkStatsColorful", false)
        val netStatsStartMonthStart = Xprefs.getSliderInt("NetworkStatsMonthStart", 1)

        StringFormatter.RXColor = if (netstatColorful) Xprefs.getInt("networkStatDLColor", Color.GREEN) else null
        StringFormatter.TXColor = if (netstatColorful) Xprefs.getInt("networkStatULColor", Color.RED) else null
        StringFormatter.NetStatStartBase = Xprefs.getString("NetworkStatsStartBase", "0")?.toIntOrNull() ?: 0
        StringFormatter.NetStatsStartTime = try {
            LocalTime.parse(Xprefs.getString("NetworkStatsStartTime", "0:0"), DateTimeFormatter.ofPattern("H:m"))
        } catch (e: Exception) {
            LocalTime.MIDNIGHT
        }

        StringFormatter.NetStatsDayOf = if (StringFormatter.NetStatStartBase == StringFormatter.NET_STAT_TYPE_MONTH) {
            netStatsStartMonthStart
        } else {
            Xprefs.getString("NetworkStatsWeekStart", "1")?.toIntOrNull() ?: 1
        }

        StringFormatter.refreshAll()

        if (key.isNotEmpty()) {
            when (key[0]) {
                "sysui_tuner" -> updateSysUITuner()
                "volumeStps" -> setVolumeSteps()
                "force_volte" -> forceVolte()
                "force_hotspot" -> forceHotspot()
            }
        } else {
            setDisplayOverride()
            updateSysUITuner()
            setVolumeSteps()
            forceVolte()
            forceHotspot()
        }
    }

    private fun forceHotspot() {
        if (Xprefs.getBoolean("force_hotspot", false)) {
            XPLauncher.enqueueProxyCommand { proxy ->
                proxy.runRootCommand("cmd wifi force-country-code enabled US")
            }
        }
    }

    private fun forceVolte() {
        if (Xprefs.getBoolean("force_volte", false)) {
            XPLauncher.enqueueProxyCommand { proxy ->
                proxy.runRootCommand("setprop persist.dbg.volte_avail_ovr 1; setprop persist.dbg.vonr_avail_ovr 1")
            }
        }
    }

    private fun setDisplayOverride() {
        if (!Xprefs.getBoolean("displayOverrideEnabled", false)) return

        val displayOverride = Xprefs.getSliderFloat("displayOverride", 100f) / 100f
        XPLauncher.enqueueProxyCommand { proxy ->
            try {
                val sizeResult = proxy.runRootCommand("wm size").getOrNull(0) ?: return@enqueueProxyCommand
                val physicalSizes = sizeResult.replace("Physical size: ", "").split("x")
                val w = physicalSizes[0].trim().toInt()
                val h = physicalSizes[1].trim().toInt()

                val overrideW = (w * displayOverride).roundToInt()
                val overrideH = (h * displayOverride).roundToInt()

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
                val sysUITunerEnabled = Xprefs.getBoolean("sysui_tuner", false)
                val mode = if (sysUITunerEnabled) "enable" else "disable"
                proxy.runRootCommand("pm $mode com.android.systemui/.tuner.TunerActivity")
            } catch (ignored: Exception) {
            }
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
    }
}
