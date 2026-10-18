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
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.findMethodBestMatch
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@LauncherModPack
class ClearAllButtonMod(context: Context) : XposedModPack(context) {

    private var recentView: Any? = null
    private var clearAllIcon: ImageView? = null
    private var clearAllButton: FrameLayout? = null

    companion object {
        private var recentClearAllReposition = false

        fun getThemedColor(context: Context): ColorStateList? {
            return getSystemAttrColor(
                context,
                if (SystemUtils.isDarkMode()) android.R.attr.textColorPrimaryInverse else android.R.attr.textColorPrimary
            )
        }

        fun getSystemAttrColor(context: Context, attr: Int): ColorStateList? {
            context.obtainStyledAttributes(intArrayOf(attr)).use { a ->
                return a.getColorStateList(0)
            }
        }
    }

    override fun onPreferenceUpdated(vararg key: String) {
        if (key.isNotEmpty() && key[0] == "RecentClearAllReposition") {
            SystemUtils.killSelf()
        }

        recentClearAllReposition = Xprefs.getBoolean("RecentClearAllReposition", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val overviewActionsViewClass = ReflectedClass.of("com.android.quickstep.views.OverviewActionsView")
            val recentsViewClass = ReflectedClass.of("com.android.quickstep.views.RecentsView")
            val dismissAllTasksMethod = findMethodBestMatch(recentsViewClass.clazz, "dismissAllTasks", View::class.java)

            recentsViewClass
                .afterConstruction()
                .run { hookParam ->
                    recentView = hookParam.thisObject
                }

            recentsViewClass
                .after("setColorTint")
                .run {
                    if (!recentClearAllReposition) return@run
                    clearAllIcon?.drawable?.setTintList(getThemedColor(mContext))
                }

            recentsViewClass
                .after("setVisibility")
                .run { hookParam ->
                    val button = clearAllButton ?: return@run
                    val visibility = hookParam.args.getOrNull(0) as? Int ?: return@run
                    button.visibility = visibility
                }

            overviewActionsViewClass
                .before("onFinishInflate")
                .run { hookParam ->
                    if (!recentClearAllReposition) return@run

                    val button = FrameLayout(mContext)
                    clearAllButton = button

                    val icon = ImageView(mContext).apply {
                        val drawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources, R.drawable.ic_clear_all, mContext.theme)
                        setImageDrawable(drawable)
                        drawable?.setTintList(getThemedColor(mContext))
                    }
                    clearAllIcon = icon
                    button.addView(icon)

                    val params = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, Resources.getSystem().displayMetrics).toInt()
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    }

                    button.layoutParams = params
                    button.setOnClickListener { v ->
                        val rv = recentView
                        if (rv != null) {
                            try {
                                dismissAllTasksMethod?.invoke(rv, v)
                            } catch (ignored: Throwable) {
                            }
                        }
                    }

                    val parent = hookParam.thisObject as? FrameLayout ?: return@run
                    parent.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
                    parent.addView(button)
                    button.visibility = View.GONE
                }
        } catch (ignored: Throwable) {
        }
    }
}
