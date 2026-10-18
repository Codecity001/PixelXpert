package sh.siava.pixelxpert.utils

import android.content.SharedPreferences
import com.google.gson.Gson
import java.util.HashMap

object ThemePackMapping {
    const val DRAWABLE_MAPPING_KEY = "drawableMapping"
    const val TYPE_DRAWABLE = 0

    class OverlayID(
        @JvmField var packageName: String,
        @JvmField var resID: Int
    )

    class OverlayIDName(
        @JvmField var packageName: String,
        @JvmField var resName: String
    ) {
        override fun toString(): String {
            return Gson().toJson(this)
        }
    }

    class IDMapping : HashMap<Int, OverlayID>()

    class Mapping : HashMap<String, OverlayIDName>() {
        override fun toString(): String {
            return Gson().toJson(this)
        }

        companion object {
            @JvmStatic
            fun loadMapping(prefs: SharedPreferences, type: Int): Mapping {
                return when (type) {
                    TYPE_DRAWABLE -> fromJSONString(prefs.getString(DRAWABLE_MAPPING_KEY, ""))
                    else -> Mapping()
                }
            }

            @JvmStatic
            fun saveMapping(prefs: SharedPreferences, type: Int, mapping: Mapping) {
                when (type) {
                    TYPE_DRAWABLE -> prefs.edit().putString(DRAWABLE_MAPPING_KEY, mapping.toString()).apply()
                }
            }

            @JvmStatic
            private fun fromJSONString(jsonString: String?): Mapping {
                if (jsonString.isNullOrEmpty()) return Mapping()
                val mapping = runCatching { Gson().fromJson(jsonString, Mapping::class.java) }.getOrNull()
                return mapping ?: Mapping()
            }
        }

        fun add(resName: String, packageName: String, replaceResName: String) {
            put(resName, OverlayIDName(packageName, replaceResName))
        }

        fun replace(resName: String, packageName: String, replaceResName: String) {
            if (containsKey(resName)) {
                remove(resName)
                put(resName, OverlayIDName(packageName, replaceResName))
            }
        }

        fun isEnabled(resName: String, packageName: String, replaceResName: String): Boolean {
            val idName = get(resName) ?: return false
            return idName.packageName == packageName && idName.resName == replaceResName
        }
    }
}
