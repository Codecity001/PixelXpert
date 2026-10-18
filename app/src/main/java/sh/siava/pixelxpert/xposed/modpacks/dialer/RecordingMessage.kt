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

@DialerModPack
class RecordingMessage(context: Context) : XposedModPack(context) {

    companion object {
        private var removeRecodingMessage = false
        private val matchedResourceIds = ConcurrentHashMap.newKeySet<Int>()

        private val KNOWN_RESOURCE_NAMES = arrayOf(
            "call_recording_starting_voice", "call_recording_ending_voice",
            "call_recording_speaker_starting_voice", "call_recording_speaker_ending_voice",
            "call_notes_starting_voice", "call_notes_ending_voice"
        )

        private fun isRecordingOrNotesText(text: String?): Boolean {
            if (text == null) return true
            val lower = text.lowercase(Locale.ROOT).trim()
            return lower.isEmpty() || lower.contains("recording") || lower.contains("recorded")
                    || lower.contains("call notes") || lower.contains("transcript") || lower.contains("transcrib")
                    || lower.contains("this call is being") || lower.contains("this call is now")
        }
    }

    override fun onPreferenceUpdated(vararg key: String) {
        if (key.isNotEmpty() && key[0] == "DialerRemoveRecordMessage") {
            SystemUtils.killSelf()
        }
        removeRecodingMessage = Xprefs.getBoolean("DialerRemoveRecordMessage", false)
    }

    @SuppressLint("DiscouragedApi")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            param.classLoader?.let {
                ReflectedClass.setDefaultClassloader(it)
            }

            val res = mContext.resources
            val targetPkg = param.packageName ?: "com.google.android.dialer"

            for (resName in KNOWN_RESOURCE_NAMES) {
                val strId = res.getIdentifier(resName, "string", targetPkg)
                if (strId > 0) matchedResourceIds.add(strId)
                val rawId = res.getIdentifier(resName, "raw", targetPkg)
                if (rawId > 0) matchedResourceIds.add(rawId)
            }

            // Resource hooks to suppress voice strings & raw audio files
            ReflectedClass.of(Resources::class.java).before("getText").run { hookParam ->
                if (removeRecodingMessage) {
                    val id = hookParam.args.getOrNull(0) as? Int
                    if (id != null && matchedResourceIds.contains(id)) {
                        hookParam.result = ""
                    }
                }
            }

            ReflectedClass.of(Resources::class.java).before("getString").run { hookParam ->
                if (removeRecodingMessage) {
                    val id = hookParam.args.getOrNull(0) as? Int
                    if (id != null && matchedResourceIds.contains(id)) {
                        hookParam.result = ""
                    }
                }
            }

            ReflectedClass.of(Resources::class.java).before("openRawResource").run { hookParam ->
                if (removeRecodingMessage) {
                    val id = hookParam.args.getOrNull(0) as? Int
                    if (id != null && matchedResourceIds.contains(id)) {
                        hookParam.result = ByteArrayInputStream(ByteArray(0))
                    }
                }
            }

            ReflectedClass.of(Resources::class.java).before("openRawResourceFd").run { hookParam ->
                if (removeRecodingMessage) {
                    val id = hookParam.args.getOrNull(0) as? Int
                    if (id != null && matchedResourceIds.contains(id)) {
                        hookParam.result = null
                    }
                }
            }

            // Text-To-Speech muting
            val ttsClass = ReflectedClass.ofIfPossible("android.speech.tts.TextToSpeech")
            if (ttsClass.clazz != null) {
                ttsClass.before("speak").run { hookParam ->
                    if (removeRecodingMessage) {
                        val text = hookParam.args.getOrNull(0)?.toString()
                        if (text != null && isRecordingOrNotesText(text)) {
                            hookParam.result = TextToSpeech.SUCCESS
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }
}
