package sh.siava.pixelxpert.xposed.modpacks.dialer

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.speech.tts.TextToSpeech
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.DialerModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Silences the dialer's call-recording voice prompts. On load it resolves the
 * known recording/notes prompt resource names against the dialer's resources
 * (both `string` and `raw` ids) and then intercepts
 * `Resources.getText`/`getString`/`openRawResource`/`openRawResourceFd` plus
 * `TextToSpeech.speak`, so when the toggle is on the announcement is neither
 * read aloud nor played.
 *
 * Flipping the toggle kills the process (the hooks read one static flag), and
 * the typo'd `removeRecodingMessage` field name is kept from the original.
 */
@Suppress("RedundantThrows")
@DialerModPack
class RecordingMessage(context: Context) : XposedModPack(context) {

	private companion object {
		@JvmStatic
		private var removeRecodingMessage = false

		@JvmStatic
		private val matchedResourceIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

		@JvmStatic
		private val KNOWN_RESOURCE_NAMES = arrayOf(
			"call_recording_starting_voice",
			"call_recording_ending_voice",
			"call_recording_speaker_starting_voice",
			"call_recording_speaker_ending_voice",
			"call_notes_starting_voice",
			"call_notes_ending_voice",
		)

		@JvmStatic
		private fun isRecordingOrNotesText(text: String?): Boolean {
			if (text == null) return true
			val lower = text.lowercase(Locale.ROOT).trim()
			return lower.isEmpty() || lower.contains("recording") || lower.contains("recorded") ||
				lower.contains("call notes") || lower.contains("transcript") || lower.contains("transcrib") ||
				lower.contains("this call is being") || lower.contains("this call is now")
		}
	}

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {
		if (key.isNotEmpty() && key[0]!!.equals("DialerRemoveRecordMessage")) {
			SystemUtils.killSelf()
		}
		removeRecodingMessage = Xprefs.getBoolean("DialerRemoveRecordMessage", false)
	}

	@SuppressLint("DiscouragedApi")
	@Suppress("SENSELESS_COMPARISON")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		if (PRParam != null && PRParam.classLoader != null) {
			ReflectedClass.setDefaultClassloader(PRParam.classLoader)
		}

		val res = mContext.resources
		val targetPkg = if (PRParam != null &&
			PRParam.packageName != null
		) {
			PRParam.packageName
		} else {
			"com.google.android.dialer"
		}

		for (resName in KNOWN_RESOURCE_NAMES) {
			val strId = res.getIdentifier(resName, "string", targetPkg)
			if (strId > 0) matchedResourceIds.add(strId)
			val rawId = res.getIdentifier(resName, "raw", targetPkg)
			if (rawId > 0) matchedResourceIds.add(rawId)
		}

		ReflectedClass.of(Resources::class.java).before("getText").run { param ->
			if (removeRecodingMessage && param.args.isNotEmpty() && param.args[0] is Int) {
				if (matchedResourceIds.contains(param.args[0] as Int)) param.setResult("")
			}
		}

		ReflectedClass.of(Resources::class.java).before("getString").run { param ->
			if (removeRecodingMessage && param.args.isNotEmpty() && param.args[0] is Int) {
				if (matchedResourceIds.contains(param.args[0] as Int)) param.setResult("")
			}
		}

		ReflectedClass.of(Resources::class.java).before("openRawResource").run { param ->
			if (removeRecodingMessage && param.args.isNotEmpty() && param.args[0] is Int) {
				if (matchedResourceIds.contains(param.args[0] as Int)) param.setResult(ByteArrayInputStream(ByteArray(0)))
			}
		}

		ReflectedClass.of(Resources::class.java).before("openRawResourceFd").run { param ->
			if (removeRecodingMessage && param.args.isNotEmpty() && param.args[0] is Int) {
				if (matchedResourceIds.contains(param.args[0] as Int)) param.setResult(null)
			}
		}

		val ttsClass = ReflectedClass.ofIfPossible("android.speech.tts.TextToSpeech")
		if (ttsClass.clazz != null) {
			ttsClass.before("speak").run { param ->
				if (removeRecodingMessage && param.args.isNotEmpty() && param.args[0] != null) {
					if (isRecordingOrNotesText(param.args[0].toString())) param.setResult(TextToSpeech.SUCCESS)
				}
			}
		}
	}
}
