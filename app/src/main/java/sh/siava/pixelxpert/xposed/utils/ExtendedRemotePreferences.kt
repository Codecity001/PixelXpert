package sh.siava.pixelxpert.xposed.utils

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import com.crossbowffs.remotepreferences.RemotePreferences
import java.util.Collections
import org.json.JSONObject
import sh.siava.pixelxpert.utils.ExtendedSharedPreferences.Companion.IS_PREFS_INITIATED_KEY

class ExtendedRemotePreferences : RemotePreferences {

    val mOnSharedPreferenceChangeListeners: MutableList<OnSharedPreferenceChangeListener> =
        Collections.synchronizedList(mutableListOf())
    var mIsPrefsInitiated: Boolean = false

    private val l = OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (IS_PREFS_INITIATED_KEY == key) {
            mIsPrefsInitiated = getBoolean(IS_PREFS_INITIATED_KEY, false)
        }

        if (mIsPrefsInitiated && mOnSharedPreferenceChangeListeners.isNotEmpty()) {
            mOnSharedPreferenceChangeListeners.forEach { it.onSharedPreferenceChanged(sharedPreferences, key) }
        }
    }
    private var mListenerRegistered: Boolean = false

    constructor(context: Context, authority: String, prefFileName: String) : super(context, authority, prefFileName)

    constructor(context: Context, authority: String, prefFileName: String, strictMode: Boolean) : super(
        context,
        authority,
        prefFileName,
        strictMode
    )

    private fun initListener() {
        mIsPrefsInitiated = super.getBoolean(IS_PREFS_INITIATED_KEY, false)
        super.registerOnSharedPreferenceChangeListener(l)
        mListenerRegistered = true
    }

    fun getSliderValues(key: String, defaultValue: Float = 100f): List<Float> {
        return try {
            val raw = getString(key, null)
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
                listOf(getFloat(key, defaultValue))
            } catch (_: Throwable) {
                try {
                    listOf(getInt(key, defaultValue.toInt()).toFloat())
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

    override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        if (!mListenerRegistered) {
            initListener()
        }
        mOnSharedPreferenceChangeListeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        mOnSharedPreferenceChangeListeners.remove(listener)
    }
}
