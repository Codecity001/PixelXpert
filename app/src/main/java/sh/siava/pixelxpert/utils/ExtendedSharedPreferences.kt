package sh.siava.pixelxpert.utils

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import java.util.Collections
import org.json.JSONObject

class ExtendedSharedPreferences private constructor(private val prefs: SharedPreferences) : SharedPreferences {

    companion object {
        const val IS_PREFS_INITIATED_KEY = "IsPrefsInitiated"

        @JvmStatic
        fun from(prefs: SharedPreferences): ExtendedSharedPreferences {
            return ExtendedSharedPreferences(prefs)
        }
    }

    val mOnSharedPreferenceChangeListeners: MutableList<OnSharedPreferenceChangeListener> =
        Collections.synchronizedList(mutableListOf())
    var mIsPrefsInitiated: Boolean = prefs.getBoolean(IS_PREFS_INITIATED_KEY, false)

    private val listener = OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (IS_PREFS_INITIATED_KEY == key) {
            mIsPrefsInitiated = getBoolean(IS_PREFS_INITIATED_KEY, false)
        }

        if (mIsPrefsInitiated && mOnSharedPreferenceChangeListeners.isNotEmpty()) {
            mOnSharedPreferenceChangeListeners.forEach { it.onSharedPreferenceChanged(sharedPreferences, key) }
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

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

    fun getSliderFloat(key: String, defaultVal: Float = 0f): Float {
        return getSliderValues(key, defaultVal).firstOrNull() ?: defaultVal
    }

    fun getSliderInt(key: String, defaultVal: Int = 0): Int {
        return Math.round(getSliderFloat(key, defaultVal.toFloat()))
    }

    override fun getAll(): Map<String, *> = prefs.all ?: emptyMap<String, Any>()

    override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = prefs.getStringSet(key, defValues)

    override fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = prefs.getLong(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float = prefs.getFloat(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun edit(): SharedPreferences.Editor = prefs.edit()

    override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        mOnSharedPreferenceChangeListeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        mOnSharedPreferenceChangeListeners.remove(listener)
    }
}
