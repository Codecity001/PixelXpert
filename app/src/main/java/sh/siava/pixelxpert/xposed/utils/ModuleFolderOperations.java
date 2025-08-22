package sh.siava.pixelxpert.xposed.utils;

import com.topjohnwu.superuser.Shell;

import sh.siava.pixelxpert.xposed.XPLauncher;

public class ModuleFolderOperations {
	public static void applyVolumeSteps(int volumeStps, String rootPath, boolean runFromApp) {
		if (volumeStps <= 10) {
			runRootCommand("rm -Rf " + rootPath + "/system.prop", runFromApp);
			return;
		}
		runRootCommand("echo ro.config.media_vol_steps=" + volumeStps + " > " + rootPath + "/system.prop", runFromApp);
	}

	private static void runRootCommand(String command, boolean runFromApp)
	{
		try
		{
			if(runFromApp)
			{
				Shell.cmd(command).exec();
			}
			else
			{
				XPLauncher.enqueueProxyCommand(proxy -> proxy.runRootCommand(command));
			}
		}
		catch (Throwable ignored){}
	}
}