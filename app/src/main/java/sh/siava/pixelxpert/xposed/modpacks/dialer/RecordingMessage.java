package sh.siava.pixelxpert.xposed.modpacks.dialer;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.speech.tts.TextToSpeech;

import java.io.ByteArrayInputStream;
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

	// High-performance lock-free concurrent sets to eliminate thread lock contention during calls
	private static final Set<Integer> matchedResourceIds = ConcurrentHashMap.newKeySet();
	private static final Set<Integer> ignoredResourceIds = ConcurrentHashMap.newKeySet();

	private static final String[] KNOWN_RESOURCE_NAMES = {
			"call_recording_starting_voice",
			"call_recording_ending_voice",
			"call_recording_speaker_starting_voice",
			"call_recording_speaker_ending_voice",
			"call_notes_starting_voice",
			"call_notes_ending_voice"
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

		// Hook getString on Resources
		ReflectedClass.of(Resources.class)
				.before("getString")
				.run(param -> {
					if (!removeRecodingMessage || param.args == null || param.args.length == 0) return;
					if (param.args[0] instanceof Integer) {
						int id = (Integer) param.args[0];
						if (isTargetRecordingResource(res, id)) {
							param.setResult("");
						}
					}
				});

		// Hook getText on Resources
		ReflectedClass.of(Resources.class)
				.before("getText")
				.run(param -> {
					if (!removeRecodingMessage || param.args == null || param.args.length == 0) return;
					if (param.args[0] instanceof Integer) {
						int id = (Integer) param.args[0];
						if (isTargetRecordingResource(res, id)) {
							param.setResult("");
						}
					}
				});

		// Hook openRawResource on Resources (in case audio prompt is loaded from raw resource)
		ReflectedClass.of(Resources.class)
				.before("openRawResource")
				.run(param -> {
					if (!removeRecodingMessage || param.args == null || param.args.length == 0) return;
					if (param.args[0] instanceof Integer) {
						int id = (Integer) param.args[0];
						if (isTargetRecordingResource(res, id)) {
							param.setResult(new ByteArrayInputStream(new byte[0]));
						}
					}
				});

		// Hook openRawResourceFd on Resources
		ReflectedClass.of(Resources.class)
				.before("openRawResourceFd")
				.run(param -> {
					if (!removeRecodingMessage || param.args == null || param.args.length == 0) return;
					if (param.args[0] instanceof Integer) {
						int id = (Integer) param.args[0];
						if (isTargetRecordingResource(res, id)) {
							param.setResult(null);
						}
					}
				});

		// Hook TextToSpeech.speak to mute any TTS announcements for call recording or call notes
		ReflectedClass ttsClass = ReflectedClass.ofIfPossible("android.speech.tts.TextToSpeech");
		if (ttsClass.getClazz() != null) {
			ttsClass.before("speak")
					.run(param -> {
						if (!removeRecodingMessage || param.args == null || param.args.length == 0) return;
						Object textObj = param.args[0];
						if (textObj == null) {
							param.setResult(TextToSpeech.SUCCESS);
							return;
						}
						String text = textObj.toString();
						if (isRecordingOrNotesText(text)) {
							param.setResult(TextToSpeech.SUCCESS);
						}
					});
		}

		// Framework-level audio hooks: Mute volume and zero PCM buffers so playback sessions finish on time
		// 1. SoundPool.play: Mute left & right volume (args 1 & 2) to 0.0f
		ReflectedClass soundPoolClass = ReflectedClass.ofIfPossible("android.media.SoundPool");
		if (soundPoolClass.getClazz() != null) {
			soundPoolClass.before("play")
					.run(param -> {
						if (!removeRecodingMessage || param.args == null || param.args.length < 3) return;
						param.args[1] = 0.0f; // leftVolume
						param.args[2] = 0.0f; // rightVolume
					});
		}

		// 2. MediaPlayer: Mute volume on start()
		ReflectedClass mediaPlayerClass = ReflectedClass.ofIfPossible("android.media.MediaPlayer");
		if (mediaPlayerClass.getClazz() != null) {
			mediaPlayerClass.before("start")
					.run(param -> {
						if (!removeRecodingMessage || param.thisObject == null) return;
						try {
							((android.media.MediaPlayer) param.thisObject).setVolume(0.0f, 0.0f);
						} catch (Throwable ignored) {}
					});
		}

		// 3. AudioTrack.write: Fill audio buffer with PCM zero silence
		ReflectedClass audioTrackClass = ReflectedClass.ofIfPossible("android.media.AudioTrack");
		if (audioTrackClass.getClazz() != null) {
			audioTrackClass.before("write")
					.run(param -> {
						if (!removeRecodingMessage || param.args == null || param.args.length == 0) return;
						Object buffer = param.args[0];
						if (buffer instanceof byte[]) {
							Arrays.fill((byte[]) buffer, (byte) 0);
						} else if (buffer instanceof short[]) {
							Arrays.fill((short[]) buffer, (short) 0);
						} else if (buffer instanceof float[]) {
							Arrays.fill((float[]) buffer, 0.0f);
						} else if (buffer instanceof ByteBuffer) {
							ByteBuffer bb = (ByteBuffer) buffer;
							int pos = bb.position();
							int lim = bb.limit();
							for (int i = pos; i < lim; i++) {
								bb.put(i, (byte) 0);
							}
						}
					});
		}

		// 4. ToneGenerator.startTone
		ReflectedClass toneGenClass = ReflectedClass.ofIfPossible("android.media.ToneGenerator");
		if (toneGenClass.getClazz() != null) {
			toneGenClass.before("startTone")
					.run(param -> {
						if (removeRecodingMessage) {
							param.setResult(false);
						}
					});
		}
	}

	private static boolean isTargetRecordingResource(Resources res, int id) {
		if (id <= 0) return false;
		if (matchedResourceIds.contains(id)) return true;
		if (ignoredResourceIds.contains(id)) return false;

		try {
			String entryName = res.getResourceEntryName(id);
			if (entryName != null) {
				String lower = entryName.toLowerCase(Locale.ROOT);

				// Protect UI text/labels/titles so Call Notes button text is never made blank
				if (lower.contains("_title")
						|| lower.contains("_label")
						|| lower.contains("_text")
						|| lower.contains("_name")
						|| lower.contains("_button")
						|| lower.contains("_description")
						|| lower.contains("_toast")
						|| lower.contains("_dialog")
						|| lower.contains("_summary")
						|| lower.contains("_header")
						|| lower.contains("_option")
						|| lower.contains("_item")
						|| lower.contains("_chip")
						|| lower.contains("_card")
						|| lower.contains("_icon")) {
					ignoredResourceIds.add(id);
					return false;
				}

				boolean hasCategory = lower.contains("recording")
						|| lower.contains("notes")
						|| lower.contains("transcript")
						|| lower.contains("transcription");

				boolean hasType = lower.contains("voice")
						|| lower.contains("disclaimer")
						|| lower.contains("disclosure")
						|| lower.contains("prompt")
						|| lower.contains("audio_disclosure");

				if (hasCategory && hasType) {
					matchedResourceIds.add(id);
					return true;
				}
			}
		} catch (Resources.NotFoundException ignored) {
		}

		ignoredResourceIds.add(id);
		return false;
	}

	private static boolean isRecordingOrNotesText(String text) {
		if (text == null) return true;
		String lower = text.toLowerCase(Locale.ROOT).trim();
		if (lower.isEmpty()) return true;

		return lower.contains("recording")
				|| lower.contains("recorded")
				|| lower.contains("call notes")
				|| lower.contains("transcript")
				|| lower.contains("transcrib")
				|| lower.contains("this call is being")
				|| lower.contains("this call is now");
	}
}