package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.os.Build
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack

@FrameworkModPack
class TargetOptimizer(context: Context) : XposedModPack(context) {

    companion object {
        const val OPTIMIZED_BUILD_KEY = "optimized_build"
        const val SYSTEM_RESTART_PENDING_KEY = "system_restart_pending"
    }

    override fun onPreferenceUpdated(vararg keys: String) {}

    override fun onPackageLoaded(param: PackageReadyParam) {
        if (XPrefs.getBoolean(SYSTEM_RESTART_PENDING_KEY, false)) {
            XPrefs.edit()
                .putBoolean(SYSTEM_RESTART_PENDING_KEY, false)
                .apply()
        }

        val optimizedBuild = XPrefs.getString(OPTIMIZED_BUILD_KEY, "")

        if (Build.ID != optimizedBuild) {
            runCatching {
                val targetPacks = XPLauncher.moduleResources?.getStringArray(R.array.module_scope) ?: return
                for (target in targetPacks) {
                    XPLauncher.enqueueProxyCommand { proxy ->
                        proxy.runRootCommand("cmd package compile -m speed -f $target")
                    }
                }

                XPrefs.edit()
                    .putBoolean(SYSTEM_RESTART_PENDING_KEY, true)
                    .putString(OPTIMIZED_BUILD_KEY, Build.ID)
                    .apply()
            }
        }
    }
}
