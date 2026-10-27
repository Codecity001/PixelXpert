package sh.siava.pixelxpert.util

import android.content.Context
import com.lyft.kronos.AndroidClockFactory
import com.lyft.kronos.KronosClock
import com.lyft.kronos.SyncListener
import com.topjohnwu.superuser.Shell
import sh.siava.pixelxpert.data.PrefStore
import java.lang.Object
import java.util.Locale

/**
 * One NTP query, and the root command that applies the answer to the clock.
 *
 * The calling thread owns a monitor, Kronos queries on a thread of its own, and
 * the listener releases that monitor once it has either moved the clock or given
 * up. The tap on *"Sync now"* therefore freezes the main thread for the length
 * of the query.
 *
 * The monitor is a [java.lang.Object] rather than a `kotlin.Any`: `Any` has no
 * `wait`/`notify`, and rebuilding this around a `CountDownLatch` would turn a
 * line-for-line port into a rewrite. Both waits are bounded - Kronos's own
 * `DefaultParam.TIMEOUT_MS` is six seconds, so [resultListener] normally
 * wakes the caller well before [TIME_SYNC_TIMEOUT_MS] would, and [syncTimeNow]
 * catches whatever the wait throws rather than letting it escape.
 */
class NTPTimeSyncer(private val context: Context) {

	private val lock = Object()

	private var success = false

	/** Assigned in [init]; only ever read once a query has been started. */
	private lateinit var timeChecker: KronosClock

	private val resultListener = object : SyncListener {

		override fun onStartSync(host: String) {}

		override fun onSuccess(ticksDelta: Long, responseTimeMs: Long) {
			try {
				// `!!` unboxes the nullable answer the root command needs; a
				// null here throws on this thread either way, and the `finally`
				// below still notifies, so the waiter is never left stranded
				// holding a stale `success`.
				val currentNTPTime = timeChecker.getCurrentNtpTimeMs()!!
				Shell.cmd(
					"date -s @" + String.format(Locale.ROOT, "%.3f", currentNTPTime / 1000.0),
				).exec()
				success = true
			} finally {
				synchronized(lock) { lock.notify() }
			}
		}

		override fun onError(host: String, throwable: Throwable) {
			synchronized(lock) { lock.notify() }
		}
	}

	init {
		timeChecker = AndroidClockFactory.createKronosClock(context, resultListener, getNTPServers())
	}

	/**
	 * Queries on the calling thread and reports whether the clock moved.
	 *
	 * Blocks for at most [TIME_SYNC_TIMEOUT_MS], and returns whatever
	 * [resultListener] managed to write in that window - so a query that never
	 * answers is a `false`, and the caller toasts a failure rather than
	 * hanging.
	 */
	fun syncTimeNow(): Boolean {
		synchronized(lock) {
			success = false
			timeChecker.syncInBackground()

			try {
				lock.wait(TIME_SYNC_TIMEOUT_MS)
			} catch (ignored: Throwable) {
			}

			return success
		}
	}

	/**
	 * The `NTPServers` row split on `;`, with blanks dropped and a lone
	 * `time.nist.gov` standing in when nothing survives.
	 *
	 * The read default is `""`, not the `0.pool.ntp.org;1.pool.ntp.org`
	 * the row displays. That string is a display value the renderer falls
	 * back to while the key is absent; it is only written once the dialog
	 * is saved, so a device that has never edited the field syncs against
	 * NIST.
	 *
	 * Kotlin's `split` keeps the trailing empty element Java's `split` would
	 * discard, which is why the trim-and-drop runs here rather than only
	 * guarding the fallback: the two are then equivalent on every input.
	 */
	private fun getNTPServers(): List<String> {
		val prefs = PrefStore.open(context)

		val servers = (prefs.getString("NTPServers", "") ?: "")
			.split(";")
			.map { it.trim() }
			.filter { it.isNotEmpty() }

		return servers.ifEmpty { listOf("time.nist.gov") }
	}

	companion object {

		private const val TIME_SYNC_TIMEOUT_MS = 10_000L
	}
}
