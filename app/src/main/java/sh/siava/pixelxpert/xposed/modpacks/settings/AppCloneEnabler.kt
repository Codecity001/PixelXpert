package sh.siava.pixelxpert.xposed.modpacks.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.Menu
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Re-enables the Settings app's app-clone (parallel apps) screens on builds
 * that hide them: the clone controller always reports itself available and
 * shows the module's "unlocked" summary, the cloned-apps management screen
 * gains its "delete all clones" menu entry whenever a clone profile exists,
 * and the bridge that decides which packages may be cloned is fed with every
 * installed package except the system apps the clone profile already clones
 * for free.
 *
 * The original's commented-out Private Space and CloneBackend experiments were
 * never active; they stay commented out here.
 */
@Suppress("RedundantThrows")
@SettingsModPack
class AppCloneEnabler(context: Context) : XposedModPack(context) {

	private companion object {
		private const val AVAILABLE = 0
		private const val LIST_TYPE_CLONED_APPS = 17
	}

	private lateinit var UtilsClass: ReflectedClass

	/** Nothing this pack reads; declared because [XposedModPack] requires it. */
	override fun onPreferenceUpdated(vararg key: String?) {
	}

	@SuppressLint("ResourceType")
	@Suppress("SENSELESS_COMPARISON")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {

		val ClonedAppsPreferenceControllerClass = ReflectedClass.of("com.android.settings.applications.ClonedAppsPreferenceController")
		val AppStateClonedAppsBridgeClass = ReflectedClass.of("com.android.settings.applications.AppStateClonedAppsBridge")
		val ManageApplicationsClass = ReflectedClass.of("com.android.settings.applications.manageapplications.ManageApplications")
		UtilsClass = ReflectedClass.of("com.android.settings.Utils")

		ManageApplicationsClass
			.after("updateOptionsMenu")
			.run { param ->
				if (getObjectField(param.thisObject, "mListType")!!.equals(LIST_TYPE_CLONED_APPS) && getCloneUserID() > 0) {
					val mOptionsMenu = getObjectField(param.thisObject, "mOptionsMenu") as Menu?
					if (mOptionsMenu != null) {
						mOptionsMenu.findItem(SystemUtils.idOf("delete_all_app_clones")).setVisible(true)
					}
				}
			}

		/* Private Space
		ReflectedClass FlagsClass = ReflectedClass.of("android.os.Flags");

		hookAllMethods(FlagsClass, "allowPrivateProfile", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				param.setResult(true);
			}
		});*/

		AppStateClonedAppsBridgeClass
			.afterConstruction()
			.run { param ->
				val packageList = ArrayList<String>()
				val packageManager = mContext.packageManager

				val cloneUserID = getCloneUserID()

				val clonePackageNames = ArrayList<String>()
				if (cloneUserID > 0) {
					//noinspection unchecked
					@Suppress("UNCHECKED_CAST")
					val cloneUserPackages = callMethod(
						packageManager,
						"getInstalledPackagesAsUser",
						PackageManager.GET_ACTIVITIES,
						cloneUserID,
					) as List<PackageInfo>

					cloneUserPackages.forEach { clonePackage ->
						if (clonePackage.packageName != null) {
							clonePackageNames.add(clonePackage.packageName)
						}
					}
				}

				for (installedPackage in packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
					if (installedPackage.packageName != null && !installedPackage.packageName.isEmpty()) {
						val applicationInfo = packageManager.getApplicationInfo(installedPackage.packageName, PackageManager.GET_META_DATA)
						//Clone user profile is present and many system apps are auto-cloned. We don't need to display them.
						// For some reason, some system apps are not auto-cloned. We don't remove them from the list
						if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && clonePackageNames.contains(installedPackage.packageName)) {
							continue
						}

						packageList.add(installedPackage.packageName)
					}
				}

				setObjectField(param.thisObject, "mAllowedApps", packageList)
			}

		//the way to manually clone the app
/*		ReflectedClass CloneBackendClass = ReflectedClass.of("com.android.settings.applications.manageapplications.CloneBackend");

		Object cb = callStaticMethod(CloneBackendClass, "getInstance", mContext);
		callMethod(cb, "installCloneApp", "com.whatsapp");*/

		ClonedAppsPreferenceControllerClass
			.before("getAvailabilityStatus")
			.run { param -> param.setResult(AVAILABLE) }

		ClonedAppsPreferenceControllerClass
			.after("updateSummary")
			.run { param ->
				callMethod(
					getObjectField(param.thisObject, "mPreference"),
					"setSummary",
					XPLauncher.moduleResources!!.getText(R.string.settings_cloned_apps_active),
				)

				param.setResult(null)
			}
	}

	private fun getCloneUserID(): Int {
		return UtilsClass.callStaticMethod("getCloneUserId", mContext) as Int
	}
}
