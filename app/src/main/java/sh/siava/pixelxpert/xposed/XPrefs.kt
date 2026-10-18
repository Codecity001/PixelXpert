package sh.siava.pixelxpert.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.utils.ExtendedRemotePreferences
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

@SuppressLint("StaticFieldLeak")
object XPrefs : Logger() {
    lateinit var prefs: SharedPreferences

    @JvmField
    var Xprefs: SharedPreferences? = null

    const val MagiskRoot: String = "/data/adb/modules/PixelXpert"

    var packageName: String = ""
        private set

    private val initLatch = CountDownLatch(1)
    private val listener = OnSharedPreferenceChangeListener { _, key ->
        loadEverything(packageName, key)
    }

    @JvmStatic
    fun init(context: Context) {
        packageName = context.packageName
        val remotePrefs = ExtendedRemotePreferences(
            context,
            BuildConfig.APPLICATION_ID,
            "${BuildConfig.APPLICATION_ID}_preferences",
            true
        )
        prefs = remotePrefs
        Xprefs = remotePrefs
        initLatch.countDown()
    }

    @JvmStatic
    fun init(context: Context, sharedPreferences: SharedPreferences) {
        packageName = context.packageName
        prefs = sharedPreferences
        Xprefs = sharedPreferences
        initLatch.countDown()
    }

    @JvmStatic
    fun awaitInitialization(timeoutMs: Long = 3000): Boolean {
        return try {
            initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
    }

    @JvmStatic
    fun onContentProviderLoaded() {
        loadEverything(packageName)
        if (::prefs.isInitialized) {
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    @JvmStatic
    fun loadEverything(pkgName: String, vararg keys: String?) {
        if (keys.isNotEmpty()) {
            val key = keys[0]
            if (key == null || Constants.PREF_UPDATE_EXCLUSIONS.any { key.startsWith(it) }) {
                return
            }
        }

        setPackagePrefs(pkgName)

        for (thisMod in XPLauncher.runningMods) {
            runCatching {
                val nonNullKeys = keys.filterNotNull().toTypedArray()
                thisMod.onPreferenceUpdated(*nonNullKeys)
            }.onFailure { t ->
                Logger.log(t)
            }
        }
    }

    @JvmStatic
    fun edit(): SharedPreferences.Editor {
        return prefs.edit()
    }

    @JvmStatic
    fun setPackagePrefs(packageName: String) {
    }

    @JvmStatic
    fun getBoolean(key: String, defValue: Boolean = false): Boolean {
        return if (::prefs.isInitialized) prefs.getBoolean(key, defValue) else defValue
    }

    @JvmStatic
    fun getInt(key: String, defValue: Int = 0): Int {
        return if (::prefs.isInitialized) prefs.getInt(key, defValue) else defValue
    }

    @JvmStatic
    fun getFloat(key: String, defValue: Float = 0f): Float {
        return if (::prefs.isInitialized) prefs.getFloat(key, defValue) else defValue
    }

    @JvmStatic
    fun getLong(key: String, defValue: Long = 0L): Long {
        return if (::prefs.isInitialized) prefs.getLong(key, defValue) else defValue
    }

    @JvmStatic
    fun getString(key: String, defValue: String = ""): String {
        return if (::prefs.isInitialized) prefs.getString(key, defValue) ?: defValue else defValue
    }

    @JvmStatic
    fun getAll(): Map<String, *> {
        return if (::prefs.isInitialized) prefs.all ?: emptyMap<String, Any>() else emptyMap<String, Any>()
    }

    @JvmStatic
    fun getSliderValues(key: String, defaultValue: Float = 100f): List<Float> {
        if (!::prefs.isInitialized) return listOf(defaultValue)
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

    @JvmStatic
    fun getSliderFloat(key: String, defaultValue: Float = 0f): Float {
        return getSliderValues(key, defaultValue).firstOrNull() ?: defaultValue
    }

    @JvmStatic
    fun getSliderInt(key: String, defaultValue: Int = 0): Int {
        return Math.round(getSliderFloat(key, defaultValue.toFloat()))
    }
}
