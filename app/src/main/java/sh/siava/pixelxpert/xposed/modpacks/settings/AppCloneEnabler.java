package sh.siava.pixelxpert.xposed.modpacks.settings;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.Menu;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.ResourceManager;
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings({"RedundantThrows"})
@SettingsModPack
public class AppCloneEnabler extends XposedModPack {
	private static final int AVAILABLE = 0;
	private static final int LIST_TYPE_CLONED_APPS = 17;
	private ReflectedClass UtilsClass;

	public AppCloneEnabler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
	}

	@SuppressLint("ResourceType")
	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {

		ReflectedClass ClonedAppsPreferenceControllerClass = ReflectedClass.of("com.android.settings.applications.ClonedAppsPreferenceController");
		ReflectedClass AppStateClonedAppsBridgeClass = ReflectedClass.of("com.android.settings.applications.AppStateClonedAppsBridge");
		ReflectedClass ManageApplicationsClass = ReflectedClass.of("com.android.settings.applications.manageapplications.ManageApplications");
		UtilsClass = ReflectedClass.of("com.android.settings.Utils");

		ManageApplicationsClass
				.after("updateOptionsMenu")
				.run(param -> {
					if (getObjectField(param.thisObject, "mListType").equals(LIST_TYPE_CLONED_APPS) && getCloneUserID() > 0) {
						Menu mOptionsMenu = (Menu) getObjectField(param.thisObject, "mOptionsMenu");
						if (mOptionsMenu != null) {
							mOptionsMenu.findItem(idOf("delete_all_app_clones")).setVisible(true);
						}
					}
				});

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
				.run(param -> {
					ArrayList<String> packageList = new ArrayList<>();
					PackageManager packageManager = mContext.getPackageManager();

					int cloneUserID = getCloneUserID();

					List<String> clonePackageNames = new ArrayList<>();
					if (cloneUserID > 0) {
						//noinspection unchecked
						List<PackageInfo> cloneUserPackages = (List<PackageInfo>) callMethod(packageManager, "getInstalledPackagesAsUser", PackageManager.GET_ACTIVITIES, cloneUserID);

						cloneUserPackages.forEach(clonePackage -> {
							if (clonePackage.packageName != null)
								clonePackageNames.add(clonePackage.packageName);
						});
					}

					for (PackageInfo installedPackage : packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
						if (installedPackage.packageName != null && !installedPackage.packageName.isEmpty()) {
							ApplicationInfo applicationInfo = packageManager.getApplicationInfo(installedPackage.packageName, PackageManager.GET_META_DATA);
							//Clone user profile is present and many system apps are auto-cloned. We don't need to display them.
							// For some reason, some system apps are not auto-cloned. We don't remove them from the list
							if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && clonePackageNames.contains(installedPackage.packageName)) {
								continue;
							}

							packageList.add(installedPackage.packageName);
						}
					}

					setObjectField(param.thisObject, "mAllowedApps", packageList);
				});

		//the way to manually clone the app
/*		ReflectedClass CloneBackendClass = ReflectedClass.of("com.android.settings.applications.manageapplications.CloneBackend");

		Object cb = callStaticMethod(CloneBackendClass, "getInstance", mContext);
		callMethod(cb, "installCloneApp", "com.whatsapp");*/

		//Adding the menu to settings app
		ClonedAppsPreferenceControllerClass
				.before("getAvailabilityStatus")
				.run(param -> param.setResult(AVAILABLE));

		ClonedAppsPreferenceControllerClass
				.after("updateSummary")
				.run(param -> {
					callMethod(
							getObjectField(param.thisObject, "mPreference"),
							"setSummary",
							ResourceManager.modRes.getText(R.string.settings_cloned_apps_active));

					param.setResult(null);
				});
	}

	private int getCloneUserID() {
		return (int) UtilsClass.callStaticMethod("getCloneUserId", mContext);
	}
}