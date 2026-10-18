package sh.siava.pixelxpert.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.utils.ExtendedSharedPreferences

class PrefStore(context: Context) {

    private val appContext: Context = context.applicationContext
    val storageContext: Context = runCatching {
        appContext.createDeviceProtectedStorageContext()
    }.getOrDefault(appContext)

    val prefs: SharedPreferences = storageContext.getSharedPreferences(
        Constants.DEFAULT_PREFS_FILE_NAME,
        Context.MODE_PRIVATE
    )

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _revision.value += 1
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    companion object {
        @JvmStatic
        fun markReady(context: Context) {
            runCatching {
                val dps = context.createDeviceProtectedStorageContext()
                val sp = dps.getSharedPreferences(Constants.DEFAULT_PREFS_FILE_NAME, Context.MODE_PRIVATE)
                sp.edit().putBoolean(ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY, true).apply()
            }
        }
    }

    fun getBoolean(key: String, defValue: Boolean = false): Boolean = prefs.getBoolean(key, defValue)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getInt(key: String, defValue: Int = 0): Int = prefs.getInt(key, defValue)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getFloat(key: String, defValue: Float = 0f): Float = prefs.getFloat(key, defValue)

    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun getLong(key: String, defValue: Long = 0L): Long = prefs.getLong(key, defValue)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun getString(key: String, defValue: String = ""): String = prefs.getString(key, defValue) ?: defValue

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getStringSet(key: String, defValue: Set<String> = emptySet()): Set<String> =
        prefs.getStringSet(key, defValue) ?: defValue

    fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun getAll(): Map<String, *> = prefs.all ?: emptyMap<String, Any>()

    fun getSliderValues(key: String, defaultValue: Float = 100f): List<Float> {
        return try {
            val raw = prefs.getString(key, null)
            if (raw.isNullOrBlank()) {
                listOf(defaultValue)
            } else if (raw.trim().startsWith("{")) {
                val json = JSONObject(raw)
                val names = json.names()
                if (names != null && names.length() > 0) {
                    val arr = json.getJSONArray(names.getString(0))
                    val list = mutableListOf<Float>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getDouble(i).toFloat())
                    }
                    if (list.isNotEmpty()) list else listOf(defaultValue)
                } else {
                    listOf(defaultValue)
                }
            } else if (raw.contains(",")) {
                raw.split(",").mapNotNull { it.trim().toFloatOrNull() }.ifEmpty { listOf(defaultValue) }
            } else {
                listOf(raw.trim().toFloatOrNull() ?: defaultValue)
            }
        } catch (_: Throwable) {
            try {
                listOf(prefs.getFloat(key, defaultValue))
            } catch (_: Throwable) {
                try {
                    listOf(prefs.getInt(key, defaultValue.toInt()).toFloat())
                } catch (_: Throwable) {
                    listOf(defaultValue)
                }
            }
        }
    }

    fun getSliderFloat(key: String, defaultValue: Float = 0f): Float {
        return getSliderValues(key, defaultValue).firstOrNull() ?: defaultValue
    }

    fun getSliderInt(key: String, defaultValue: Int = 0): Int {
        return Math.round(getSliderFloat(key, defaultValue.toFloat()))
    }
}
