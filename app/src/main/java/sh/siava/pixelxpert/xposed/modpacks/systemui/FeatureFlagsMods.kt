package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@SystemUIModPack
class FeatureFlagsMods(context: Context) : XposedModPack(context) {

    companion object {
        const val SIGNAL_DEFAULT: Int = 0
        const val SIGNAL_FORCE_LTE: Int = 1
        const val SIGNAL_FORCE_4G: Int = 2

        @JvmField
        var SBLTEIcon: Int = SIGNAL_DEFAULT

        private var enableClipboardSmartActions = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        SBLTEIcon = Xprefs.getString("LTE4GIconMod", SIGNAL_DEFAULT.toString())?.toIntOrNull() ?: SIGNAL_DEFAULT
        enableClipboardSmartActions = Xprefs.getBoolean("EnableClipboardSmartActions", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        ReflectedClass.of("android.provider.DeviceConfig")
            .before("getBoolean")
            .run { runParam ->
                if (runParam.args.size >= 2 && "systemui" == runParam.args[0] && "clipboard_overlay_show_actions" == runParam.args[1]) {
                    if (enableClipboardSmartActions) {
                        runParam.result = true
                    }
                }
            }

        ReflectedClass.of("com.android.settingslib.mobile.MobileMappings\$Config")
            .after("readConfig")
            .run { runParam ->
                if (SBLTEIcon == SIGNAL_DEFAULT) return@run

                runParam.result?.let {
                    setObjectField(it, "show4gForLte", SBLTEIcon == SIGNAL_FORCE_4G)
                }
            }
    }
}
