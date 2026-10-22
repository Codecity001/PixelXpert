package sh.siava.pixelxpert.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File
import sh.siava.pixelxpert.Constants

/**
 * Restarts tracked apps that started without the module.
 *
 * A hooked process reads the preferences provider as it starts, so the provider
 * records its pid. The boot receiver checks those pids early in boot; a running app whose main process never
 * reported, missed the module, it is killed so Android starts it again.
 */
object HooksMonitor {
	private const val TAG = "HooksMonitor"
	private const val PREFS = "hooks_monitor"
	private const val BOOT_ID = "boot_id"
	private const val CHECKED = "checked"
	private const val PIDS = "pids:"

	private const val SYSTEM_UI = "com.android.systemui"

	/** Watched packages, mapped to the process names used by pidof/killall. */
	private val TRACKED = mapOf(
		Constants.SYSTEM_UI_PACKAGE to SYSTEM_UI,
		Constants.LAUNCHER_PACKAGE to "com.google.android.apps.nexuslauncher",
		Constants.SETTINGS_PACKAGE to "com.android.settings",
		Constants.DIALER_PACKAGE to "com.google.android.dialer",
	)

	private val lock = Any()
	private var scheduled = false

	/** Called from the preferences provider, which hooked processes read. */
	fun onHookReported(context: Context, uid: Int, pid: Int) {
		val packageName = context.packageManager.getPackagesForUid(uid)
			.orEmpty()
			.firstOrNull { it in TRACKED }
			?: return

		val prefs = bootPrefs(context)
		synchronized(lock) {
			val key = PIDS + packageName
			val pids = prefs.getStringSet(key, null)?.toMutableSet() ?: mutableSetOf()
			if (!pids.add(pid.toString())) return
			prefs.edit().putStringSet(key, pids).apply()
		}
	}

	/** Schedules this boot's single check. Called by the boot receiver. */
	fun schedule(context: Context) {
		synchronized(lock) {
			if (scheduled) return
			scheduled = true
		}
		val app = context.applicationContext
		Thread({
			try {
				check(app)
			} finally {
				synchronized(lock) { scheduled = false }
			}
		}, "px-hooks-check").start()
	}

	private fun check(context: Context) {
		val prefs = bootPrefs(context)
		if (prefs.getBoolean(CHECKED, false)) return

		// Root is needed to see and kill other processes
		// If SysUI or root is not ready at LOCKED_BOOT_COMPLETED, run at BOOT_COMPLETED.
		if (!hasRoot()) return
		if (runningPid(SYSTEM_UI) == null) return

		val running = TRACKED.mapNotNull { (packageName, process) ->
			val pid = runningPid(process) ?: return@mapNotNull null
			Triple(packageName, process, pid)
		}

		val missed = synchronized(lock) {
			if (prefs.getBoolean(CHECKED, false)) return
			prefs.edit().putBoolean(CHECKED, true).commit()

			running.filter { (packageName, _, pid) ->
				val reported = prefs.getStringSet(PIDS + packageName, null).orEmpty()
				pid.toString() !in reported
			}.map { (_, process, pid) -> process to pid }
		}

		for ((process, pid) in missed) {
			Log.i(TAG, "restarting $process: pid $pid did not report")
			Shell.cmd("killall $process").exec()
		}
	}

	private fun runningPid(process: String): Int? = try {
		Shell.cmd("pidof $process").exec().out
			.firstOrNull { it.isNotBlank() }
			?.trim()
			?.split(Regex("\\s+"))
			?.firstOrNull()
			?.toIntOrNull()
	} catch (ignored: Throwable) {
		null
	}

	private fun hasRoot(): Boolean = try {
		Shell.cmd("id -u").exec().out.any { it.trim() == "0" }
	} catch (ignored: Throwable) {
		false
	}

	private fun bootPrefs(context: Context): SharedPreferences {
		val prefs = context.createDeviceProtectedStorageContext()
			.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

		val bootId = readBootId(context)
		synchronized(lock) {
			if (prefs.getString(BOOT_ID, null) != bootId) {
				prefs.edit().clear().putString(BOOT_ID, bootId).commit()
			}
		}
		return prefs
	}

	private fun readBootId(context: Context): String = try {
		File("/proc/sys/kernel/random/boot_id").readText().trim()
	} catch (ignored: Throwable) {
		Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toString()
	}
}
