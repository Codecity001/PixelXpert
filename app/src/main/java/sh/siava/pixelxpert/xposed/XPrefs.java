package sh.siava.pixelxpert.xposed;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;

import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.xposed.utils.ExtendedRemotePreferences;


public class XPrefs {
	@SuppressLint("StaticFieldLeak")
	public static ExtendedRemotePreferences Xprefs;
	public static final String MagiskRoot = "/data/adb/modules/PixelXpert";
	public static String packageName;

	private static final OnSharedPreferenceChangeListener listener = (sharedPreferences, key) -> loadEverything(packageName, key);
	public static void init(Context context) {
		packageName = context.getPackageName();

		Xprefs = new ExtendedRemotePreferences(context, BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID + "_preferences", true);
	}

	public static void onContentProviderLoaded() {
		loadEverything(packageName);
		Xprefs.registerOnSharedPreferenceChangeListener(listener);
	}

	public static void loadEverything(String packageName, String... key) {
		if (key.length > 0 && (key[0] == null || Constants.PREF_UPDATE_EXCLUSIONS.stream().anyMatch(exclusion -> key[0].startsWith(exclusion))))
			return;

		setPackagePrefs(packageName);

		XPLauncher.runningMods.forEach(thisMod -> thisMod.onPreferenceUpdated(key));
	}

	/** @noinspection unused*/
	public static void setPackagePrefs(String packageName) {
	}
}