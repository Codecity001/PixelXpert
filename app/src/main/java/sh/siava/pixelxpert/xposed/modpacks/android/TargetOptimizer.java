package sh.siava.pixelxpert.xposed.modpacks.android;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.os.Build;

import java.util.Arrays;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.ResourceManager;
import sh.siava.pixelxpert.xposed.XPLauncher;
import sh.siava.pixelxpert.xposed.XposedModPack;

@FrameworkModPack
public class TargetOptimizer extends XposedModPack {
	public static final String OPTIMIZED_BUILD_KEY = "optimized_build";
	public static final String SYSTEM_RESTART_PENDING_KEY = "system_restart_pending";

	public TargetOptimizer(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		if(Xprefs.getBoolean(SYSTEM_RESTART_PENDING_KEY, false)) {
			Xprefs.edit()
					.putBoolean(SYSTEM_RESTART_PENDING_KEY, false)
					.apply();
		}

		String optimizedBuild = Xprefs.getString(OPTIMIZED_BUILD_KEY, "");

		if(!Build.ID.equals(optimizedBuild))
		{
			try {
				String[] targetPacks = ResourceManager.modRes.getStringArray(R.array.module_scope);

				Arrays.asList(targetPacks).forEach(target ->
						XPLauncher.enqueueProxyCommand(proxy ->
								proxy.runRootCommand(String.format("cmd package compile -m speed -f %s", target))));

				Xprefs.edit()
						.putBoolean(SYSTEM_RESTART_PENDING_KEY, true)
						.putString(OPTIMIZED_BUILD_KEY, Build.ID)
						.apply();
			}
			catch (Throwable ignored)
			{}
		}
	}
}
