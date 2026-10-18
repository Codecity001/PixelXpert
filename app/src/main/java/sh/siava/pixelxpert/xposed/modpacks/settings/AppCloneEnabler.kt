package sh.siava.pixelxpert.xposed.modpacks.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.Menu
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@SettingsModPack
class AppCloneEnabler(context: Context) : XposedModPack(context) {

    companion object {
        private const val AVAILABLE = 0
        private const val LIST_TYPE_CLONED_APPS = 17
    }

    private var utilsClass: ReflectedClass? = null

    override fun onPreferenceUpdated(vararg key: String) {}

    @SuppressLint("ResourceType")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clonedAppsPreferenceControllerClass = ReflectedClass.of("com.android.settings.applications.ClonedAppsPreferenceController")
            val appStateClonedAppsBridgeClass = ReflectedClass.of("com.android.settings.applications.AppStateClonedAppsBridge")
            val manageApplicationsClass = ReflectedClass.of("com.android.settings.applications.manageapplications.ManageApplications")
            utilsClass = ReflectedClass.of("com.android.settings.Utils")

            manageApplicationsClass
                .after("updateOptionsMenu")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    if (getObjectField(thisObj, "mListType") == LIST_TYPE_CLONED_APPS && getCloneUserID() > 0) {
                        val optionsMenu = getObjectField(thisObj, "mOptionsMenu") as? Menu
                        optionsMenu?.findItem(idOf("delete_all_app_clones"))?.isVisible = true
                    }
                }

            appStateClonedAppsBridgeClass
                .afterConstruction()
                .run { hookParam ->
                    val packageList = ArrayList<String>()
                    val packageManager = mContext.packageManager

                    val cloneUserID = getCloneUserID()

                    val clonePackageNames = ArrayList<String>()
                    if (cloneUserID > 0) {
                        @Suppress("UNCHECKED_CAST")
                        val cloneUserPackages = callMethod(
                            packageManager,
                            "getInstalledPackagesAsUser",
                            PackageManager.GET_ACTIVITIES,
                            cloneUserID
                        ) as? List<PackageInfo>

                        cloneUserPackages?.forEach { clonePackage ->
                            clonePackage.packageName?.let { clonePackageNames.add(it) }
                        }
                    }

                    for (installedPackage in packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
                        val pkgName = installedPackage.packageName
                        if (!pkgName.isNullOrEmpty()) {
                            val applicationInfo = packageManager.getApplicationInfo(pkgName, PackageManager.GET_META_DATA)
                            if ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && clonePackageNames.contains(pkgName)) {
                                continue
                            }
                            packageList.add(pkgName)
                        }
                    }

                    hookParam.thisObject?.let { setObjectField(it, "mAllowedApps", packageList) }
                }

            // Adding the menu to settings app
            clonedAppsPreferenceControllerClass
                .before("getAvailabilityStatus")
                .run { hookParam -> hookParam.result = AVAILABLE }

            clonedAppsPreferenceControllerClass
                .after("updateSummary")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    val pref = getObjectField(thisObj, "mPreference")
                    if (pref != null) {
                        callMethod(pref, "setSummary", XPLauncher.moduleResources.getText(R.string.settings_cloned_apps_active))
                    }
                    hookParam.result = null
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun getCloneUserID(): Int {
        return utilsClass?.callStaticMethod("getCloneUserId", mContext) as? Int ?: -1
    }
}
