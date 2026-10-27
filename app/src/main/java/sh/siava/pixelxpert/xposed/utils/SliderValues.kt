package sh.siava.pixelxpert.xposed.utils

import android.content.SharedPreferences
import android.util.JsonReader
import java.io.StringReader

/**
 * Reads a slider's stored value, in every shape a slider can be written in.
 *
 * The String-first read order is load bearing: a single-handle slider stores a
 * Float, a range slider stores `{"":[lo,hi]}` JSON (what this app's
 * `PrefRenderer.writeRange` emits), and an imported preference file may carry
 * whichever type the exporting build wrote. Reads try String, then Float,
 * then Int, and only then give up, so all three survive.
 */
object SliderValues {

	/**
	 * Every value stored under [key], parsed out of whatever container it is in.
	 *
	 * An empty or unparsable container yields an empty list for the JSON form
	 * and falls through to the scalar forms.
	 */
	@JvmStatic
	fun getValues(prefs: SharedPreferences, key: String, defaultValue: Float): List<Float> = try {
		getValues(prefs.getString(key, "") ?: "")
	} catch (ignored: Exception) {
		try {
			listOf(prefs.getFloat(key, defaultValue))
		} catch (ignored2: Exception) {
			try {
				listOf(prefs.getInt(key, Math.round(defaultValue)).toFloat())
			} catch (ignored3: Exception) {
				listOf(defaultValue)
			}
		}
	}

	/**
	 * Parses `{"":[lo,hi]}` - and, defensively, a bare `[lo,hi]`.
	 *
	 * `JsonReader` is used rather than a JSON library because its parse is
	 * tolerant: a stray name inside the array is skipped, and the
	 * object name is skipped, so both container shapes decode to the same list.
	 */
	@JvmStatic
	@Throws(Exception::class)
	fun getValues(jsonString: String): List<Float> {
		val values = ArrayList<Float>()
		if (jsonString.trim().isEmpty()) return values

		val reader = JsonReader(StringReader(jsonString))
		reader.beginObject()
		try {
			reader.nextName()
			reader.beginArray()
		} catch (ignored: Exception) {
		}

		while (reader.hasNext()) {
			try {
				reader.nextName()
			} catch (ignored: Exception) {
			}
			values.add(reader.nextDouble().toFloat())
		}
		return values
	}

	@JvmStatic
	fun getSingleFloatValue(prefs: SharedPreferences, key: String, defaultValue: Float): Float = try {
		getValues(prefs, key, defaultValue)[0]
	} catch (ignored: Throwable) {
		defaultValue
	}

	@JvmStatic
	fun getSingleIntValue(prefs: SharedPreferences, key: String, defaultValue: Int): Int =
		Math.round(getSingleFloatValue(prefs, key, defaultValue.toFloat()))
}
