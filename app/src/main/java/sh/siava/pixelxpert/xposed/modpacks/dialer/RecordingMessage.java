package sh.siava.pixelxpert.xposed.modpacks.dialer;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.DialerModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@DialerModPack
public class RecordingMessage extends XposedModPack {
	private static boolean removeRecodingMessage = false;

	public RecordingMessage(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		if (Key.length > 0 && Key[0].equals("DialerRemoveRecordMessage")) {
			SystemUtils.killSelf();
		}
		removeRecodingMessage = Xprefs.getBoolean("DialerRemoveRecordMessage", false);
	}

	@SuppressLint("DiscouragedApi")
	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		int call_recording_starting_voice = mContext.getResources().getIdentifier("call_recording_starting_voice", "string", mContext.getPackageName());
		int call_recording_ending_voice = mContext.getResources().getIdentifier("call_recording_ending_voice", "string", mContext.getPackageName());

		ReflectedClass.of(Resources.class)
				.before("getString")
				.run(param -> {
					if (removeRecodingMessage
							&& (param.args[0].equals(call_recording_starting_voice) || param.args[0].equals(call_recording_ending_voice))) {
						param.setResult("");
					}
				});
	}
}