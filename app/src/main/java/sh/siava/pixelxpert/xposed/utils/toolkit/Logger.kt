package sh.siava.pixelxpert.xposed.utils.toolkit

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Single logging funnel for everything on the Xposed side, with one
 * deliberate change: output goes through [XposedBridge] instead of the
 * libxposed `XposedInterface.log(priority, tag, text, throwable)` handle.
 * `XposedBridge.log` is the sink the framework gives a hook installed through
 * the legacy Xposed API, so lines land where the rest of this module's
 * tooling reads them.
 * It offers exactly two overloads, `log(String)` and `log(Throwable)`, which
 * is why [log] composes them rather than finding a combined form.
 *
 * `XposedBridge.log` is called qualified rather than with the static
 * `import de.robv.android.xposed.XposedBridge.log`: a companion member of the
 * same name and arity shadows an imported top-level `log`, so the imports and
 * the overloads of [log] would recurse into each other until the stack blew.
 *
 * Members are `static` and
 * [sh.siava.pixelxpert.xposed.XposedModPack] *extends* this class, so call
 * sites across the 52 mod packs read as a bare `log(...)`. Kotlin resolves an
 * inherited companion member the same way, so the members all live on the
 * companion and the subclass keeps the original spelling working - adding
 * instance forwarders on top would be a platform-declaration clash with
 * `@JvmStatic`.
 */
open class Logger {

	companion object {
		@JvmField
		var TAG: String = "PixelXpert Lsposed Module"

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
			try {
				if (t == null) {
					XposedBridge.log(text)
				} else {
					if (text.isNotEmpty()) {
						XposedBridge.log(text)
					}
					XposedBridge.log(t)
				}
			} catch (ignored: Throwable) {
				// XposedBridge has no live sink before the framework installs
				// its support classes, so fall back to logcat rather than lose
				// the line.
				if (t == null) {
					Log.w(TAG, text)
				} else {
					Log.e(TAG, text, t)
				}
			}
		}

		@JvmStatic
		@Synchronized
		fun log(t: Throwable) {
			log("", t)
		}
	}
}
