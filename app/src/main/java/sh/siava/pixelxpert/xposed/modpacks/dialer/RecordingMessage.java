package sh.siava.pixelxpert.xposed.modpacks.dialer;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.speech.tts.TextToSpeech;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.DialerModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@DialerModPack
public class RecordingMessage extends XposedModPack {
	private static boolean removeRecodingMessage = false;

	private static final Set<Integer> matchedResourceIds = ConcurrentHashMap.newKeySet();

	private static final String[] KNOWN_RESOURCE_NAMES = {
			"call_recording_starting_voice", "call_recording_ending_voice",
			"call_recording_speaker_starting_voice", "call_recording_speaker_ending_voice",
			"call_notes_starting_voice", "call_notes_ending_voice"
	};

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
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		if (PRParam != null && PRParam.getClassLoader() != null) {
			ReflectedClass.setDefaultClassloader(PRParam.getClassLoader());
		}

		Resources res = mContext.getResources();
		String targetPkg = (PRParam != null && PRParam.getPackageName() != null) ? PRParam.getPackageName() : "com.google.android.dialer";

		for (String resName : KNOWN_RESOURCE_NAMES) {
			int strId = res.getIdentifier(resName, "string", targetPkg);
			if (strId > 0) matchedResourceIds.add(strId);
			int rawId = res.getIdentifier(resName, "raw", targetPkg);
			if (rawId > 0) matchedResourceIds.add(rawId);
		}

		// Resource hooks to suppress voice strings & raw audio files
		ReflectedClass.of(Resources.class).before("getText").run(param -> {
			if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
				if (matchedResourceIds.contains((Integer) param.args[0])) param.setResult("");
			}
		});

		ReflectedClass.of(Resources.class).before("getString").run(param -> {
			if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
				if (matchedResourceIds.contains((Integer) param.args[0])) param.setResult("");
			}
		});

		ReflectedClass.of(Resources.class).before("openRawResource").run(param -> {
			if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
				if (matchedResourceIds.contains((Integer) param.args[0])) param.setResult(new ByteArrayInputStream(new byte[0]));
			}
		});

		ReflectedClass.of(Resources.class).before("openRawResourceFd").run(param -> {
			if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
				if (matchedResourceIds.contains((Integer) param.args[0])) param.setResult(null);
			}
		});

		// Text-To-Speech muting
		ReflectedClass ttsClass = ReflectedClass.ofIfPossible("android.speech.tts.TextToSpeech");
		if (ttsClass.getClazz() != null) {
			ttsClass.before("speak").run(param -> {
				if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] != null) {
					if (isRecordingOrNotesText(param.args[0].toString())) param.setResult(TextToSpeech.SUCCESS);
				}
			});
		}
	}

	private static boolean isRecordingOrNotesText(String text) {
		if (text == null) return true;
		String lower = text.toLowerCase(Locale.ROOT).trim();
		return lower.isEmpty() || lower.contains("recording") || lower.contains("recorded")
				|| lower.contains("call notes") || lower.contains("transcript") || lower.contains("transcrib")
				|| lower.contains("this call is being") || lower.contains("this call is now");
	}
}