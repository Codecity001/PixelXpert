package sh.siava.pixelxpert.xposed.utils

import com.topjohnwu.superuser.Shell
import sh.siava.pixelxpert.xposed.XPLauncher

object ModuleFolderOperations {

    @JvmStatic
    fun applyVolumeSteps(volumeSteps: Int, rootPath: String, runFromApp: Boolean) {
        if (volumeSteps <= 10) {
            runRootCommand("rm -Rf $rootPath/system.prop", runFromApp)
            return
        }
        runRootCommand("echo ro.config.media_vol_steps=$volumeSteps > $rootPath/system.prop", runFromApp)
    }

    private fun runRootCommand(command: String, runFromApp: Boolean) {
        try {
            if (runFromApp) {
                Shell.cmd(command).exec()
            } else {
                XPLauncher.enqueueProxyCommand { proxy -> proxy.runRootCommand(command) }
            }
        } catch (ignored: Throwable) {}
    }
}
