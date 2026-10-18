package sh.siava.pixelxpert.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.XmlResourceParser
import android.graphics.Color
import android.util.Log
import org.xmlpull.v1.XmlPullParser

object PreferenceXMLParser {

    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val APP_NAMESPACE = "http://schemas.android.com/apk/res-auto"
    private const val TAG = "PreferenceXmlParser"
    const val KEY_HAS_SET_DEFAULT_VALUES = "_has_set_default_values"

    /**
     * Reads default preference values from an XML resource and commits them to SharedPreferences
     * if the keys do not already exist.
     */
    @SuppressLint("ApplySharedPref")
    @JvmStatic
    fun setDefaultsFromXml(context: Context, xmlResId: Int, sharedPrefs: SharedPreferences) {
        var changed = false
        val editor = sharedPrefs.edit()
        try {
            context.resources.getXml(xmlResId).use { parser ->
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name

                        var key = parser.getAttributeValue(ANDROID_NAMESPACE, "key")
                        var defaultValueString = parser.getAttributeValue(ANDROID_NAMESPACE, "defaultValue")

                        if (key == null) {
                            key = parser.getAttributeValue(APP_NAMESPACE, "key")
                        }
                        if (defaultValueString == null) {
                            defaultValueString = parser.getAttributeValue(APP_NAMESPACE, "defaultValue")
                        }

                        if (key != null && defaultValueString != null) {
                            if (!sharedPrefs.contains(key)) {
                                Log.d(TAG, "Setting default for key: $key, value: $defaultValueString, tag: $tagName")
                                changed = true

                                try {
                                    when {
                                        tagName.contains("SwitchPreference") || tagName.contains("CheckBoxPreference") -> {
                                            editor.putBoolean(key, defaultValueString.toBoolean())
                                        }
                                        tagName.contains("EditTextPreference") || tagName.contains("ListPreference") ||
                                                tagName == "Preference" || tagName.contains("TimePickerPreference") -> {
                                            editor.putString(key, defaultValueString)
                                        }
                                        tagName.contains("SeekBarPreference") -> {
                                            editor.putInt(key, defaultValueString.toInt())
                                        }
                                        tagName.contains("RangeSliderPreference") || tagName.contains("SliderPreference") -> {
                                            editor.putString(key, defaultValueString)
                                        }
                                        tagName.contains("ColorPreference") -> {
                                            editor.putInt(key, parseAndroidColorString(defaultValueString))
                                        }
                                        isInteger(defaultValueString) -> {
                                            editor.putInt(key, defaultValueString.toInt())
                                        }
                                        isFloat(defaultValueString) -> {
                                            editor.putFloat(key, defaultValueString.toFloat())
                                        }
                                        isLong(defaultValueString) -> {
                                            editor.putLong(key, defaultValueString.toLong())
                                        }
                                        else -> {
                                            editor.putString(key, defaultValueString)
                                        }
                                    }
                                } catch (e: NumberFormatException) {
                                    editor.putString(key, defaultValueString)
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (ignored: Throwable) {
        } finally {
            if (changed) {
                editor.putBoolean(KEY_HAS_SET_DEFAULT_VALUES, true)
                editor.commit()
            }
        }
    }

    private fun isInteger(str: String): Boolean = str.toIntOrNull() != null

    private fun isFloat(str: String): Boolean = str.toFloatOrNull() != null

    private fun isLong(str: String): Boolean = str.toLongOrNull() != null

    @JvmStatic
    fun parseAndroidColorString(colorString: String?): Int {
        if (colorString.isNullOrBlank()) {
            throw IllegalArgumentException("Color string cannot be null or empty.")
        }

        val trimmed = colorString.trim()

        return when {
            trimmed.startsWith("0x") -> {
                val hexPart = trimmed.substring(2)
                if (hexPart.isEmpty()) {
                    throw IllegalArgumentException("Invalid hexadecimal color string: $trimmed")
                }
                java.lang.Long.parseUnsignedLong(hexPart, 16).toInt()
            }
            trimmed.startsWith("#") -> {
                Color.parseColor(trimmed)
            }
            else -> {
                val asInt = trimmed.toIntOrNull()
                if (asInt != null) {
                    asInt
                } else {
                    try {
                        java.lang.Long.parseUnsignedLong(trimmed, 16).toInt()
                    } catch (eHexNoPrefix: NumberFormatException) {
                        throw IllegalArgumentException("Could not parse color string as decimal or hexadecimal: $trimmed", eHexNoPrefix)
                    }
                }
            }
        }
    }
}
