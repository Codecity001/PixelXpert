package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.FlexStatusIconContainer
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

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

	// The live flex container is module-owned, so it is only ever held as
	// `Any?` and reached reflectively. Keeping it lets the sort-plan slider
	// update the container after a hot reload, when the old generation's static
	// `sortPlan` is no longer what this generation's `setSortPlan` writes.
	private var mFlexContainer: Any? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		// WHY we check the old value? because if prefs is empty it will fill it up and count an unwanted change
		if (key.isNotEmpty() && key[0] == "systemIconsMultiRow") {
			val newsystemIconsMultiRow = Xprefs.getBoolean("systemIconsMultiRow", false)
			if (newsystemIconsMultiRow != systemIconsMultiRow) {
				SystemUtils.killSelf()
			}
		}
		systemIconsMultiRow = Xprefs.getBoolean("systemIconsMultiRow", false)
		val sortPlan = Xprefs.getString("systemIconSortPlan", FlexStatusIconContainer.SORT_CLEAN.toString())!!.toInt()
		FlexStatusIconContainer.setSortPlan(sortPlan)
		applySortPlanToRestoredContainer(sortPlan)
	}

	private fun applySortPlanToRestoredContainer(plan: Int) {
		val container = mFlexContainer ?: return

		try {
			// Calls the *instance's* class, which is the previous generation's
			// copy after a hot reload, so its renderer sees the new plan.
			container.javaClass.getMethod("setSortPlan", Int::class.javaPrimitiveType)
				.invoke(null, plan)
		} catch (ignored: Throwable) {
		}
	}

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		mFlexContainer?.let { outState["flexContainer"] = it }
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		mFlexContainer = savedState["flexContainer"]
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
				if (!systemIconsMultiRow) return@run

				try {
					val linearStatusbarIconContainer = param.args[0] as View

					// helps getting exception if it's in QS
					val id = mContext.resources.getResourceName((linearStatusbarIconContainer.parent.parent as View).id)
					if (!id.contains("status_bar_end_side_content")) return@run

					val flex = FlexStatusIconContainer(mContext, linearStatusbarIconContainer)
					mFlexContainer = flex
					flex.setPadding(linearStatusbarIconContainer.paddingLeft, 0, linearStatusbarIconContainer.paddingRight, 0)

					val flexParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
					flex.layoutParams = flexParams

					flex.foregroundGravity = Gravity.CENTER_VERTICAL or Gravity.END

					val parent = linearStatusbarIconContainer.parent as ViewGroup
					val index = parent.indexOfChild(linearStatusbarIconContainer)
					parent.addView(flex, index)
					parent.layoutParams!!.height = LinearLayout.LayoutParams.MATCH_PARENT
					linearStatusbarIconContainer.visibility = View.GONE // remove will crash the system
					param.args[0] = flex
				} catch (ignored: Throwable) {
				}
			}
	}
}
