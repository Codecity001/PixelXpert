package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.FlexStatusIconContainer
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Lets the status bar's end-side icon area hold more than one row of icons.
 *
 * The status bar's icon container is swapped for a
 * [FlexStatusIconContainer] that is inserted beside it and fed every icon the
 * original container would have held; the original is only hidden, because
 * removing it outright crashes SystemUI. Toggling the preference itself
 * restarts SystemUI through [SystemUtils.killSelf] since the container cannot
 * be rebuilt live, and the sort plan slider is pushed into
 * [FlexStatusIconContainer] on every update.
 */
@SystemUIModPack
class MultiStatusbarRows(context: Context) : XposedModPack(context) {

	companion object {
		private var systemIconsMultiRow = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		if (key.isNotEmpty() && key[0] == "systemIconsMultiRow") { //WHY we check the old value? because if prefs is empty it will fill it up and count an unwanted change
			val newsystemIconsMultiRow = Xprefs.getBoolean("systemIconsMultiRow", false)
			if (newsystemIconsMultiRow != systemIconsMultiRow) {
				SystemUtils.killSelf()
			}
		}
		systemIconsMultiRow = Xprefs.getBoolean("systemIconsMultiRow", false)
		FlexStatusIconContainer.setSortPlan(Xprefs.getString("systemIconSortPlan", FlexStatusIconContainer.SORT_CLEAN.toString())!!.toInt())
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		var IconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.IconManager")
		if (IconManagerClass.clazz == null) //pre 15beta3
		{
			IconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconController\$IconManager")
		}

		IconManagerClass
			.beforeConstruction()
			.run { param ->
				if (!systemIconsMultiRow) return@run

				try {
					val linearStatusbarIconContainer = param.args[0] as View

					val id = mContext.resources.getResourceName((linearStatusbarIconContainer.parent.parent as View).id) //helps getting exception if it's in QS
					if (!id.contains("status_bar_end_side_content")) return@run

					val flex = FlexStatusIconContainer(mContext, linearStatusbarIconContainer)
					flex.setPadding(linearStatusbarIconContainer.paddingLeft, 0, linearStatusbarIconContainer.paddingRight, 0)

					val flexParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
					flex.layoutParams = flexParams

					flex.foregroundGravity = Gravity.CENTER_VERTICAL or Gravity.END

					val parent = linearStatusbarIconContainer.parent as ViewGroup
					val index = parent.indexOfChild(linearStatusbarIconContainer)
					parent.addView(flex, index)
					parent.layoutParams!!.height = LinearLayout.LayoutParams.MATCH_PARENT
					linearStatusbarIconContainer.visibility = View.GONE //remove will crash the system
					param.args[0] = flex
				} catch (ignored: Throwable) {
				}
			}
	}
}
