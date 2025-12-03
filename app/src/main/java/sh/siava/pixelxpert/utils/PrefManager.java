package sh.siava.pixelxpert.utils;

import static sh.siava.pixelxpert.utils.ExtendedSharedPreferences.IS_PREFS_INITIATED_KEY;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;

import sh.siava.pixelxpert.BuildConfig;

public class PrefManager {
	private static final String TAG = "Pref Exporter";

	@SuppressWarnings("UnusedReturnValue")
	public static boolean exportPrefs(SharedPreferences preferences, final @NonNull OutputStream outputStream) {
		try (OutputStream out = outputStream; ObjectOutputStream objectOutputStream = new ObjectOutputStream(out)) {
			objectOutputStream.writeObject(preferences.getAll());
			return true;
		} catch (IOException e) {
			Log.e(TAG, "Error serializing preferences", BuildConfig.DEBUG ? e : null);
			return false;
		}
	}

	@SuppressWarnings({"UnusedReturnValue", "unchecked"})
	public static boolean importPath(SharedPreferences sharedPreferences, final @NonNull InputStream inputStream) {
		Map<String, Object> map;
		try (InputStream in = inputStream; ObjectInputStream objectInputStream = new ObjectInputStream(in)) {
			map = (Map<String, Object>) objectInputStream.readObject();
		} catch (Exception e) {
			Log.e(TAG, "Error deserializing preferences", BuildConfig.DEBUG ? e : null);
			return false;
		}

		SharedPreferences.Editor editor = sharedPreferences.edit();
		editor.clear();

		for (Map.Entry<String, Object> e : map.entrySet()) {
			String key = e.getKey();
			Object value = e.getValue();
			// Unfortunately, the editor only provides typed setters
			if (IS_PREFS_INITIATED_KEY.equals(key)) //we don't import this key
				continue;

			if (value instanceof Boolean) {
				editor.putBoolean(key, (Boolean) value);
			} else if (value instanceof String) {
				editor.putString(key, (String) value);
			} else if (value instanceof Integer) {
				editor.putInt(key, (Integer) value);
			} else if (value instanceof Float) {
				editor.putFloat(key, (Float) value);
			} else if (value instanceof Long) {
				editor.putLong(key, (Long) value);
			} else if (value instanceof Set) {
				editor.putStringSet(key, (Set<String>) value);
			} else {
				// We assume value is not null as SharedPreferences doesn't store nulls
				throw new IllegalArgumentException("Type " + (value == null ? "null" : value.getClass().getName()) + " is unknown");
			}
		}
		return editor.commit();
	}
}