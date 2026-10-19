package sh.siava.pixelxpert.xposed.utils

import com.topjohnwu.superuser.Shell
import sh.siava.pixelxpert.xposed.XPLauncher

/**
 * Maintains the module's optional `system.prop` volume-step override: a step
 * count of ten or less removes the file entirely, anything larger writes the
 * `ro.config.media_vol_steps` line into it. Commands run either through libsu
 * (when the caller lives in the app process) or through the Xposed root
 * proxy, and failures are swallowed the same way either way.
 */
object ModuleFolderOperations {
	@JvmStatic
	fun applyVolumeSteps(volumeStps: Int, rootPath: String, runFromApp: Boolean) {
		if (volumeStps <= 10) {
			runRootCommand("rm -Rf $rootPath/system.prop", runFromApp)
			return
		}
		runRootCommand("echo ro.config.media_vol_steps=$volumeStps > $rootPath/system.prop", runFromApp)
	}

	private fun runRootCommand(command: String, runFromApp: Boolean) {
		try {
			if (runFromApp) {
				Shell.cmd(command).exec()
			} else {
				XPLauncher.enqueueProxyCommand { proxy -> proxy.runRootCommand(command) }
			}
		} catch (ignored: Throwable) {
		}
	}
}
