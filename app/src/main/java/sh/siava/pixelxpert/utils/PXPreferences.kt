package sh.siava.pixelxpert.utils

import android.content.SharedPreferences
import sh.siava.pixelxpert.PixelXpert

object PXPreferences {

    val prefs: ExtendedSharedPreferences
        get() = PixelXpert.get().defaultPreferences

    private val editor: SharedPreferences.Editor
        get() = prefs.edit()

    @JvmStatic
    fun getPrefs(): ExtendedSharedPreferences = prefs

    @JvmStatic
    fun putBoolean(key: String, value: Boolean) {
        editor.putBoolean(key, value).apply()
    }

    @JvmStatic
    fun putInt(key: String, value: Int) {
        editor.putInt(key, value).apply()
    }

    @JvmStatic
    fun putFloat(key: String, value: Float) {
        editor.putFloat(key, value).apply()
    }

    @JvmStatic
    fun putLong(key: String, value: Long) {
        editor.putLong(key, value).apply()
    }

    @JvmStatic
    fun putString(key: String, value: String?) {
        editor.putString(key, value).apply()
    }

    @JvmStatic
    fun getBoolean(key: String): Boolean = prefs.getBoolean(key, false)

    @JvmStatic
    fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    @JvmStatic
    fun getInt(key: String): Int = prefs.getInt(key, 0)

    @JvmStatic
    fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)

    @JvmStatic
    fun getLong(key: String): Long = prefs.getLong(key, 0L)

    @JvmStatic
    fun getLong(key: String, defValue: Long): Long = prefs.getLong(key, defValue)

    @JvmStatic
    fun getFloat(key: String): Float = prefs.getFloat(key, 0f)

    @JvmStatic
    fun getFloat(key: String, defValue: Float): Float = prefs.getFloat(key, defValue)

    @JvmStatic
    fun getString(key: String): String? = prefs.getString(key, null)

    @JvmStatic
    fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)

    @JvmStatic
    fun getSliderInt(key: String, defaultVal: Int): Int = prefs.getSliderInt(key, defaultVal)

    @JvmStatic
    fun getSliderValues(key: String, defaultValue: Float): List<Float> = prefs.getSliderValues(key, defaultValue)

    @JvmStatic
    fun getSliderFloat(key: String, defaultVal: Float): Float = prefs.getSliderFloat(key, defaultVal)

    @JvmStatic
    fun clear(vararg keys: String) {
        val edit = editor
        for (key in keys) {
            edit.remove(key)
        }
        edit.apply()
    }

    @JvmStatic
    fun clearAll() {
        editor.clear().apply()
    }
}
