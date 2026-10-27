package sh.siava.pixelxpert

import android.content.Context
import android.content.Intent

/**
 * The two broadcast actions are string-keyed contracts with the Xposed side:
 * the module sends [ACTION_CHECK_XPOSED_ENABLED] to ask which packages are
 * hooked, and the hooks answer with [ACTION_XPOSED_CONFIRMED] naming each
 * one. Both are derived from the application id, so a module installed under
 * the same id keeps talking to old hook code.
 */
object Constants {
	const val APPLICATION_ID: String = BuildConfig.APPLICATION_ID

	/** Sent to ask the hooks which of the scoped packages they are in. */
	const val ACTION_CHECK_XPOSED_ENABLED: String =
		"$APPLICATION_ID.ACTION_CHECK_XPOSED_ENABLED"

	/** Answer, carrying the confirmed package in extra "packageName". */
	const val ACTION_XPOSED_CONFIRMED: String =
		"$APPLICATION_ID.ACTION_XPOSED_CONFIRMED"

	/**
	 * Hook Audit broadcast contracts:
	 * Sent to request a single-pass inspection from all active hooked processes.
	 */
	const val ACTION_REQUEST_HOOK_AUDIT: String =
		"$APPLICATION_ID.ACTION_REQUEST_HOOK_AUDIT"

	/**
	 * Answer to hook audit request, carrying the target package in "packageName"
	 * and serialized audit report JSON in "auditJson".
	 */
	const val ACTION_HOOK_AUDIT_RESULT: String =
		"$APPLICATION_ID.ACTION_HOOK_AUDIT_RESULT"

	const val SYSTEM_UI_PACKAGE: String = "com.android.systemui"
	const val SYSTEM_FRAMEWORK_PACKAGE: String = "android"

	// The rest of the scoped packages, all read by the Xposed side. Each one
	// backs a target annotation in xposed/annotations, so these are compile
	// time constants on purpose: annotation arguments must be.
	const val TELECOM_SERVER_PACKAGE: String = "com.android.server.telecom"
	const val LAUNCHER_PACKAGE: String = "com.google.android.apps.nexuslauncher"
	const val SETTINGS_PACKAGE: String = "com.android.settings"
	const val DIALER_PACKAGE: String = "com.google.android.dialer"

	/**
	 * The navigation gestures the gesture handler broadcasts instead of
	 * injecting key events, so a listener in the launcher can act on them
	 * without the framework seeing a synthetic back or home press.
	 */
	const val ACTION_HOME: String = "$APPLICATION_ID.ACTION_HOME"
	const val ACTION_BACK: String = "$APPLICATION_ID.ACTION_BACK"

	/** Screen-off request used by the face-up and pocket gestures. */
	const val ACTION_SLEEP: String = "$APPLICATION_ID.ACTION_SLEEP"

	/**
	 * App profile switching: the gesture asks for a switch, and the proxy
	 * answers which profiles are available for the current app.
	 */
	const val ACTION_SWITCH_APP_PROFILE: String = "$APPLICATION_ID.ACTION_SWITCH_APP_PROFILE"
	const val ACTION_PROFILE_SWITCH_AVAILABLE: String =
		"$APPLICATION_ID.ACTION_PROFILE_SWITCH_AVAILABLE"

	const val AI_METHOD_MLKIT: Int = 0

	const val AI_METHOD_PYTORCH: Int = 1

	/**
	 * Key prefix the Xposed side uses to count bootloop strikes per package.
	 *
	 * The strikes live in the same preferences file this app reads through
	 * [sh.siava.pixelxpert.data.PrefStore], so the Hooks page can report a
	 * bootlooped package without a separate channel.
	 */
	const val PACKAGE_STRIKE_KEY_KEY: String = "packageStrike_"

	/** Companion prefix for the load timestamp that resets the strike count. */
	const val LOAD_TIME_KEY_KEY: String = "packageLastLoad_"

	/**
	 * Preference keys the Xposed side must never broadcast an update for.
	 *
	 * Both are bootloop bookkeeping: relaying them would re-enter
	 * `loadEverything` for a change that only matters to
	 * [sh.siava.pixelxpert.xposed.utils.BootLoopProtector].
	 */
	val PREF_UPDATE_EXCLUSIONS: List<String> =
		listOf(LOAD_TIME_KEY_KEY, PACKAGE_STRIKE_KEY_KEY)

	// Update page. The channel id is the key the notifications are posted
	// under, and the two ids keep the "downloaded" and "failed" notifications
	// from overwriting each other.
	const val UPDATES_CHANNEL_ID: String = "Updates"
	const val UPDATE_AVAILABLE_ID: Int = 0
	const val UPDATE_DOWNLOADED_ID: Int = 1
	const val UPDATE_DOWNLOAD_FAILED_ID: Int = 2

	/**
	 * Preference holding a user-entered update manifest URL.
	 *
	 * It is the hidden counterpart of the endpoints above: tapping the
	 * installed-version line on the Updates page fifteen times opens a text
	 * box, and whatever is saved there replaces the channel's built-in URL -
	 * blank (or the key simply absent) means "use the shipped one". Both the
	 * Updates page and [sh.siava.pixelxpert.util.UpdateWorker] read this
	 * one key, so an on-screen check and a scheduled check can never
	 * disagree about what they fetched.
	 */
	const val KEY_UPDATE_JSON_URL: String = "updateJsonUrl"

	/** Magisk module identity. */
	const val MOD_NAME: String = "PixelXpert"
	const val MAGISK_UPDATE_DIR: String = "/data/adb/modules_update"
	const val MAGISK_MODULES_DIR: String = "/data/adb/modules"

	/**
	 * Where the lock screen wallpaper's subject mask is cached.
	 *
	 * [sh.siava.pixelxpert.xposed.modpacks.systemui.DepthWallpaper] writes
	 * it on every wallpaper change and checks its length to decide whether a
	 * re-segmentation is needed, so the path has to be stable across process
	 * restarts - hence the cache dir rather than a temp file.
	 */
	fun getLockScreenSubjectCachePath(context: Context): String = context.cacheDir.absolutePath + "/lswt.png"

	fun getLockScreenBitmapCachePath(context: Context): String = context.cacheDir.absolutePath + "/lsw.jpg"

	/**
	 * Broadcast announcing that the current app's profile should change.
	 *
	 * `FLAG_RECEIVER_FOREGROUND` is what keeps the switch from queuing behind
	 * whatever else the receiver is doing - a profile switch is a gesture
	 * response, and a late one reads as a dead gesture.
	 */
	fun getAppProfileSwitchIntent(): Intent = Intent()
		.setAction(ACTION_SWITCH_APP_PROFILE)
		.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
}
