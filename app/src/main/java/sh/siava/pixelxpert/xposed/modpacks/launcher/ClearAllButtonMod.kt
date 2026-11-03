package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.res.ResourcesCompat
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.findMethodBestMatch
import java.lang.reflect.Method

/**
 * Adds a floating "clear all" button to the Recents screen.
 *
 * When the `RecentClearAllReposition` preference is on, the pack injects a
 * second [FrameLayout] button into `OverviewActionsView` during
 * `onFinishInflate`, pins it to the end/top edge of the screen, and wires its
 * click to the launcher's own `RecentsView.dismissAllTasks` through a
 * reflective [Method] captured at hook-registration time. The button mirrors
 * the system's visibility by re-applying every `RecentsView.setVisibility`
 * argument to itself, and re-tints its icon on `setColorTint` so it tracks
 * light/dark mode.
 *
 * Flipping the preference kills the launcher process so the hooks re-register
 * in the new configuration; the icon is read from the module's own resources
 * via [XPLauncher.moduleResources] rather than the launcher's.
 */
@LauncherModPack
class ClearAllButtonMod(context: Context) : XposedModPack(context) {
	private var recentView: Any? = null
	private var clearAllIcon: ImageView? = null
	private var clearAllButton: FrameLayout? = null
	private var overviewActionsView: FrameLayout? = null
	private var originalParentHeight = ViewGroup.LayoutParams.MATCH_PARENT
	private var dismissAllTasksMethod: Method? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		RecentClearAllReposition = Xprefs.getBoolean("RecentClearAllReposition", false)

		if (key.isEmpty()) {
			// `onFinishInflate` is not replayed, so a restored button is rewired
			// to this generation on the full pass.
			reapplyLiveState()
		} else if (key[0] == "RecentClearAllReposition") {
			applyButtonState()
		}
	}

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		recentView?.let { outState["recentView"] = it }
		clearAllIcon?.let { outState["clearAllIcon"] = it }
		clearAllButton?.let { outState["clearAllButton"] = it }
		overviewActionsView?.let { outState["overviewActionsView"] = it }
		outState["parentHeight"] = originalParentHeight
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		recentView = savedState["recentView"]
		clearAllIcon = savedState["clearAllIcon"] as? ImageView
		clearAllButton = savedState["clearAllButton"] as? FrameLayout
		overviewActionsView = savedState["overviewActionsView"] as? FrameLayout
		originalParentHeight = savedState["parentHeight"] as? Int ?: ViewGroup.LayoutParams.MATCH_PARENT
	}

	override fun onUnloaded() {
		// The replacement generation owns the saved views; make the listeners
		// that still point at this pack unable to act on stale state.
		clearAllButton = null
		clearAllIcon = null
		recentView = null
		overviewActionsView = null
		dismissAllTasksMethod = null
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val OverviewActionsViewClass = ReflectedClass.of("com.android.quickstep.views.OverviewActionsView")
		val RecentsViewClass = ReflectedClass.of("com.android.quickstep.views.RecentsView")
		dismissAllTasksMethod = findMethodBestMatch(RecentsViewClass.clazz, "dismissAllTasks", View::class.java)

		RecentsViewClass
			.afterConstruction()
			.run { param -> recentView = param.thisObject }

		RecentsViewClass
			.after("setColorTint")
			.run { param ->
				if (!RecentClearAllReposition) return@run

				clearAllIcon?.drawable?.setTintList(getThemedColor(mContext))
			}

		RecentsViewClass
			.after("setVisibility")
			.run { param ->
				if (clearAllButton == null) return@run

				clearAllButton!!.visibility = param.args[0] as Int
			}

		OverviewActionsViewClass
			.before("onFinishInflate")
			.run { param ->
				val parent = param.thisObject as FrameLayout
				overviewActionsView = parent
				if (!RecentClearAllReposition) return@run

				ensureButton(parent)
				clearAllButton!!.visibility = View.GONE
			}
	}

	private fun reapplyLiveState() {
		if (overviewActionsView == null && clearAllButton == null) return
		applyButtonState()
	}

	private fun applyButtonState() {
		val parent = overviewActionsView
		if (RecentClearAllReposition) {
			if (parent != null) {
				ensureButton(parent)
			}
		} else {
			val button = clearAllButton ?: return
			(button.parent as? ViewGroup)?.removeView(button)
			parent?.layoutParams?.height = originalParentHeight
			clearAllButton = null
			clearAllIcon = null
		}
	}

	private fun ensureButton(parent: FrameLayout) {
		if (clearAllButton == null) {
			val button = FrameLayout(mContext)
			val icon = ImageView(mContext)
			icon.setImageDrawable(
				ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, R.drawable.ic_clear_all, mContext.theme),
			)
			icon.drawable.setTintList(getThemedColor(mContext))
			button.addView(icon)

			val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			params.rightMargin =
				TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, Resources.getSystem().displayMetrics).toInt()
			params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
			button.layoutParams = params

			originalParentHeight = parent.layoutParams.height
			clearAllButton = button
			clearAllIcon = icon
		}

		clearAllButton!!.setOnClickListener { v ->
			val currentView = recentView
			if (currentView != null) {
				try {
					dismissAllTasksMethod?.invoke(currentView, v)
				} catch (ignored: Throwable) {
				}
			}
		}

		// Resize to the whole screen.
		parent.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		if (clearAllButton!!.parent == null) {
			parent.addView(clearAllButton)
		}
	}

	companion object {
		private var RecentClearAllReposition = false

		@JvmStatic
		fun getThemedColor(context: Context): ColorStateList? = getSystemAttrColor(
			context,
			if (SystemUtils.isDarkMode()) {
				android.R.attr.textColorPrimaryInverse
			} else {
				android.R.attr.textColorPrimary
			},
		)

		@JvmStatic
		fun getSystemAttrColor(context: Context, attr: Int): ColorStateList? =
			context.obtainStyledAttributes(intArrayOf(attr)).use { a ->
				a.getColorStateList(a.getIndex(0))
			}
	}
}
