package sh.siava.pixelxpert.xposed.modpacks.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.res.ResourcesCompat
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@SettingsModPack
class PXSettingsLauncher(context: Context) : XposedModPack(context) {

    companion object {
        private var pxInSettings = true
    }

    private var mNewSettings = true
    private var mExpressiveTheme = false

    override fun onPreferenceUpdated(vararg key: String) {
        pxInSettings = Xprefs.getBoolean("PXInSettings", true)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val homepagePreferenceClass = ReflectedClass.of("com.android.settings.widget.HomepagePreference")
            val topLevelSettingsClass = ReflectedClass.of("com.android.settings.homepage.TopLevelSettings")
            val onPreferenceClickListenerInterface = ReflectedClass.of("androidx.preference.Preference\$OnPreferenceClickListener")

            val preferenceCategoryClass = ReflectedClass.ofIfPossible("androidx.preference.PreferenceCategory")
            val preferenceManagerClass = ReflectedClass.ofIfPossible("androidx.preference.PreferenceManager")

            try {
                val settingsThemeHelperClass = ReflectedClass.of("com.android.settingslib.widget.SettingsThemeHelper")
                mExpressiveTheme = settingsThemeHelperClass.callStaticMethod("isExpressiveTheme", mContext) as? Boolean ?: false
            } catch (ignored: Throwable) {
            }

            topLevelSettingsClass
                .after("getPreferenceScreenResId")
                .run { hookParam ->
                    @SuppressLint("DiscouragedApi")
                    val oldResName = mContext.resources.getIdentifier("top_level_settings", "xml", mContext.packageName)
                    if (hookParam.result == oldResName) {
                        mNewSettings = false
                    }
                }

            topLevelSettingsClass
                .before("onCreateAdapter")
                .run { hookParam ->
                    if (pxInSettings) {
                        val pxPreference = homepagePreferenceClass.clazz.getConstructor(Context::class.java).newInstance(mContext)

                        callMethod(
                            pxPreference,
                            "setIcon",
                            ResourcesCompat.getDrawable(
                                XPLauncher.moduleResources,
                                if (mExpressiveTheme) R.mipmap.ic_launcher else R.drawable.ic_notification_foreground,
                                mContext.theme
                            )
                        )
                        callMethod(pxPreference, "setTitle", XPLauncher.moduleResources.getString(R.string.app_name))

                        val onClickListener = Proxy.newProxyInstance(
                            onPreferenceClickListenerInterface.clazz.classLoader,
                            arrayOf(onPreferenceClickListenerInterface.clazz),
                            PXClickListener()
                        )

                        setObjectField(pxPreference, "mOnClickListener", onClickListener)

                        val arg0 = hookParam.args.getOrNull(0) ?: return@run

                        if (mNewSettings && preferenceCategoryClass.clazz != null && preferenceManagerClass.clazz != null) {
                            callMethod(pxPreference, "setSummary", XPLauncher.moduleResources.getString(R.string.xposed_desc))
                            val pxPreferenceCategory = preferenceCategoryClass.clazz.getConstructor(Context::class.java).newInstance(mContext)

                            val prefManager = preferenceManagerClass.clazz.getConstructor(Context::class.java).newInstance(mContext)
                            setObjectField(pxPreferenceCategory, "mPreferenceManager", prefManager)
                            callMethod(pxPreferenceCategory, "setOrder", 9999)

                            @SuppressLint("DiscouragedApi")
                            val layoutID = mContext.resources.getIdentifier(
                                "settingslib_preference_category_no_title",
                                "layout",
                                mContext.packageName
                            )

                            if (layoutID != 0) {
                                callMethod(pxPreferenceCategory, "setLayoutResource", layoutID)
                            }

                            callMethod(pxPreferenceCategory, "addPreference", pxPreference)
                            callMethod(arg0, "addPreference", pxPreferenceCategory)
                        } else {
                            callMethod(pxPreference, "setOrder", 9999)
                            callMethod(arg0, "addPreference", pxPreference)
                        }
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    private inner class PXClickListener : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any {
            val intent = mContext.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
            if (intent != null) {
                mContext.startActivity(intent)
            }
            return true
        }
    }
}
