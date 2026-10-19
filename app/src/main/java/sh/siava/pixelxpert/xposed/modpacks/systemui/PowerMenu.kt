package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Expands SystemUI's global-actions (power) dialog when the "advanced power
 * menu" preference is on: the system's own "Power options" entry is relabelled
 * with the module's title and appended to the dialog's item list, and three
 * module-owned entries - reboot to bootloader, soft reboot, and SystemUI
 * restart - are added to the power items so they sit beside the normal reboot
 * actions in the order the dialog builds them.
 *
 * Every custom entry reaches the dialog as a [java.lang.reflect.Proxy]
 * implementing SystemUI's `LongPressAction` interface, with [PXCustomAction]
 * answering each method the dialog calls (`create`, `onPress`, `onLongPress`,
 * `shouldShow`, ...) - `create` inflates SystemUI's own
 * `global_actions_grid_item_lite` row so the added items are drawn exactly
 * like the built-in ones.
 */
@SystemUIModPack
class PowerMenu(context: Context) : XposedModPack(context) {
	private var mLongPressActionInterface: ReflectedClass? = null

	companion object {
		/** Whether the advanced entries are wired into the power dialog. */
		private var advancedPowerMenu = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		advancedPowerMenu = Xprefs.getBoolean("advancedPowerMenu", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val GlobalActionsDialogLiteClass = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite")
		val PowerOptionsAction = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite\$PowerOptionsAction")
		mLongPressActionInterface = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite\$LongPressAction")

		PowerOptionsAction
			.afterConstruction()
			.run { param ->
				if (!advancedPowerMenu) return@run

				setObjectField(param.thisObject, "mMessageResId", 0)
				setObjectField(param.thisObject, "mMessage", getString(R.string.advanced_option_button_title))
			}

		GlobalActionsDialogLiteClass
			.after("createActionItems")
			.run { param ->
				if (!advancedPowerMenu) return@run

				//noinspection unchecked
				@Suppress("UNCHECKED_CAST")
				val mItems = getObjectField(param.thisObject, "mItems") as ArrayList<Any>
				mItems.add(PowerOptionsAction.clazz!!.constructors[0].newInstance(param.thisObject))

				//noinspection unchecked
				@Suppress("UNCHECKED_CAST")
				val mPowerItems = getObjectField(param.thisObject, "mPowerItems") as ArrayList<Any>

				mPowerItems.add(getAction(BootloaderAction()))
				mPowerItems.add(getAction(SoftRebootAction()))
				mPowerItems.add(getAction(SystemUIRebootAction()))
			}
	}

	private fun getString(id: Int): String {
		return XPLauncher.moduleResources!!.getString(id)
	}

	private fun getAction(action: PXCustomAction): Any {
		return Proxy.newProxyInstance(
			mLongPressActionInterface!!.clazz!!.classLoader,
			arrayOf(mLongPressActionInterface!!.clazz!!),
			action,
		)
	}

	internal inner class BootloaderAction : PXCustomAction() {
		override fun onLongPress(): Boolean {
			return false
		}

		override fun onPress() {
			SystemUtils.restart("bootloader")
		}

		override fun getText(): String {
			return getString(R.string.reboot_bootloader_title)
		}

		@SuppressLint("DiscouragedApi")
		override fun getIcon(): Drawable? {
			val res = mContext.resources
			return ResourcesCompat.getDrawable(res, res.getIdentifier("ic_restart", "drawable", "android"), mContext.theme)
		}
	}

	internal inner class SoftRebootAction : PXCustomAction() {
		override fun onLongPress(): Boolean {
			return false
		}

		override fun onPress() {
			SystemUtils.restart("android")
		}

		override fun getText(): String {
			return getString(R.string.soft_reboot_title)
		}

		@SuppressLint("DiscouragedApi")
		override fun getIcon(): Drawable? {
			val res = mContext.resources
			return ResourcesCompat.getDrawable(res, res.getIdentifier("ic_restart", "drawable", "android"), mContext.theme)
		}
	}

	internal inner class SystemUIRebootAction : PXCustomAction() {
		override fun onLongPress(): Boolean {
			return false
		}

		override fun onPress() {
			SystemUtils.restart("systemUI")
		}

		override fun getText(): String {
			return getString(R.string.restart_systemui_title)
		}

		@SuppressLint("DiscouragedApi")
		override fun getIcon(): Drawable? {
			val res = mContext.resources
			return ResourcesCompat.getDrawable(res, res.getIdentifier("ic_restart", "drawable", "android"), mContext.theme)
		}
	}

	internal abstract inner class PXCustomAction : InvocationHandler {
		override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>): Any? {
			when (method.name) {
				"create" -> {
					//					Context context = (Context) args[0];
					//					View convertView = (View) args[1];
					val parent = args[2] as ViewGroup
					val layoutInflater = args[3] as LayoutInflater
					val res = mContext.resources

					return create(parent, layoutInflater, res)
				}

				"onPress" -> { //main action
					onPress()
					return null
				}

				"onLongPress" -> return onLongPress() //result: if longpress is handled by us

				"shouldShow" -> return true //used

				//unused methods
				"getLabelForAccessibility" -> return "" //haven't seen usage

				"showDuringKeyguard", //didn't see usage
				"showBeforeProvisioning", //didn't see usage
				"shouldBeSeparated" -> return false //didn't see usage

				"isEnabled" -> return true //didn't see usage

				"getMessageResId" -> return 0 //not used

				"getIcon" -> return getIcon() //not used

				"getMessage" -> return getText() //not used

				//general object methods
				"equals" -> return this.equals(args[0])

				"hashCode" -> return this.hashCode()

				"toString" -> return this.toString()
			}
			return null
		}

		private fun create(parent: ViewGroup, layoutInflater: LayoutInflater, res: Resources): View {
			@SuppressLint("DiscouragedApi")
			val view = layoutInflater.inflate(res.getIdentifier("global_actions_grid_item_lite", "layout", mContext.packageName), parent, false)

			@SuppressLint("DiscouragedApi")
			val iconView: ImageView = view.findViewById(res.getIdentifier("icon", "id", "android"))

			iconView.setImageDrawable(getIcon())
			iconView.scaleType = ImageView.ScaleType.CENTER_CROP

			@SuppressLint("DiscouragedApi")
			val messageView: TextView = view.findViewById(res.getIdentifier("message", "id", "android"))
			messageView.isSelected = true
			messageView.text = getText()

			view.id = View.generateViewId()
			return view
		}

		protected abstract fun onLongPress(): Boolean

		abstract fun onPress()

		abstract fun getText(): String

		abstract fun getIcon(): Drawable?
	}
}
