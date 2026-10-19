package sh.siava.pixelxpert.util

import android.content.SharedPreferences
import com.google.gson.Gson

private val mappingGson = Gson()

/**
 * The drawable substitution map that the Xposed side reads.
 *
 * Kept byte-compatible on purpose: the value under
 * [DRAWABLE_MAPPING_KEY] is a JSON object keyed by the
 * original resource name, each entry naming the pack and the replacement
 * resource that should stand in for it. Hook code parses this string, so the
 * field names below are the contract - they are what is already stored under
 * the key on device and what existing hooks expect to find.
 *
 * It lives in the app's own preferences file
 * ([sh.siava.pixelxpert.data.PrefStore.FILE_NAME]), the same file the
 * module exposes to the hooks, which is why this writes through a raw
 * `SharedPreferences` rather than through `PrefStore`: the value is a plain
 * string the screen builds itself.
 */
object ThemePackMapping {

	const val DRAWABLE_MAPPING_KEY: String = "drawableMapping"

	/** The one mapping type the format defines: drawable. */
	const val TYPE_DRAWABLE: Int = 0

	/**
	 * A resolved replacement: the overlay [packageName] holding the resource
	 * and the integer id to substitute for it. Both were plain mutable public
	 * fields in the Java, so both stay settable here.
	 */
	class OverlayID(
		var packageName: String,
		var resID: Int,
	)

	/** One replacement: find [resName] inside [packageName], use [replaceResName]. */
	data class OverlayIDName(
		val resName: String,
		val packageName: String,
	) {
		override fun toString(): String = mappingGson.toJson(this)
	}

	/**
	 * resource id -> the overlay [OverlayID] supplying it.
	 *
	 * Extends [HashMap] directly (rather than delegating to one) because
	 * callers index and mutate it exactly like the Java subclass did.
	 */
	class IDMapping : HashMap<Int, OverlayID>()

	/**
	 * resource name -> the replacement standing in for it.
	 *
	 * A [HashMap] rather than a data collection because Gson reads and writes
	 * the subclass directly as a JSON object, exactly as it did with the Java
	 * type.
	 */
	class Mapping : HashMap<String, OverlayIDName>() {

		override fun toString(): String = mappingGson.toJson(this)

		fun add(resName: String, packageName: String, replaceResName: String) {
			// Removing the key first, as `replace` does, lands on the same
			// result as putting it: the value is replaced either way.
			put(resName, OverlayIDName(resName = replaceResName, packageName = packageName))
		}

		fun replace(resName: String, packageName: String, replaceResName: String) {
			if (containsKey(resName)) {
				remove(resName)
				put(resName, OverlayIDName(resName = replaceResName, packageName = packageName))
			}
		}

		fun isEnabled(resName: String, packageName: String, replaceResName: String): Boolean {
			val idName = get(resName) ?: return false
			return idName.packageName == packageName && idName.resName == replaceResName
		}
	}

	fun loadMapping(prefs: SharedPreferences, type: Int): Mapping = when (type) {
		TYPE_DRAWABLE -> fromJSONString(prefs.getString(DRAWABLE_MAPPING_KEY, ""))
		else -> Mapping()
	}

	fun saveMapping(prefs: SharedPreferences, type: Int, mapping: Mapping) {
		when (type) {
			TYPE_DRAWABLE -> prefs.edit().putString(DRAWABLE_MAPPING_KEY, mapping.toString()).apply()
		}
	}

	private fun fromJSONString(jsonString: String?): Mapping =
		runCatching { mappingGson.fromJson(jsonString, Mapping::class.java) }
			.getOrNull() ?: Mapping()
}
