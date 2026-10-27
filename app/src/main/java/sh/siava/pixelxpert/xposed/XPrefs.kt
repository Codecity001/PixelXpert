package sh.siava.pixelxpert.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.utils.ExtendedRemotePreferences

/**
 * Bridge between the hooked process and the module app's preferences.
 *
 * Everything a mod pack
 * reads arrives through [Xprefs], a [ExtendedRemotePreferences] over the
 * `<applicationId>_preferences` file the app writes - the authority and file
 * name are the same contract [sh.siava.pixelxpert.data.PrefStore] documents
 * on the other side, so a key set by the UI is what the hooks observe.
 *
 * [onContentProviderLoaded] is the "everything is readable now" point: it does
 * the first full fan-out and then registers the change listener, because
 * before the provider answers the first read throws.
 */
object XPrefs {

	/**
	 * The handle every reader goes through.
	 *
	 * `lateinit` rather than nullable: the field is non-null by contract, so
	 * every call site before [init] would be a crash either way - same
	 * failure, clearer message.
	 *
	 * Because it is `lateinit` and non-null, the `if (Xprefs == null) return`
	 * that opens the preference readers is dead code here and has been
	 * dropped from every pack: an uninitialised read throws
	 * `UninitializedPropertyAccessException` rather than yielding null, so the
	 * guard could never fire. All it does is read like a working null check
	 * that is not one.
	 */
	@JvmStatic
	@SuppressLint("StaticFieldLeak")
	lateinit var Xprefs: ExtendedRemotePreferences

	@JvmStatic
	val MagiskRoot: String = "/data/adb/modules/PixelXpert"

	@JvmStatic
	var packageName: String? = null
		private set

	private val listener =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			val target = packageName ?: return@OnSharedPreferenceChangeListener
			loadEverything(target, key)
		}

	@JvmStatic
	fun init(context: Context) {
		packageName = context.packageName

		Xprefs = ExtendedRemotePreferences(
			context,
			BuildConfig.APPLICATION_ID,
			BuildConfig.APPLICATION_ID + "_preferences",
			true,
		)
	}

	@JvmStatic
	fun onContentProviderLoaded() {
		loadEverything(packageName ?: return)
		Xprefs.registerOnSharedPreferenceChangeListener(listener)
	}

	/** Detaches the change listener before this generation is retired. */
	@JvmStatic
	fun release() {
		if (this::Xprefs.isInitialized) {
			runCatching { Xprefs.unregisterOnSharedPreferenceChangeListener(listener) }
		}
		packageName = null
	}

	/**
	 * Fans [key] out to every running mod pack.
	 *
	 * Bootloop bookkeeping is dropped on purpose: relaying those keys would
	 * re-enter this very method from inside [sh.siava.pixelxpert.xposed.utils.BootLoopProtector],
	 * which is the one thing the exclusion list exists to prevent.
	 */
	@JvmStatic
	fun loadEverything(packageName: String, vararg key: String?) {
		if (key.isNotEmpty() &&
			(key[0] == null || Constants.PREF_UPDATE_EXCLUSIONS.any { key[0]!!.startsWith(it) })
		) {
			return
		}

		setPackagePrefs(packageName)

		for (mod in XPLauncher.runningMods) {
			mod.onPreferenceUpdated(*key)
		}
	}

	/**
	 * Hook for per-package preference shaping.
	 *
	 * Empty in both generations: nothing has needed it yet, and the call site
	 * is part of the load sequence, so it stays rather than being removed.
	 */
	@Suppress("unused")
	@JvmStatic
	fun setPackagePrefs(packageName: String?) {
	}
}
