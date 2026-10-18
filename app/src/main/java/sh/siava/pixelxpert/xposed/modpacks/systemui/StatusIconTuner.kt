package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.util.ArraySet
import android.view.View
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.modpacks.systemui.StatusbarMods.Companion.APP_SWITCH_SLOT
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@SystemUIModPack
class StatusIconTuner(context: Context) : XposedModPack(context) {

    private var sbIconContainer: Any? = null
    private var qsIconContainer: Any? = null
    private var kgIconContainer: Any? = null

    companion object {
        private var sbIgnoredIcons: MutableSet<String> = ArraySet()
        private var kgIgnoredIcons: MutableSet<String> = ArraySet()
        private var qsIgnoredIcons: MutableSet<String> = ArraySet()
    }

    override fun onPreferenceUpdated(vararg key: String) {
        sbIgnoredIcons = Xprefs.getStringSet("SBIgnoredIcons", ArraySet()) ?: ArraySet()
        kgIgnoredIcons = Xprefs.getStringSet("KGIgnoredIcons", ArraySet()) ?: ArraySet()
        qsIgnoredIcons = Xprefs.getStringSet("QSIgnoredIcons", ArraySet()) ?: ArraySet()

        kgIgnoredIcons.add(APP_SWITCH_SLOT) // We don't need it on keyguard

        setIgnoredIcons(sbIconContainer, sbIgnoredIcons)
        setIgnoredIcons(kgIconContainer, kgIgnoredIcons)
        setIgnoredIcons(qsIconContainer, qsIgnoredIcons)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        var iconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.IconManager")
        if (iconManagerClass?.clazz == null) {
            iconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconController\$IconManager")
        }

        iconManagerClass?.beforeConstruction()?.run { runParam ->
            try {
                val iconContainer = runParam.args[0] as? View ?: return@run
                val grandParent = (iconContainer.parent?.parent as? View) ?: return@run
                val id = mContext.resources.getResourceName(grandParent.id)

                if (id.contains("status_bar_end_side_content")) {
                    sbIconContainer = iconContainer
                    setIgnoredIcons(iconContainer, sbIgnoredIcons)
                } else if (id.contains("shade_header_system_icons")) {
                    qsIconContainer = iconContainer
                    setIgnoredIcons(iconContainer, qsIgnoredIcons)
                } else if (id.contains("system_icons_container")) {
                    kgIconContainer = iconContainer
                    setIgnoredIcons(iconContainer, kgIgnoredIcons)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun setIgnoredIcons(container: Any?, ignorableSlots: Set<String>) {
        if (container == null) return
        try {
            val ignoredSlots = getObjectField<MutableCollection<String>>(container, "mIgnoredSlots") ?: return
            ignoredSlots.clear()

            for (slot in ignorableSlots) {
                if (!ignoredSlots.contains(slot)) {
                    ignoredSlots.add(slot)
                }
            }

            (container as? View)?.requestLayout()
        } catch (_: Throwable) {}
    }
}
