package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.util.ArraySet
import android.view.View
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

/**
 * Applies the module's per-area icon hide lists to SystemUI.
 *
 * The pack watches `IconManager` construction to learn which icon container
 * it is dealing with - status bar, shade header or keyguard, told apart by
 * the container's resource id - then replaces that container's
 * `mIgnoredSlots` contents with the matching preference set. The keyguard
 * list additionally always hides the app-switch slot, which makes no sense on
 * the lock screen.
 */
@SystemUIModPack
class StatusIconTuner(context: Context) : XposedModPack(context) {

	companion object {
		private var SBIgnoredIcons: MutableSet<String> = ArraySet()
		private var KGIgnoredIcons: MutableSet<String> = ArraySet()
		private var QSIgnoredIcons: MutableSet<String> = ArraySet()
	}

	private var mSBIconContainer: Any? = null
	private var mQSIconContainer: Any? = null
	private var mKGIconContainer: Any? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		SBIgnoredIcons = Xprefs.getStringSet("SBIgnoredIcons", ArraySet())!!
		KGIgnoredIcons = Xprefs.getStringSet("KGIgnoredIcons", ArraySet())!!
		QSIgnoredIcons = Xprefs.getStringSet("QSIgnoredIcons", ArraySet())!!

		KGIgnoredIcons.add(StatusbarMods.APP_SWITCH_SLOT) // we don't need it on keyguard

		setIgnoredIcons(mSBIconContainer, SBIgnoredIcons)
		setIgnoredIcons(mKGIconContainer, KGIgnoredIcons)
		setIgnoredIcons(mQSIconContainer, QSIgnoredIcons)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		var IconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.IconManager")
		if (IconManagerClass.clazz == null) { // pre 15beta3
			IconManagerClass =
				ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconController\$IconManager")
		}

		IconManagerClass
			.beforeConstruction()
			.run { param ->
				try {
					val iconContainer = param.args[0] as View

					// helps getting exception if it's in QS
					val id = mContext.resources.getResourceName((iconContainer.parent.parent as View).id)

					if (id.contains("status_bar_end_side_content")) { // statusbar specific id
						mSBIconContainer = iconContainer
						setIgnoredIcons(iconContainer, SBIgnoredIcons)
					} else if (id.contains("shade_header_system_icons")) { // QS specific id
						mQSIconContainer = iconContainer
						setIgnoredIcons(iconContainer, QSIgnoredIcons)
					} else if (id.contains("system_icons_container")) { // KG specific id
						mKGIconContainer = iconContainer
						setIgnoredIcons(iconContainer, KGIgnoredIcons)
					}
				} catch (ignored: Throwable) {
				}
			}
	}

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		mSBIconContainer?.let { outState["statusBar"] = it }
		mQSIconContainer?.let { outState["quickSettings"] = it }
		mKGIconContainer?.let { outState["keyguard"] = it }
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		mSBIconContainer = savedState["statusBar"]
		mQSIconContainer = savedState["quickSettings"]
		mKGIconContainer = savedState["keyguard"]

		setIgnoredIcons(mSBIconContainer, SBIgnoredIcons)
		setIgnoredIcons(mQSIconContainer, QSIgnoredIcons)
		setIgnoredIcons(mKGIconContainer, KGIgnoredIcons)
	}

	private fun setIgnoredIcons(container: Any?, ignorableSlots: MutableSet<String>) {
		try {
			//noinspection unchecked
			@Suppress("UNCHECKED_CAST")
			val ignoredSlots = getObjectField(container, "mIgnoredSlots") as MutableCollection<String>

			ignoredSlots.clear()

			for (slot in ignorableSlots) {
				if (!ignoredSlots.contains(slot)) {
					ignoredSlots.add(slot)
				}
			}

			(container as View).requestLayout()
		} catch (ignored: Throwable) {
		}
	}
}
