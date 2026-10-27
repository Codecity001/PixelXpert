package sh.siava.pixelxpert.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.data.PrefStore
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The `SyncNTPTime` switch's side effect, and the half of
 * [sh.siava.pixelxpert.receivers.BootReceiver] that goes with it.
 *
 * One unique work name with `TimeSyncInterval` as the period and the `UPDATE`
 * policy, so sliding the interval replaces the chain rather than adding to it;
 * LINEAR 1-hour backoff, and cancel-when-off. Reads the preference file directly
 * rather than through a [PrefStore] because all three callers - a switch write,
 * a slider commit, a BOOT_COMPLETED broadcast - are outside composition.
 *
 * With the switch off this is a cancel, so a device that never turned periodic
 * sync on has no work scheduled at all rather than a chain quietly failing.
 */
object TimeSyncScheduler {

	private const val TIME_SYNC_WORK_NAME = BuildConfig.APPLICATION_ID + ".TimeSyncSchedule"

	/** Fallback for an absent or unreadable `TimeSyncInterval`. */
	private const val DEFAULT_INTERVAL_HOURS = 24

	/** Enqueues or cancels the periodic query, per the switch and the slider. */
	fun scheduleTimeSync(context: Context) {
		if (BuildConfig.DEBUG) {
			Log.d("Time Scheduler", "Updating time sync schedule...")
		}

		if (!WorkManager.isInitialized()) {
			WorkManager.initialize(context, Configuration.Builder().build())
		}

		val workManager = WorkManager.getInstance(context)
		val prefs = PrefStore.open(context)

		val syncNTPTime = prefs.getBoolean("SyncNTPTime", false)
		val timeSyncInterval = sliderInt(prefs)

		if (syncNTPTime) {
			val request = PeriodicWorkRequest.Builder(
				TimeSyncWorker::class.java,
				timeSyncInterval.toLong(),
				TimeUnit.HOURS,
			)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
				.build()

			workManager.enqueueUniquePeriodicWork(
				TIME_SYNC_WORK_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				request,
			)
		} else {
			workManager.cancelUniqueWork(TIME_SYNC_WORK_NAME)
		}
	}

	/**
	 * The interval, in hours, whatever shape it happens to be stored in.
	 *
	 * The type under `TimeSyncInterval` is genuinely unknown: this app's
	 * single-handle slider writes a Float, an imported backup or the
	 * range-slider path writes `{"": [24]}` JSON, a raw write can be an Int,
	 * and an untouched key is absent. Reading the type once off `getAll()`
	 * gives the String-then-Float-then-Int chain a tolerant reader would
	 * walk, without the exception, since a mismatched shape is just a
	 * `null` there.
	 *
	 * The eight lines are duplicated from [PrefStore.getSliderInt] rather
	 * than calling it, which keeps this file reading the file directly in
	 * the manner of [UpdateScheduler] above. The regex is the one
	 * `PrefRenderer` already keeps for `sliderValues`, for the same reason
	 * it exists there: the JSON blob is the only shape that needs parsing
	 * rather than conversion.
	 */
	private fun sliderInt(prefs: SharedPreferences): Int {
		val raw = prefs.all["TimeSyncInterval"] ?: return DEFAULT_INTERVAL_HOURS

		val hours = when (raw) {
			is Number -> raw.toFloat()
			is String -> SLIDER_NUMBER.find(raw)?.value?.toFloatOrNull()
			else -> null
		} ?: return DEFAULT_INTERVAL_HOURS

		return hours.roundToInt()
	}

	/** Matches the number inside the `{"": [24]}` blob a range slider stores. */
	private val SLIDER_NUMBER = Regex("""-?\d*\.?\d+""")
}
