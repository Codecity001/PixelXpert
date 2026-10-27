package sh.siava.pixelxpert.xposed.modpacks.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.res.ResourcesCompat
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Adds the module's entry to the Settings homepage: a preference that opens
 * the module app when tapped, either in its own category on the new-style
 * homepage or straight onto the top-level screen on the old one, with the
 * alternate launcher icon when A16's expressive theme is active.
 *
 * `PXInSettings` stays a static switch (the injected preference reads it from
 * the hook, and the original kept one copy for the whole process), while the
 * "new vs old homepage" and "expressive theme" discoveries are per-instance
 * exactly as before.
 */
@Suppress("RedundantThrows")
@SettingsModPack
class PXSettingsLauncher(context: Context) : XposedModPack(context) {

	private companion object {
		@JvmStatic
		private var PXInSettings = true
	}

	private var mNewSettings = true

	private var mExpressiveTheme = false

	override fun onPreferenceUpdated(vararg key: String?) {
		PXInSettings = Xprefs.getBoolean("PXInSettings", true)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val HomepagePreferenceClass = ReflectedClass.of("com.android.settings.widget.HomepagePreference")
		val TopLevelSettingsClass = ReflectedClass.of("com.android.settings.homepage.TopLevelSettings")
		val OnPreferenceClickListenerInterface = ReflectedClass.of(
			"androidx.preference.Preference\$OnPreferenceClickListener",
		)

		val PreferenceCategoryClass = ReflectedClass.ofIfPossible("androidx.preference.PreferenceCategory")
		val PreferenceManagerClass = ReflectedClass.ofIfPossible("androidx.preference.PreferenceManager")

		try { // A16 expressive theme needs a different icon of PX
			val SettingsThemeHelperClass = ReflectedClass.of("com.android.settingslib.widget.SettingsThemeHelper")
			mExpressiveTheme = SettingsThemeHelperClass.callStaticMethod("isExpressiveTheme", mContext) as Boolean
		} catch (ignored: Throwable) {
		}

		TopLevelSettingsClass
			.after("getPreferenceScreenResId")
			.run { param ->
				@SuppressLint("DiscouragedApi")
				val oldResName = mContext.resources.getIdentifier("top_level_settings", "xml", mContext.packageName)

				if (param.getResult<Any?>()!!.equals(oldResName)) {
					mNewSettings = false
				}
			}

		TopLevelSettingsClass
			.before("onCreateAdapter")
			.run { param ->
				if (PXInSettings) {
					val PXPreference = HomepagePreferenceClass.clazz!!.getConstructor(Context::class.java).newInstance(mContext)

					callMethod(
						PXPreference,
						"setIcon",
						ResourcesCompat.getDrawable(
							XPLauncher.moduleResources!!,
							if (mExpressiveTheme) {
								R.mipmap.ic_launcher
							} else {
								R.drawable.ic_notification_foreground
							},
							mContext.theme,
						),
					)
					callMethod(PXPreference, "setTitle", XPLauncher.moduleResources!!.getString(R.string.app_name))

					val onClickListener = Proxy.newProxyInstance(
						OnPreferenceClickListenerInterface.clazz!!.classLoader,
						arrayOf(OnPreferenceClickListenerInterface.clazz),
						PXClickListener(),
					)

					setObjectField(PXPreference, "mOnClickListener", onClickListener)

					if (mNewSettings) {
						callMethod(PXPreference, "setSummary", XPLauncher.moduleResources!!.getString(R.string.xposed_desc))
						val PXPreferenceCategory = PreferenceCategoryClass.clazz!!.getConstructor(
							Context::class.java,
						).newInstance(mContext)

						setObjectField(
							PXPreferenceCategory,
							"mPreferenceManager",
							PreferenceManagerClass.clazz!!.getConstructor(Context::class.java).newInstance(mContext),
						)
						callMethod(PXPreferenceCategory, "setOrder", 9999)

						@SuppressLint("DiscouragedApi")
						val layoutID = mContext.resources.getIdentifier(
							"settingslib_preference_category_no_title",
							"layout",
							mContext.packageName,
						)

						if (layoutID != 0) {
							callMethod(PXPreferenceCategory, "setLayoutResource", layoutID)
						}

						callMethod(PXPreferenceCategory, "addPreference", PXPreference)

						callMethod(param.args[0], "addPreference", PXPreferenceCategory)
					} else {
						callMethod(PXPreference, "setOrder", 9999)
						callMethod(param.args[0], "addPreference", PXPreference)
					}
				}
			}
	}

	inner class PXClickListener : InvocationHandler {
		/**
		 * @noinspection SuspiciousInvocationHandlerImplementation
		 */
		override fun invoke(proxy: Any?, method: Method?, args: Array<out Any>?): Any? {
			val intent = mContext.packageManager.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
			mContext.startActivity(intent)

			return true
		}
	}
}
