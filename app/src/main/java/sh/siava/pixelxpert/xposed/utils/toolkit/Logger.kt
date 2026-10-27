package sh.siava.pixelxpert.xposed.utils.toolkit

import android.util.Log
import sh.siava.pixelxpert.xposed.XposedRuntime

/**
 * Single logging funnel for everything on the Xposed side.
 *
 * Lines go through the libxposed `XposedInterface.log` handle, which is the
 * only sink available to a module targeting API 102. Before the framework has
 * attached an interface, or while a retired generation is being torn down, it
 * falls back to logcat instead of losing the message.
 *
 * Members are `static` and
 * [sh.siava.pixelxpert.xposed.XposedModPack] *extends* this class, so call
 * sites across the mod packs read as a bare `log(...)`.
 */
open class Logger {

	companion object {
		@JvmField
		var TAG: String = "PixelXpert"

		/**
		 * Logs [text], and [t] when given, to the framework log.
		 *
		 * [text] is skipped when blank so [log]'s `t`-only form emits a single
		 * stack-trace entry rather than an empty line beside it.
		 */
		@JvmStatic
		@Synchronized
		@JvmOverloads
		fun log(text: String, t: Throwable? = null) {
			if (t == null) {
				XposedRuntime.log(Log.INFO, text)
			} else {
				XposedRuntime.log(Log.ERROR, text, t)
			}
		}

		@JvmStatic
		@Synchronized
		fun log(t: Throwable) {
			log("", t)
		}
	}
}
