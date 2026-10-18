package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.tryParseInt
import java.util.regex.Pattern

@SystemUIModPack
class NotificationExpander(context: Context) : XposedModPack(context) {

    private var expandBtn: Button? = null
    private var collapseBtn: Button? = null
    private var footerView: FrameLayout? = null
    private var btnLayout: LinearLayout? = null
    private var scroller: View? = null
    private var notifCollection: Any? = null
    private var dismissContainer: LinearLayout? = null

    companion object {
        private const val DEFAULT = 0
        private const val EXPAND_ALWAYS = 1
        private const val COLLAPSE_ALWAYS = 2

        @JvmField
        var notificationExpandallHookEnabled: Boolean = true

        @JvmField
        var notificationExpandallEnabled: Boolean = false

        private var notificationDefaultExpansion: Int = DEFAULT
    }

    override fun onPreferenceUpdated(vararg key: String) {
        notificationExpandallEnabled = Xprefs.getBoolean("notificationExpandallEnabled", false)
        notificationExpandallHookEnabled = Xprefs.getBoolean("notificationExpandallHookEnabled", true)
        notificationDefaultExpansion = tryParseInt(Xprefs.getString("notificationDefaultExpansion", "0"), 0)

        if (key.isNotEmpty() && key[0] == "notificationExpandallEnabled") {
            updateFooterBtn()
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val notificationStackScrollLayoutClass = ReflectedClass.of("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
        val footerViewButtonClass = ReflectedClass.of("com.android.systemui.statusbar.notification.row.FooterViewButton")
        val notifCollectionClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.collection.NotifCollection")
        val footerViewClass = ReflectedClass.of("com.android.systemui.statusbar.notification.footer.ui.view.FooterView")

        // Default notification state
        notificationStackScrollLayoutClass
            .before("setIsExpanded")
            .run { runParam ->
                val isExpanded = runParam.args.getOrNull(0) as? Boolean ?: return@run
                if (isExpanded && notificationDefaultExpansion != DEFAULT) {
                    expandAll(notificationDefaultExpansion == EXPAND_ALWAYS)
                }
            }

        // Notification Footer
        footerViewClass
            .after(Pattern.compile("updateColors.*"))
            .run { updateFooterBtn() }

        footerViewClass
            .after("onFinishInflate")
            .run { runParam ->
                val footer = runParam.thisObject as? FrameLayout ?: return@run
                footerView = footer

                footer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        updateFooterBtn()
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })

                val layout = LinearLayout(mContext).apply {
                    clipChildren = false
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    }
                }
                btnLayout = layout

                reAddView(layout, footer.findViewById(idOf("history_button")))

                val constructor = footerViewButtonClass.clazz?.getConstructor(Context::class.java)
                val expBtn = constructor?.newInstance(mContext) as? Button
                val expandArrows = ResourcesCompat.getDrawable(XPLauncher.moduleResources, R.drawable.ic_expand, mContext.theme)
                expBtn?.foreground = expandArrows
                expandBtn = expBtn

                if (expBtn != null) {
                    layout.addView(expBtn)
                }

                val disContainer = LinearLayout(mContext)
                dismissContainer = disContainer
                reAddView(disContainer, footer.findViewById(idOf("dismiss_text")))
                layout.addView(disContainer)

                expBtn?.setOnClickListener { expandAll(true) }

                val colBtn = constructor?.newInstance(mContext) as? Button
                val collapseArrows = ResourcesCompat.getDrawable(XPLauncher.moduleResources, R.drawable.ic_collapse, mContext.theme)
                colBtn?.foreground = collapseArrows
                collapseBtn = colBtn

                if (colBtn != null) {
                    layout.addView(colBtn)
                }

                reAddView(layout, footer.findViewById(idOf("settings_button")))

                colBtn?.setOnClickListener { expandAll(false) }

                updateFooterBtn()
                footer.addView(layout)
            }

        // Theme changed
        footerViewClass
            .after("updateColors")
            .run { updateFooterBtn() }

        // Grab notification container manager
        notifCollectionClass?.afterConstruction()?.run { runParam ->
            notifCollection = runParam.thisObject
        }

        // Grab notification scroll page
        notificationStackScrollLayoutClass
            .afterConstruction()
            .run { runParam ->
                scroller = runParam.thisObject as? View
            }
    }

    private fun updateFooterBtn() {
        val footer = footerView ?: return

        footer.post {
            try {
                val layout = btnLayout ?: return@post
                val clearAllButton = layout.findViewById<Button>(idOf("dismiss_text")) ?: return@post

                clearAllButton.layoutParams.width = -1

                dismissContainer?.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT).apply {
                    weight = 1f
                }

                val backgroundDrawable = clearAllButton.background?.constantState?.newDrawable()
                expandBtn?.background = backgroundDrawable
                collapseBtn?.background = clearAllButton.background?.constantState?.newDrawable()

                val textColor = clearAllButton.currentTextColor
                expandBtn?.foreground?.setTint(textColor)
                collapseBtn?.foreground?.setTint(textColor)

                expandBtn?.let { setLayoutParams(it, true) }
                collapseBtn?.let { setLayoutParams(it, false) }

                val visibility = if (notificationExpandallEnabled) VISIBLE else GONE
                expandBtn?.visibility = visibility
                collapseBtn?.visibility = visibility
            } catch (_: Throwable) {}
        }
    }

    private fun setLayoutParams(button: Button, marginToLeft: Boolean) {
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, mContext.resources.displayMetrics).toInt()

        val btnParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        }
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, mContext.resources.displayMetrics).toInt()

        if (marginToLeft) {
            btnParams.marginStart = margin
        } else {
            btnParams.marginEnd = margin
        }

        button.post { button.layoutParams = btnParams }
    }

    fun expandAll(expand: Boolean) {
        val collection = notifCollection ?: return

        val entries = getObjectField<Collection<*>>(collection, "mReadOnlyNotificationSet") ?: return
        for (entry in entries) {
            if (entry != null) {
                val row = getObjectField<Any>(entry, "row")
                if (row != null) {
                    setRowExpansion(row, expand)
                }
            }
        }

        if (!expand) {
            scroller?.requestLayout()
        }
    }

    private fun setRowExpansion(row: Any, expand: Boolean) {
        callMethod<Any>(row, "setUserExpanded", expand, true)
    }
}
