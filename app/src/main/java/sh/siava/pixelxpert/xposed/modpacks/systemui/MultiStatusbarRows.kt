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

@SystemUIModPack
class MultiStatusbarRows(context: Context) : XposedModPack(context) {

    companion object {
        private var systemIconsMultiRow = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        if (key.isNotEmpty() && key[0] == "systemIconsMultiRow") {
            val newSystemIconsMultiRow = Xprefs.getBoolean("systemIconsMultiRow", false)
            if (newSystemIconsMultiRow != systemIconsMultiRow) {
                SystemUtils.killSelf()
            }
        }
        systemIconsMultiRow = Xprefs.getBoolean("systemIconsMultiRow", false)
        val defaultSort = FlexStatusIconContainer.SORT_CLEAN.toString()
        FlexStatusIconContainer.setSortPlan(
            Xprefs.getString("systemIconSortPlan", defaultSort)?.toIntOrNull() ?: FlexStatusIconContainer.SORT_CLEAN
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        var iconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.IconManager")
        if (iconManagerClass?.clazz == null) {
            iconManagerClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconController\$IconManager")
        }

        iconManagerClass?.beforeConstruction()?.run { runParam ->
            if (!systemIconsMultiRow) return@run

            try {
                val linearStatusbarIconContainer = runParam.args[0] as? View ?: return@run

                val grandParent = (linearStatusbarIconContainer.parent?.parent as? View) ?: return@run
                val id = mContext.resources.getResourceName(grandParent.id)
                if (!id.contains("status_bar_end_side_content")) return@run

                val flex = FlexStatusIconContainer(mContext, linearStatusbarIconContainer)
                flex.setPadding(linearStatusbarIconContainer.paddingLeft, 0, linearStatusbarIconContainer.paddingRight, 0)

                val flexParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                flex.layoutParams = flexParams

                flex.foregroundGravity = Gravity.CENTER_VERTICAL or Gravity.END

                val parent = linearStatusbarIconContainer.parent as? ViewGroup ?: return@run
                val index = parent.indexOfChild(linearStatusbarIconContainer)
                parent.addView(flex, index)
                parent.layoutParams.height = LinearLayout.LayoutParams.MATCH_PARENT
                linearStatusbarIconContainer.visibility = View.GONE
                runParam.args[0] = flex
            } catch (_: Throwable) {}
        }
    }
}
