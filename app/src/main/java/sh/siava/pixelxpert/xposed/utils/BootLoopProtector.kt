package sh.siava.pixelxpert.xposed.utils

import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import java.util.Calendar

/**
 * Guards against a package that crashes on every load by counting strikes in
 * the shared preferences and refusing to load once there are three in a row
 * inside a minute.
 *
 * The two key prefixes are also referenced by [sh.siava.pixelxpert.Constants.PREF_UPDATE_EXCLUSIONS],
 * which is why the Xposed side must never broadcast an update for them.
 */
object BootLoopProtector {
	const val LOAD_TIME_KEY_KEY: String = "packageLastLoad_"
	const val PACKAGE_STRIKE_KEY_KEY: String = "packageStrike_"

	private const val COUNTER_RESET_PERIOD = 60 // seconds

	@JvmStatic
	fun isBootLooped(packageName: String): Boolean {
		val loadTimeKey = "$LOAD_TIME_KEY_KEY$packageName"
		val strikeKey = "$PACKAGE_STRIKE_KEY_KEY$packageName"
		val currentTime = Calendar.getInstance().time.time
		val lastLoadTime = Xprefs.getLong(loadTimeKey, 0)
		val strikeCount = Xprefs.getInt(strikeKey, 0)

		return if (currentTime - lastLoadTime > COUNTER_RESET_PERIOD * 1000) {
			resetCounter(packageName)
			false
		} else if (strikeCount >= 3) {
			true
		} else {
			// commit(), not apply(): the next line of code reads this back, and
			// a bootloop that loses the write would never be caught.
			@Suppress("ApplySharedPref")
			Xprefs.edit().putInt(strikeKey, strikeCount + 1).commit()
			false
		}
	}

	@JvmStatic
	fun resetCounter(packageName: String) {
		try {
			val loadTimeKey = "$LOAD_TIME_KEY_KEY$packageName"
			val strikeKey = "$PACKAGE_STRIKE_KEY_KEY$packageName"
			val editor = Xprefs.edit()
			editor.remove(strikeKey)
			editor.putLong(loadTimeKey, Calendar.getInstance().time.time)
			@Suppress("ApplySharedPref")
			editor.commit()
		} catch (ignored: Throwable) {
		}
	}
}
