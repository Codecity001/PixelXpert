package sh.siava.pixelxpert.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.ui.prefs.node.ValuePref

/**
 * Reads and writes the module's preferences.
 *
 * Cross-process contract with the Xposed side: the hooks open this exact file
 * through `RemotePreferences(authority = applicationId,
 * prefFileName = applicationId + "_preferences")`. That file name and
 * authority are the contract the hooks bind to, so keys and defaults
 * written here are what the hooks will observe.
 *
 * @see sh.siava.pixelxpert.data.RemotePrefProviderAuthority
 */
class PrefStore(context: Context) {

	private val appContext: Context = context.applicationContext

	private val prefs: SharedPreferences = open(appContext)

	/**
	 * Bumped on every write so composables can invalidate.
	 *
	 * Rather than wrapping every key in its own state object, readers observe
	 * this single revision counter and re-read synchronously. With 272 keys
	 * across 21 screens that keeps allocation trivial and guarantees a screen
	 * never shows a stale value after a write.
	 */
	private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0)

	init {
		prefs.registerOnSharedPreferenceChangeListener { _, _ ->
			_revision.value++
		}
	}

	/** Observe this in composition; changes here invalidate every reader. */
	val revision: kotlinx.coroutines.flow.StateFlow<Int> = _revision

	private val _activeSliderKey = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
	/** Key of the slider currently being adjusted by the user, or null if idle. */
	val activeSliderKey: kotlinx.coroutines.flow.StateFlow<String?> = _activeSliderKey

	fun setActiveSliderKey(key: String?) {
		_activeSliderKey.value = key
	}

	// ------------------------------------------------------------------ reads

	fun getString(key: String, default: String? = null): String? =
		prefs.getString(key, default)

	fun getBoolean(key: String, default: Boolean = false): Boolean =
		prefs.getBoolean(key, default)

	fun getFloat(key: String, default: Float = 0f): Float =
		prefs.getFloat(key, default)

	/**
	 * Integer read with the same tolerance [sliderValue] applies: a writer
	 * that stored the value under a different type must not take the reader
	 * down with a ClassCastException.
	 *
	 * This accessor is on a cross-process path - the Xposed side counts
	 * bootloop strikes through RemotePreferences into this same file, and
	 * the Hooks page reports them - so it is worth the same care the slider
	 * keys now get.
	 */
	fun getInt(key: String, default: Int = 0): Int = try {
		prefs.getInt(key, default)
	} catch (ignored: ClassCastException) {
		val str = prefs.getString(key, null)
		str?.toIntOrNull()
			?: runCatching { android.graphics.Color.parseColor(str) }.getOrNull()
			?: str?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)?.toInt()
			?: default
	}

	fun getStringSet(key: String, default: Set<String>? = null): Set<String>? =
		prefs.getStringSet(key, default)

	/**
	 * Slider read as an integer: the slider's stored number is read as a
	 * Float and rounded to the nearest whole one.
	 *
	 * [getInt] is the wrong tool for a slider key - a slider stores a Float,
	 * so `SharedPreferences.getInt` throws and only [getInt]'s String
	 * fallback saves it, handing back the default instead of the stored
	 * value. The updater's `volumeStps` is exactly such a key.
	 */
	fun getSliderInt(key: String, default: Int): Int = runCatching {
		(sliderValue(key, default.toString()) ?: default.toString()).toFloat().roundToInt()
	}.getOrDefault(default)

	fun getSliderFloat(key: String, default: Float): Float = runCatching {
		(sliderValue(key, default.toString()) ?: default.toString()).toFloat()
	}.getOrDefault(default)

	// ----------------------------------------------------------------- writes

	fun putString(key: String, value: String?) {
		prefs.edit().putString(key, value).apply()
		_revision.value++
	}

	fun putBoolean(key: String, value: Boolean) {
		prefs.edit().putBoolean(key, value).apply()
		_revision.value++
	}

	fun putFloat(key: String, value: Float) {
		prefs.edit().putFloat(key, value).apply()
		_revision.value++
	}

	fun putInt(key: String, value: Int) {
		prefs.edit().putInt(key, value).apply()
		_revision.value++
	}

	fun putStringSet(key: String, value: Set<String>?) {
		prefs.edit().putStringSet(key, value).apply()
		_revision.value++
	}

	/**
	 * Resolves a preference's effective value, falling back to the default
	 * the pref itself declares.
	 *
	 * The stored type differs per widget (switches are booleans, sliders are
	 * floats, lists are strings), and a key may legitimately have been written
	 * by an older widget type. `runCatching` keeps that from crashing: a type
	 * mismatch simply falls back to the declared default.
	 */
	fun read(pref: ValuePref): String? {
		val d = pref.default
		return when (pref) {
			is sh.siava.pixelxpert.ui.prefs.node.SwitchPref ->
				if (contains(pref.key)) getBoolean(pref.key, d == "true").toString()
				else d

			is sh.siava.pixelxpert.ui.prefs.node.SliderPref ->
				if (contains(pref.key)) sliderValue(pref.key, d) else d

			is sh.siava.pixelxpert.ui.prefs.node.ListPref ->
				if (contains(pref.key)) {
					if (pref.multiSelect) {
						getStringSet(pref.key)?.joinToString(",") ?: d
					} else getString(pref.key, d)
				} else d

			is sh.siava.pixelxpert.ui.prefs.node.TimePref ->
				if (contains(pref.key)) getInt(pref.key, d?.toIntOrNull() ?: 0).toString() else d

			is sh.siava.pixelxpert.ui.prefs.node.ColorPref ->
				if (contains(pref.key)) {
					try {
						getString(pref.key, d)
					} catch (e: ClassCastException) {
						getInt(pref.key, d?.toIntOrNull() ?: 0).toString()
					}
				} else d

			else ->
				if (contains(pref.key)) getString(pref.key, d) else d
		}
	}

	/** Writes a [ValuePref] back, coercing to the widget's native type. */
	fun write(pref: ValuePref, value: String?) {
		when (pref) {
			is sh.siava.pixelxpert.ui.prefs.node.SwitchPref ->
				putBoolean(pref.key, value == "true")

			is sh.siava.pixelxpert.ui.prefs.node.SliderPref -> {
				// A range value is not a single number; storing it as one
				// would coerce "0,50" to 0f and lose the upper bound.
				if (value != null && value.toFloatOrNull() == null) putString(pref.key, value)
				else putFloat(pref.key, value?.toFloatOrNull() ?: 0f)
			}

			is sh.siava.pixelxpert.ui.prefs.node.ListPref ->
				if (pref.multiSelect) {
					putStringSet(
						pref.key,
						value?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
					)
				} else putString(pref.key, value)

			is sh.siava.pixelxpert.ui.prefs.node.TimePref ->
				putInt(pref.key, value?.toIntOrNull() ?: 0)

			else -> putString(pref.key, value)
		}
	}

	/** True once the key has been written; used to distinguish "unset" from "false". */
	fun contains(key: String): Boolean = prefs.contains(key)

	/**
	 * A slider's stored value, whatever type it happens to be.
	 *
	 * `SharedPreferences.getFloat` throws `ClassCastException` for any other
	 * type, and three writers disagree here: single-handle sliders are
	 * written as a Float by `SliderItem`, range sliders as a `lo,hi` String
	 * by its `writeRange`, and a range-slider preference writes
	 * `{"":[lo,hi]}` JSON. So String is read first, then Float, then Int:
	 * a value already stored on the device - or one carried in by an
	 * imported `.bin` - has whichever type the writer that produced it used.
	 *
	 * Assuming Float is what crashed the Misc screen the moment a range
	 * slider had been written: `read` asked for a Float, got the String the
	 * row itself had just stored, and died in `SharedPreferencesImpl`.
	 */
	private fun sliderValue(key: String, default: String?): String? {
		val raw = prefs.all[key] ?: return default
		return when (raw) {
			is String -> raw
			is Number -> raw.toString()
			else -> default
		}
	}

	// ---------------------------------------------- import / export / reset

	/**
	 * Serialises every stored key to [uri].
	 *
	 * Export writes `ObjectOutputStream(SharedPreferences.getAll())`, the
	 * same byte format as backup files already on the device, so those
	 * backups stay importable and the keys they hold are the ones in
	 * `<pkg>_preferences`.
	 */
	fun exportTo(uri: Uri): Boolean =
		appContext.contentResolver.openOutputStream(uri)?.use(::writeAll) ?: false

	private fun writeAll(out: OutputStream): Boolean = try {
		ObjectOutputStream(out).use { it.writeObject(prefs.all) }
		true
	} catch (e: IOException) {
		false
	}

	/**
	 * Replaces every stored key with the map held in [uri].
	 *
	 * Clear, then re-add through the typed setters (the editor has no
	 * generic put), skipping the initialisation marker key, which carries no
	 * meaning in this app. A file that is not a preference export returns
	 * false and leaves prefs untouched, because the clear only happens once
	 * the map has decoded.
	 */
	fun importFrom(uri: Uri): Boolean =
		appContext.contentResolver.openInputStream(uri)?.use(::readAll) ?: false

	@Suppress("UNCHECKED_CAST")
	private fun readAll(input: InputStream): Boolean {
		val map = try {
			ObjectInputStream(input).readObject() as? Map<String, Any?>
		} catch (e: Exception) {
			return false
		} ?: return false

		val editor = prefs.edit().clear()
		for ((key, value) in map) {
			if (key == LEGACY_INIT_MARKER) continue
			when (value) {
				is Boolean -> editor.putBoolean(key, value)
				is String -> editor.putString(key, value)
				is Int -> editor.putInt(key, value)
				is Float -> editor.putFloat(key, value)
				is Long -> editor.putLong(key, value)
				is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
				else -> Unit // unknown type: ignore rather than abort the import
			}
		}
		val ok = editor.commit()
		_revision.value++
		return ok
	}

	/**
	 * Reset all settings.
	 *
	 * Dropping every key is sufficient: [read] falls back to the default
	 * declared in the generated screen whenever a key is absent, so every
	 * screen is back on its declared defaults afterwards.
	 */
	fun resetAll(): Boolean {
		val ok = prefs.edit().clear().commit()
		_revision.value++
		return ok
	}

	companion object {
		/** On-disk initialisation marker; carries no meaning in this app. */
		const val LEGACY_INIT_MARKER = "IsPrefsInitiated"

		/**
		 * Opens [FILE_NAME] in device-protected storage, which is where the
		 * hooks read it.
		 *
		 * `RemotePrefProvider` serves `RemotePreferenceFile(FILE_NAME, true)`,
		 * so a key written through credential-protected storage lands in a file
		 * the hooks never open - silently, because the name matches on both
		 * sides. The store is device-protected end to end, which is also why
		 * it answers during direct boot, before the user has unlocked.
		 *
		 * Every read and write of [FILE_NAME] goes through here; reaching for
		 * `context.getSharedPreferences(FILE_NAME, ...)` directly is the bug
		 * this function exists to prevent.
		 */
		fun open(context: Context): SharedPreferences =
			context.applicationContext
				.createDeviceProtectedStorageContext()
				.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

		/**
		 * Flags the store as ready for the hooks.
		 *
		 * The marker is a no-op for readers in this app - generated screens
		 * already fall back to their declared defaults for an absent key -
		 * but the hooks still read it: `ExtendedRemotePreferences` holds back
		 * its listener fan-out until the marker is `true`, so without it a
		 * mod pack would load once and then never see another preference
		 * change.
		 *
		 * Called from `PxnApp.onCreate`, which Android runs before the content
		 * provider that serves the hooks is created, so the very first remote
		 * read already observes it.
		 */
		/**
		 * Writes [LEGACY_INIT_MARKER] so the hooks stop withholding preference
		 * changes, then moves any keys left in credential-protected storage.
		 *
		 * `RemotePrefProvider` is `directBootAware`, so Android may start this
		 * process before the user has unlocked. The store itself is
		 * device-protected and answers then, but the credential-protected file
		 * this build wrote before it moved does not: `getSharedPreferences`
		 * throws `IllegalStateException` - and a throw out of `Application.onCreate`
		 * kills the process before a single component exists, taking the
		 * provider down with it. So the first attempt is guarded, and when
		 * storage is genuinely not ready yet both steps are repeated on
		 * `ACTION_USER_UNLOCKED`, which is exactly when it becomes possible.
		 */
		@JvmStatic
		fun markReady(context: Context) {
			val appContext = context.applicationContext
			try {
				writeMarker(appContext)
				migrateFromCredentialStorage(appContext)
			} catch (ignored: Throwable) {
				val receiver = object : BroadcastReceiver() {
					override fun onReceive(ctx: Context, intent: Intent) {
						try {
							writeMarker(appContext)
							migrateFromCredentialStorage(appContext)
						} catch (ignored: Throwable) {
						}
						runCatching { appContext.unregisterReceiver(this) }
					}
				}
				appContext.registerReceiver(
					receiver,
					IntentFilter(Intent.ACTION_USER_UNLOCKED),
					Context.RECEIVER_EXPORTED,
				)
			}
		}

		/** Sets [LEGACY_INIT_MARKER] when it is not already set. */
		private fun writeMarker(appContext: Context) {
			val prefs = open(appContext)
			if (prefs.getBoolean(LEGACY_INIT_MARKER, false)) return
			@Suppress("ApplySharedPref")
			prefs.edit().putBoolean(LEGACY_INIT_MARKER, true).commit()
		}

		/**
		 * One-time move of the keys this build wrote to credential-protected
		 * storage before [open] existed.
		 *
		 * Only installs that predate the fix ever wrote keys to
		 * credential-protected storage, so the move exists for them alone,
		 * and it is why the two files are named alike without holding the
		 * same keys.
		 *
		 * Deliberate properties:
		 *
		 *  * it never throws, because a failure here must not defer
		 *    [writeMarker], which is what unblocks the hooks' change listener;
		 *    a credential read during direct boot is simply skipped and picked
		 *    up on the next start;
		 *  * a key already present in the destination is left alone, so
		 *    values the hooks have written back since boot win;
		 *  * the source is only deleted once the copy commits, so a partial
		 *    run resumes rather than losing data, and a fully drained source
		 *    makes the whole thing a no-op afterwards.
		 */
		private fun migrateFromCredentialStorage(appContext: Context) {
			try {
				val source = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
				val pending = source.all
				if (pending.isEmpty()) return

				val target = open(appContext)
				val editor = target.edit()
				for ((key, value) in pending) {
					if (target.contains(key)) continue
					when (value) {
						is Boolean -> editor.putBoolean(key, value)
						is String -> editor.putString(key, value)
						is Int -> editor.putInt(key, value)
						is Float -> editor.putFloat(key, value)
						is Long -> editor.putLong(key, value)
						is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
						else -> Unit // unknown type: skip rather than abort the move
					}
				}
				if (editor.commit()) {
					appContext.deleteSharedPreferences(FILE_NAME)
				}
			} catch (ignored: Throwable) {
				// credential storage is unreadable before unlock; retried on the next start
			}
		}

		/**
		 * The file name the hooks open by, so changing it would silently
		 * disconnect the UI from them - and would leave the pref file already
		 * on the device unread.
		 */
		val FILE_NAME: String = BuildConfig.APPLICATION_ID + "_preferences"
	}
}

/**
 * Authority of the `RemotePrefProvider` that exposes [PrefStore.FILE_NAME] to
 * hooked processes. It is the authority other processes bind to, so it stays
 * `BuildConfig.APPLICATION_ID`.
 */
const val REMOTE_PREF_AUTHORITY: String = BuildConfig.APPLICATION_ID

/** Revision-driven read; recomposes whenever the underlying key changes. */
@Composable
fun PrefStore.valueOf(pref: ValuePref): String? {
	val rev by revision.collectAsState()
	@Suppress("UNUSED_EXPRESSION") rev
	return read(pref)
}
