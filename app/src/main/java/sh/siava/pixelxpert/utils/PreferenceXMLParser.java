package sh.siava.pixelxpert.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.graphics.Color;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.util.List;

import sh.siava.rangesliderpreference.RangeSliderPreference;

// This class is mostly made by Gemini. customized by Siavash
public class PreferenceXMLParser {

	private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
	private static final String APP_NAMESPACE = "http://schemas.android.com/apk/res-auto";
	private static final String TAG = "PreferenceXmlParser";
	public static final String KEY_HAS_SET_DEFAULT_VALUES = "_has_set_default_values";

	/**
	 * Reads default preference values from an XML resource and commits them to SharedPreferences
	 * if the keys do not already exist.
	 *
	 * @param context The application context.
	 * @param xmlResId The R.xml.ID of your preferences XML file.
	 * @param sharedPrefs The SharedPreferences instance to write to.
	 */
	@SuppressLint("ApplySharedPref")
	public static void setDefaultsFromXml(Context context, int xmlResId, SharedPreferences sharedPrefs) {

		boolean changed = false;
		SharedPreferences.Editor editor = sharedPrefs.edit();
		try (XmlResourceParser parser = context.getResources().getXml(xmlResId)) {
			int eventType = parser.getEventType();
			while (eventType != XmlPullParser.END_DOCUMENT) {
				if (eventType == XmlPullParser.START_TAG) {
					String tagName = parser.getName();

					String key = parser.getAttributeValue(ANDROID_NAMESPACE, "key");
					String defaultValueString = parser.getAttributeValue(ANDROID_NAMESPACE, "defaultValue");

					//if instead of android:, app: used
					if (key == null) {
						key = parser.getAttributeValue(APP_NAMESPACE, "key");
					}
					if (defaultValueString == null) {
						defaultValueString = parser.getAttributeValue(APP_NAMESPACE, "defaultValue");
					}

					if (key != null && defaultValueString != null) {
						if (!sharedPrefs.contains(key)) {
							Log.d(TAG, "Setting default for key: " + key + ", value: " + defaultValueString + ", tag: " + tagName);
							changed = true;

							try {
								if (tagName.contains("SwitchPreference") || tagName.contains("CheckBoxPreference")) {
									editor.putBoolean(key, Boolean.parseBoolean(defaultValueString));
								} else if (tagName.contains("EditTextPreference") || tagName.contains("ListPreference") || tagName.equals("Preference") || tagName.contains("TimePickerPreference")) {
									editor.putString(key, defaultValueString);
								} else if (tagName.contains("SeekBarPreference")) {
									editor.putInt(key, Integer.parseInt(defaultValueString));
								} else if (tagName.contains("RangeSliderPreference")) {
									RangeSliderPreference rangeSliderPreference = new RangeSliderPreference(context, Xml.asAttributeSet(parser));
									List<Float> values = RangeSliderPreference.getValues(sharedPrefs, key, rangeSliderPreference.getFirstValue());
									rangeSliderPreference.cleanupValues(values);
									RangeSliderPreference.setValues(sharedPrefs, key, values);
								} else if (tagName.contains("ColorPreference")) {
									editor.putInt(key, parseAndroidColorString(defaultValueString));
								}
								//
								//other future stuff come between this gap
								//
								else if (isInteger(defaultValueString)) { // Try parsing as int if generic
									editor.putInt(key, Integer.parseInt(defaultValueString));
								} else if (isFloat(defaultValueString)) { // Try parsing as float if generic
									editor.putFloat(key, Float.parseFloat(defaultValueString));
								} else if (isLong(defaultValueString)) { // Try parsing as long if generic
									editor.putLong(key, Long.parseLong(defaultValueString));
								}
							} catch (NumberFormatException e) {
								editor.putString(key, defaultValueString);
							}
						}
					}
				}
				eventType = parser.next();
			}
		} catch (Throwable ignored) {
		} finally {
			if (changed) {
				editor.putBoolean(KEY_HAS_SET_DEFAULT_VALUES, true);
				editor.commit(); // Perform the synchronous commit if any changes were made
			}
		}
	}

	private static boolean isInteger(String str) {
		try {
			Integer.parseInt(str);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static boolean isFloat(String str) {
		try {
			Float.parseFloat(str);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static boolean isLong(String str) {
		try {
			Long.parseLong(str);
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

		public static int parseAndroidColorString(String colorString) {
			if (colorString == null || colorString.trim().isEmpty()) {
				throw new IllegalArgumentException("Color string cannot be null or empty.");
			}

			colorString = colorString.trim();
			int argbValue;

			// 1. Check for common hexadecimal prefixes
			if (colorString.startsWith("0x")) {
				// It's likely a hexadecimal string with "0x" prefix
				String hexPart = colorString.substring(2); // Remove "0x"
				if (hexPart.isEmpty()) {
					throw new IllegalArgumentException("Invalid hexadecimal color string: " + colorString);
				}
				try {
					// Use Long.parseUnsignedLong for robustness with ARGB values
					argbValue = (int) Long.parseUnsignedLong(hexPart, 16);
					return argbValue;
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Invalid hexadecimal color format: " + colorString, e);
				}
			} else if (colorString.startsWith("#")) {
				// It's likely a hexadecimal string with "#" prefix
				// Android's Color.parseColor handles this directly and robustly
				try {
					return Color.parseColor(colorString);
				} catch (IllegalArgumentException e) {
					throw new IllegalArgumentException("Invalid hexadecimal color format (with #): " + colorString, e);
				}
			} else {
				// 2. No explicit hex prefix, try to parse as decimal first
				try {
					argbValue = Integer.parseInt(colorString);
					// If it's a decimal RGB (e.g., 16777215 for white), Android's Color
					// functions typically expect an ARGB int.
					// If the input decimal represents 0xRRGGBB, we need to ensure full alpha.
					// A common convention is that decimal inputs are RGB, so add full alpha.
					// However, if the decimal *could* be an ARGB value directly,
					// you might need a different heuristic.
					// For simplicity here, if it's a small decimal (like 0-16777215),
					// we'll assume it's RGB and make it opaque.
					// If the parsed decimal is already a full ARGB, this will still work.
					return argbValue; // Color class methods like argb/rgb take int
				} catch (NumberFormatException eDecimal) {
					// 3. If decimal parsing fails, it *might* still be a hex string without a prefix.
					// This is a common pattern for "AARRGGBB" or "RRGGBB" in config files.
					try {
						argbValue = (int) Long.parseUnsignedLong(colorString, 16);
						// If it's 6 digits (RRGGBB), Android's Color class has `rgb` method
						// If it's 8 digits (AARRGGBB), it's already an ARGB value.
						// Let's assume this `argbValue` is the final ARGB int.
						return argbValue;
					} catch (NumberFormatException eHexNoPrefix) {
						throw new IllegalArgumentException(
								"Could not parse color string as decimal or hexadecimal: " + colorString, eHexNoPrefix);
					}
				}
			}
		}
	}
