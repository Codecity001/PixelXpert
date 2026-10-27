package sh.siava.pixelxpert.util

import com.topjohnwu.superuser.Shell

/**
 * The three Restart entries issue exactly these commands:
 *
 * | menu entry        | command                                         |
 * |-------------------|-------------------------------------------------|
 * | Restart SystemUI  | `killall com.android.systemui`                  |
 * | Soft reboot       | `kill $(pidof zygote)` + `kill $(pidof zygote64)`|
 * | Restart system    | `am start -a android.intent.action.REBOOT`      |
 *
 * All three go through libsu, which is already a declared dependency of
 * the app. `Shell.cmd` builds the default (root) shell lazily and
 * degrades to an unprivileged one on a device without root, in which case
 * the privileged commands fail quietly.
 */
object Restarter {

	/**
	 * Restart SystemUI. Returns whether the command was accepted, because
	 * this is also what import and reset call to make the hooks re-read the
	 * preferences, and callers want to know if nothing happened.
	 */
	fun restartSystemUI(): Boolean = Shell.cmd("killall com.android.systemui").exec().isSuccess

	/**
	 * Soft reboot: kill both zygote flavours so the Android runtime comes
	 * back up without a full reboot.
	 *
	 * Deliberately not `await`ed - the two kills are independent, so they
	 * are submitted rather than blocking on zygote's exit.
	 */
	fun softRestart() {
		Shell.cmd("kill $(pidof zygote)").submit()
		Shell.cmd("kill $(pidof zygote64)").submit()
	}

	/**
	 * Full restart, via an intent rather than a root `reboot`, so the
	 * platform's normal shutdown path runs.
	 */
	fun restartSystem() {
		Shell.cmd("am start -a android.intent.action.REBOOT").exec()
	}
}
