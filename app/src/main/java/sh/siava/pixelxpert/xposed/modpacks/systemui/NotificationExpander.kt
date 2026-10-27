package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.tryParseInt
import java.util.Collection
import java.util.regex.Pattern

/**
 * Gives the notification footer its expand/collapse-all buttons and applies
 * the default expansion state.
 *
 * The footer buttons are rebuilt every time SystemUI inflates or recolours
 * `FooterView`: both mirror `dismiss_text`'s background and text colour so
 * they follow the theme, and [expandAll] walks the captured
 * `NotifCollection`'s read-only set flipping each row's `setUserExpanded`.
 * The same [expandAll] runs from the `setIsExpanded` hook when
 * `notificationDefaultExpansion` asks for expand-always on every shade open,
 * and the expand-all button visibility follows `notificationExpandallEnabled`.
 */
@SystemUIModPack
class NotificationExpander(context: Context) : XposedModPack(context) {

	companion object {
		private const val DEFAULT = 0
		private const val EXPAND_ALWAYS = 1

		/** Never read; kept beside the others deliberately. */
		private const val COLLAPSE_ALWAYS = 2

		@JvmStatic
		var notificationExpandallHookEnabled = true

		@JvmStatic
		var notificationExpandallEnabled = false

		private var notificationDefaultExpansion = DEFAULT
	}

	private var ExpandBtn: Button? = null
	private var CollapseBtn: Button? = null
	private var FooterView: FrameLayout? = null
	private var BtnLayout: LinearLayout? = null
	private var Scroller: View? = null
	private var NotifCollection: Any? = null
	private var mDismissContainer: LinearLayout? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		notificationExpandallEnabled = Xprefs.getBoolean("notificationExpandallEnabled", false)
		notificationExpandallHookEnabled = Xprefs.getBoolean("notificationExpandallHookEnabled", true)
		notificationDefaultExpansion = tryParseInt(Xprefs.getString("notificationDefaultExpansion", "0"), 0)

		if (key.isNotEmpty() && key[0]!!.equals("notificationExpandallEnabled")) {
			updateFooterBtn()
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val NotificationStackScrollLayoutClass = ReflectedClass.of(
			"com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
		)
		val FooterViewButtonClass = ReflectedClass.of("com.android.systemui.statusbar.notification.row.FooterViewButton")
		val NotifCollectionClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.statusbar.notification.collection.NotifCollection",
		)
		// this class is resolved but never hooked; kept so class lookup side effects stay identical
		val NotificationPanelViewControllerClass = ReflectedClass.of(
			"com.android.systemui.shade.NotificationPanelViewController",
		)
		val FooterViewClass = ReflectedClass.of("com.android.systemui.statusbar.notification.footer.ui.view.FooterView")

		//region default notification state
		NotificationStackScrollLayoutClass
			.before("setIsExpanded")
			.run { param ->
				val isExpanded = param.args[0] as Boolean
				if (isExpanded && notificationDefaultExpansion != DEFAULT) {
					expandAll(notificationDefaultExpansion == EXPAND_ALWAYS)
				}
			}
		//endregion

		// Notification Footer, where shortcuts should live
		FooterViewClass
			.after(Pattern.compile("updateColors.*"))
			.run { param -> updateFooterBtn() }

		FooterViewClass
			.after("onFinishInflate")
			.run { param ->
				FooterView = param.thisObject as FrameLayout

				FooterView!!.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
					override fun onViewAttachedToWindow(v: View) {
						updateFooterBtn()
					}

					override fun onViewDetachedFromWindow(v: View) {
					}
				})

				BtnLayout = LinearLayout(mContext)
				BtnLayout!!.setClipChildren(false)

				val BtnFrameParams = LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					ViewGroup.LayoutParams.WRAP_CONTENT,
				)
				BtnFrameParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
				BtnLayout!!.layoutParams = BtnFrameParams

				reAddView(BtnLayout!!, FooterView!!.findViewById(SystemUtils.idOf("history_button")))

				ExpandBtn = FooterViewButtonClass.clazz!!.getConstructor(Context::class.java).newInstance(mContext) as Button
				val expandArrows = ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, R.drawable.ic_expand, mContext.theme)
				ExpandBtn!!.foreground = expandArrows!!

				BtnLayout!!.addView(ExpandBtn!!)

				mDismissContainer = LinearLayout(mContext)
				reAddView(mDismissContainer!!, FooterView!!.findViewById(SystemUtils.idOf("dismiss_text")))

				BtnLayout!!.addView(mDismissContainer!!)

				ExpandBtn!!.setOnClickListener { expandAll(true) }

				CollapseBtn = FooterViewButtonClass.clazz!!.getConstructor(Context::class.java).newInstance(mContext) as Button
				val collapseArrows = ResourcesCompat.getDrawable(
					XPLauncher.moduleResources!!,
					R.drawable.ic_collapse,
					mContext.theme,
				)
				CollapseBtn!!.foreground = collapseArrows!!

				BtnLayout!!.addView(CollapseBtn!!)

				reAddView(BtnLayout!!, FooterView!!.findViewById(SystemUtils.idOf("settings_button")))

				CollapseBtn!!.setOnClickListener { expandAll(false) }

				updateFooterBtn()
				FooterView!!.addView(BtnLayout!!)
			}

		// theme changed
		FooterViewClass
			.after("updateColors")
			.run { param -> updateFooterBtn() }

		// grab notification container manager
		if (NotifCollectionClass.clazz != null) {
			NotifCollectionClass
				.afterConstruction()
				.run { param -> NotifCollection = param.thisObject }
		}

		// grab notification scroll page
		NotificationStackScrollLayoutClass
			.afterConstruction()
			.run { param -> Scroller = param.thisObject as View }
	}

	private fun updateFooterBtn() {
		if (FooterView == null) return // Footer not inflated yet

		FooterView!!.post {
			try {
				val clearAllButton = BtnLayout!!.findViewById<Button>(SystemUtils.idOf("dismiss_text"))!!

				clearAllButton.layoutParams!!.width = -1

				val dismissContainerParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
				dismissContainerParams.weight = 1f
				mDismissContainer!!.layoutParams = dismissContainerParams

				// must clone a fully new drawable for each to avoid effects conflict
				ExpandBtn!!.background = clearAllButton.background!!.constantState!!.newDrawable()
				CollapseBtn!!.background = clearAllButton.background!!.constantState!!.newDrawable()

				val textColor = clearAllButton.currentTextColor
				ExpandBtn!!.foreground!!.setTint(textColor)
				CollapseBtn!!.foreground!!.setTint(textColor)

				setLayouParams(ExpandBtn!!, true)
				setLayouParams(CollapseBtn!!, false)

				ExpandBtn!!.visibility = if (notificationExpandallEnabled) View.VISIBLE else View.GONE
				CollapseBtn!!.visibility = ExpandBtn!!.visibility
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun setLayouParams(button: Button, marginToLeft: Boolean) {
		val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, mContext.resources.displayMetrics).toInt()

		val btnParams = LinearLayout.LayoutParams(size, size)
		btnParams.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
		val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, mContext.resources.displayMetrics).toInt()

		if (marginToLeft) {
			btnParams.marginStart = margin
		} else {
			btnParams.marginEnd = margin
		}

		button.post { button.layoutParams = btnParams }
	}

	fun expandAll(expand: Boolean) {
		if (NotifCollection == null) return

		@Suppress("UNCHECKED_CAST")
		val entries = getObjectField(NotifCollection, "mReadOnlyNotificationSet") as Collection<Any>
		for (entry in entries.toArray()) {
			val row = getObjectField(entry, "row")
			if (row != null) {
				setRowExpansion(row, expand)
			}
		}

		if (!expand) {
			Scroller!!.requestLayout()
		}
	}

	private fun setRowExpansion(row: Any, expand: Boolean) {
		try {
			for (method in row.javaClass.declaredMethods) {
				if (method.name != "setUserExpanded") continue
				val parameterTypes = method.parameterTypes
				method.isAccessible = true
				if (parameterTypes.size == 3 && parameterTypes[0] == Boolean::class.javaPrimitiveType &&
					parameterTypes[1] == Boolean::class.javaPrimitiveType && parameterTypes[2].isEnum
				) {
					method.invoke(row, expand, true, getStaticObjectField(parameterTypes[2], "USER_ACTION"))
					return
				}
				if (parameterTypes.size == 2 && parameterTypes[0] == Boolean::class.javaPrimitiveType &&
					parameterTypes[1] == Boolean::class.javaPrimitiveType
				) {
					method.invoke(row, expand, true)
					return
				}
			}
		} catch (ignored: Throwable) {
		}
	}
}
