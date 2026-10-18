package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList

@SystemUIModPack
class PowerMenu(context: Context) : XposedModPack(context) {

    private var longPressActionInterface: ReflectedClass? = null

    companion object {
        private var advancedPowerMenu = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        advancedPowerMenu = Xprefs.getBoolean("advancedPowerMenu", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val globalActionsDialogLiteClass = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite")
        val powerOptionsAction = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite\$PowerOptionsAction")
        longPressActionInterface = ReflectedClass.of("com.android.systemui.globalactions.GlobalActionsDialogLite\$LongPressAction")

        powerOptionsAction
            .afterConstruction()
            .run { runParam ->
                if (!advancedPowerMenu) return@run

                setObjectField(runParam.thisObject, "mMessageResId", 0)
                setObjectField(runParam.thisObject, "mMessage", getString(R.string.advanced_option_button_title))
            }

        globalActionsDialogLiteClass
            .after("createActionItems")
            .run { runParam ->
                if (!advancedPowerMenu) return@run

                val items = getObjectField<ArrayList<Any>>(runParam.thisObject, "mItems")
                val constructor = powerOptionsAction.clazz?.constructors?.firstOrNull()
                if (constructor != null && items != null) {
                    items.add(constructor.newInstance(runParam.thisObject))
                }

                val powerItems = getObjectField<ArrayList<Any>>(runParam.thisObject, "mPowerItems")
                if (powerItems != null) {
                    powerItems.add(getAction(BootloaderAction()))
                    powerItems.add(getAction(SoftRebootAction()))
                    powerItems.add(getAction(SystemUIRebootAction()))
                }
            }
    }

    private fun getString(id: Int): String {
        return XPLauncher.moduleResources.getString(id)
    }

    private fun getAction(action: PXCustomAction): Any {
        val ifaceClazz = longPressActionInterface?.clazz ?: throw IllegalStateException("LongPressAction interface not found")
        return Proxy.newProxyInstance(
            ifaceClazz.classLoader,
            arrayOf(ifaceClazz),
            action
        )
    }

    inner class BootloaderAction : PXCustomAction() {
        override fun onLongPress(): Boolean = false

        override fun onPress() {
            SystemUtils.restart("bootloader")
        }

        override fun getText(): String {
            return getString(R.string.reboot_bootloader_title)
        }

        @SuppressLint("DiscouragedApi")
        override fun getIcon(): Drawable? {
            val res = mContext.resources
            val id = res.getIdentifier("ic_restart", "drawable", "android")
            return ResourcesCompat.getDrawable(res, id, mContext.theme)
        }
    }

    inner class SoftRebootAction : PXCustomAction() {
        override fun onLongPress(): Boolean = false

        override fun onPress() {
            SystemUtils.restart("android")
        }

        override fun getText(): String {
            return getString(R.string.soft_reboot_title)
        }

        @SuppressLint("DiscouragedApi")
        override fun getIcon(): Drawable? {
            val res = mContext.resources
            val id = res.getIdentifier("ic_restart", "drawable", "android")
            return ResourcesCompat.getDrawable(res, id, mContext.theme)
        }
    }

    inner class SystemUIRebootAction : PXCustomAction() {
        override fun onLongPress(): Boolean = false

        override fun onPress() {
            SystemUtils.restart("systemUI")
        }

        override fun getText(): String {
            return getString(R.string.restart_systemui_title)
        }

        @SuppressLint("DiscouragedApi")
        override fun getIcon(): Drawable? {
            val res = mContext.resources
            val id = res.getIdentifier("ic_restart", "drawable", "android")
            return ResourcesCompat.getDrawable(res, id, mContext.theme)
        }
    }

    abstract inner class PXCustomAction : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val nonNullArgs = args ?: emptyArray()
            return when (method.name) {
                "create" -> {
                    val parent = nonNullArgs.getOrNull(2) as? ViewGroup ?: return null
                    val layoutInflater = nonNullArgs.getOrNull(3) as? LayoutInflater ?: return null
                    createView(parent, layoutInflater)
                }
                "onPress" -> {
                    onPress()
                    null
                }
                "onLongPress" -> onLongPress()
                "shouldShow" -> true
                "getLabelForAccessibility" -> ""
                "showDuringKeyguard", "showBeforeProvisioning", "shouldBeSeparated" -> false
                "isEnabled" -> true
                "getMessageResId" -> 0
                "getIcon" -> getIcon()
                "getMessage" -> getText()
                "equals" -> this == nonNullArgs.firstOrNull()
                "hashCode" -> this.hashCode()
                "toString" -> this.toString()
                else -> null
            }
        }

        private fun createView(parent: ViewGroup, layoutInflater: LayoutInflater): View {
            val res = mContext.resources
            @SuppressLint("DiscouragedApi")
            val layoutId = res.getIdentifier("global_actions_grid_item_lite", "layout", mContext.packageName)
            val view = layoutInflater.inflate(layoutId, parent, false)

            @SuppressLint("DiscouragedApi")
            val iconId = res.getIdentifier("icon", "id", "android")
            val iconView = view.findViewById<ImageView>(iconId)
            iconView?.setImageDrawable(getIcon())
            iconView?.scaleType = ImageView.ScaleType.CENTER_CROP

            @SuppressLint("DiscouragedApi")
            val messageId = res.getIdentifier("message", "id", "android")
            val messageView = view.findViewById<TextView>(messageId)
            messageView?.isSelected = true
            messageView?.text = getText()

            view.id = View.generateViewId()
            return view
        }

        protected abstract fun onLongPress(): Boolean
        abstract fun onPress()
        abstract fun getText(): String
        abstract fun getIcon(): Drawable?
    }
}
