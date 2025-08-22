package sh.siava.pixelxpert.xposed.modpacks;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.graphics.Color;
import android.os.RemoteException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XPLauncher;
import sh.siava.pixelxpert.xposed.XPrefs;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.ModuleFolderOperations;
import sh.siava.pixelxpert.xposed.utils.StringFormatter;

@SystemUIModPack
public class MiscSettings extends XposedModPack {

	public MiscSettings(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return; //it won't be null. but anyway...

		//netstat settings
		boolean netstatColorful = Xprefs.getBoolean("networkStatsColorful", false);

		int NetStatsStartMonthStart = Xprefs.getSliderInt( "NetworkStatsMonthStart", 1);

		StringFormatter.RXColor = (netstatColorful) ? Xprefs.getInt("networkStatDLColor", Color.GREEN) : null;
		StringFormatter.TXColor = (netstatColorful) ? Xprefs.getInt("networkStatULColor", Color.RED) : null;
		StringFormatter.NetStatStartBase = Integer.parseInt(Xprefs.getString("NetworkStatsStartBase", "0"));
		StringFormatter.NetStatsStartTime = LocalTime.parse(Xprefs.getString("NetworkStatsStartTime", "0:0"), DateTimeFormatter.ofPattern("H:m"));

		StringFormatter.NetStatsDayOf = StringFormatter.NetStatStartBase == StringFormatter.NET_STAT_TYPE_MONTH
		? NetStatsStartMonthStart
		: Integer.parseInt(Xprefs.getString("NetworkStatsWeekStart", "1"));

		StringFormatter.refreshAll();

		if (Key.length > 0) {
			//we're not at startup
			//we know what has changed
			switch (Key[0]) {
				case "sysui_tuner":
					updateSysUITuner();
					break;
				case "volumeStps":
					setVolumeSteps();
					break;
			}
		} else {
			//startup jobs
			setDisplayOverride();

			updateSysUITuner();

			setVolumeSteps();
		}
	}

	private void setDisplayOverride() {
		if(!Xprefs.getBoolean("displayOverrideEnabled", false)) return;

		float displayOverride = Xprefs.getSliderFloat( "displayOverride", 100f) / 100f;
		XPLauncher.enqueueProxyCommand(proxy -> {
			try {
				String sizeResult = proxy.runRootCommand("wm size")[0];

				String[] physicalSizes = sizeResult.replace("Physical size: ", "").split("x");
				int w = Integer.parseInt(physicalSizes[0]);
				int h = Integer.parseInt(physicalSizes[1]);

				int overrideW = Math.round(w * displayOverride);
				int overrideH = Math.round(h * displayOverride);

				proxy.runRootCommand(String.format("wm size %sx%s", overrideW, overrideH));
			} catch (RemoteException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private void setVolumeSteps() {
		int volumeStps = Xprefs.getSliderInt("volumeStps", 0);

		ModuleFolderOperations.applyVolumeSteps(volumeStps, XPrefs.MagiskRoot, false);
	}

	private void updateSysUITuner() {
		XPLauncher.enqueueProxyCommand(proxy -> {
			try {
				boolean SysUITunerEnabled = Xprefs.getBoolean("sysui_tuner", false);
				String mode = (SysUITunerEnabled) ? "enable" : "disable";

				proxy.runRootCommand("pm " + mode + " com.android.systemui/.tuner.TunerActivity");
			} catch (Exception ignored) {}
		});
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
	}
}
