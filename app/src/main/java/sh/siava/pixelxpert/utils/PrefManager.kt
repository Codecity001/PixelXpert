package sh.siava.pixelxpert.utils

import android.content.SharedPreferences
import android.util.Log
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.utils.ExtendedSharedPreferences.Companion.IS_PREFS_INITIATED_KEY

object PrefManager {
    private const val TAG = "Pref Exporter"

    @JvmStatic
    fun exportPrefs(preferences: SharedPreferences, outputStream: OutputStream): Boolean {
        return try {
            outputStream.use { out ->
                ObjectOutputStream(out).use { objectOutputStream ->
                    objectOutputStream.writeObject(preferences.all)
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing preferences", if (BuildConfig.DEBUG) e else null)
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun importPath(sharedPreferences: SharedPreferences, inputStream: InputStream): Boolean {
        val map: Map<String, Any?> = try {
            inputStream.use { stream ->
                ObjectInputStream(stream).use { objectInputStream ->
                    objectInputStream.readObject() as Map<String, Any?>
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing preferences", if (BuildConfig.DEBUG) e else null)
            return false
        }

        val editor = sharedPreferences.edit()
        editor.clear()

        for ((key, value) in map) {
            if (IS_PREFS_INITIATED_KEY == key) continue

            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Long -> editor.putLong(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                else -> {
                    if (value != null) {
                        Log.w(TAG, "Type ${value::class.java.name} is unknown for key $key")
                    }
                }
            }
        }
        return editor.commit()
    }
}
