package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.os.Build
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack

/**
 * Compiles the module's target packages ahead of time, once per build. On load
 * it reads `R.array.module_scope` - the same list the Hooks page monitors -
 * and asks the root proxy to run `cmd package compile -m speed -f` for each
 * entry, so the packages the module touches most are already speed-profiled
 * instead of waiting for the profile to be collected.
 *
 * Two preference keys make the sweep idempotent: `optimized_build` records the
 * [Build.ID] the sweep ran for, and `system_restart_pending` marks that a sweep
 * happened and is still being applied - the flag is cleared on the next load,
 * then rewritten when a new build is seen. Everything after the flag reset
 * runs inside one `Throwable` swallow so a missing resource array or a dead
 * root proxy leaves the preferences consistent rather than crashing
 * system_server.
 */
@FrameworkModPack
class TargetOptimizer(context: Context) : XposedModPack(context) {

	companion object {
		const val OPTIMIZED_BUILD_KEY = "optimized_build"
		const val SYSTEM_RESTART_PENDING_KEY = "system_restart_pending"
	}

	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		if (Xprefs.getBoolean(SYSTEM_RESTART_PENDING_KEY, false)) {
			Xprefs.edit()
				.putBoolean(SYSTEM_RESTART_PENDING_KEY, false)
				.apply()
		}

		val optimizedBuild = Xprefs.getString(OPTIMIZED_BUILD_KEY, "")

		if (!Build.ID.equals(optimizedBuild)) {
			try {
				val targetPacks = XPLauncher.moduleResources!!.getStringArray(R.array.module_scope)

				targetPacks.forEach { target ->
					XPLauncher.enqueueProxyCommand { proxy ->
						proxy.runRootCommand(String.format("cmd package compile -m speed -f %s", target))
					}
				}

				Xprefs.edit()
					.putBoolean(SYSTEM_RESTART_PENDING_KEY, true)
					.putString(OPTIMIZED_BUILD_KEY, Build.ID)
					.apply()
			} catch (ignored: Throwable) {
			}
		}
	}
}
