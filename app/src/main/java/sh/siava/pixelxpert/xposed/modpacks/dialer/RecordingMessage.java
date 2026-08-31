package sh.siava.pixelxpert.xposed.modpacks.dialer;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.speech.tts.TextToSpeech;

import java.nio.ByteBuffer;
import java.util.Arrays;
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
	private static final Set<Integer> ignoredResourceIds = ConcurrentHashMap.newKeySet();

	private static final String[] KNOWN_RESOURCE_NAMES = {
			"call_recording_starting_voice",
			"call_recording_ending_voice"
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

		// -----------------------------------------------------------------------------------------
		// STEP 1: Standard Call Recording Text & TTS Suppression
		// -----------------------------------------------------------------------------------------
		ReflectedClass.of(Resources.class).before("getText").run(param -> {
			if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
				Resources res = param.thisObject instanceof Resources ? (Resources) param.thisObject : mContext.getResources();
				if (isTargetRecordingResource(res, (Integer) param.args[0])) param.setResult("");
			}
		});

		ReflectedClass.of(Resources.class).before("getString").run(param -> {
			if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
				Resources res = param.thisObject instanceof Resources ? (Resources) param.thisObject : mContext.getResources();
				if (isTargetRecordingResource(res, (Integer) param.args[0])) param.setResult("");
			}
		});

		ReflectedClass ttsClass = ReflectedClass.ofIfPossible("android.speech.tts.TextToSpeech");
		if (ttsClass.getClazz() != null) {
			ttsClass.before("speak").run(param -> {
				if (removeRecodingMessage && param.args != null && param.args.length > 0 && param.args[0] != null) {
					if (isRecordingOrNotesText(param.args[0].toString())) param.setResult(TextToSpeech.SUCCESS);
				}
			});
		}

		// -----------------------------------------------------------------------------------------
		// STEP 2: Uplink Audio Disclaimer Player AudioTrack Suppression (Call Notes & Modern Pixel)
		// -----------------------------------------------------------------------------------------
		ReflectedClass audioTrackClass = ReflectedClass.ofIfPossible("android.media.AudioTrack");
		if (audioTrackClass.getClazz() != null) {
			audioTrackClass.before("write").run(param -> {
				if (!removeRecodingMessage || param.args == null || param.args.length == 0) return;
				if (isUplinkDisclosureAudio()) {
					Object buf = param.args[0];
					if (buf instanceof byte[]) Arrays.fill((byte[]) buf, (byte) 0);
					else if (buf instanceof short[]) Arrays.fill((short[]) buf, (short) 0);
					else if (buf instanceof float[]) Arrays.fill((float[]) buf, 0.0f);
					else if (buf instanceof ByteBuffer) {
						ByteBuffer bb = (ByteBuffer) buf;
						for (int i = bb.position(); i < bb.limit(); i++) bb.put(i, (byte) 0);
					}
				}
			});
		}

		ReflectedClass mediaPlayerClass = ReflectedClass.ofIfPossible("android.media.MediaPlayer");
		if (mediaPlayerClass.getClazz() != null) {
			mediaPlayerClass.before("start").run(param -> {
				if (removeRecodingMessage && param.thisObject != null && isUplinkDisclosureAudio()) {
					try {
						((android.media.MediaPlayer) param.thisObject).setVolume(0.0f, 0.0f);
					} catch (Throwable ignored) {}
				}
			});
		}
	}

	private static boolean isUplinkDisclosureAudio() {
		StackTraceElement[] stack = Thread.currentThread().getStackTrace();
		for (StackTraceElement elem : stack) {
			String cls = elem.getClassName().toLowerCase(Locale.ROOT);

			// Explicit whitelist: Never touch Call Screen, Take a Message, Voicemail, Assistant, or RTT
			if (cls.contains("screening") || cls.contains("callscreen") || cls.contains("assistant")
					|| cls.contains("voicemail") || cls.contains("vvm") || cls.contains("takeamessage")
					|| cls.contains("greeting") || cls.contains("xatu") || cls.contains("ivr")
					|| cls.contains("rtt")) {
				return false;
			}

			// Targeted matching: Uplink audio players, disclaimer synclets, and recording pipelines
			if (cls.contains("uplinkaudioplayback")
					|| cls.contains("audiotrackuplinkaudioplayer")
					|| cls.contains("voicecalluplink")
					|| cls.contains("disclaimer")
					|| cls.contains("nautilus")
					|| cls.contains("fermat")
					|| cls.contains("beesly")
					|| cls.contains("audioprism")) {
				return true;
			}
		}
		return false;
	}

	private static boolean isTargetRecordingResource(Resources res, int id) {
		if (id <= 0) return false;
		if (matchedResourceIds.contains(id)) return true;
		if (ignoredResourceIds.contains(id)) return false;

		try {
			String entryName = res.getResourceEntryName(id);
			if (entryName != null) {
				String lower = entryName.toLowerCase(Locale.ROOT);
				for (String known : KNOWN_RESOURCE_NAMES) {
					if (lower.equals(known)) {
						matchedResourceIds.add(id);
						return true;
					}
				}

				if (lower.contains("_title") || lower.contains("_label") || lower.contains("_text") || lower.contains("_name")
						|| lower.contains("_button") || lower.contains("_description") || lower.contains("_toast")
						|| lower.contains("_dialog") || lower.contains("_summary") || lower.contains("_header")
						|| lower.contains("_option") || lower.contains("_item") || lower.contains("_chip")
						|| lower.contains("_card") || lower.contains("_icon")) {
					ignoredResourceIds.add(id);
					return false;
				}

				boolean hasCategory = lower.contains("recording") || lower.contains("notes") || lower.contains("transcript") || lower.contains("transcription");
				boolean hasType = lower.contains("voice") || lower.contains("disclaimer") || lower.contains("disclosure") || lower.contains("prompt") || lower.contains("audio_disclosure");

				if (hasCategory && hasType) {
					matchedResourceIds.add(id);
					return true;
				}
			}
		} catch (Resources.NotFoundException ignored) {}

		ignoredResourceIds.add(id);
		return false;
	}

	private static boolean isRecordingOrNotesText(String text) {
		if (text == null) return true;
		String lower = text.toLowerCase(Locale.ROOT).trim();
		return lower.isEmpty() || lower.contains("recording") || lower.contains("recorded")
				|| lower.contains("call notes") || lower.contains("transcript") || lower.contains("transcrib")
				|| lower.contains("this call is being") || lower.contains("this call is now");
	}
}