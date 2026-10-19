package sh.siava.pixelxpert.util

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.data.PrefStore

/**
 * The AutoUpdate switch's side effect, and the thing [sh.siava.pixelxpert.receivers.BootReceiver]
 * re-runs after a reboot.
 *
 * `ExistingPeriodicWorkPolicy.UPDATE` is what makes flipping the switch
 * twice not leave two chains running.
 *
 * Reads the preference straight off the file rather than through [PrefStore]
 * because both callers are outside composition - a switch write and a
 * BOOT_COMPLETED broadcast - and [PrefStore.FILE_NAME] is the same file, so
 * the read sees the value the switch just wrote.
 *
 * The `AutoUpdate` fallback is `false`, which is the row's own default and
 * therefore what the switch displays; falling back to `true` would make boot
 * and settings disagree whenever the key is absent, so the switch wins.
 */
object UpdateScheduler {

	private const val UPDATE_WORK_NAME = BuildConfig.APPLICATION_ID + ".UpdateSchedule"

	private const val INTERVAL_HOURS = 12L

	fun scheduleUpdates(context: Context) {
		if (!WorkManager.isInitialized()) {
			WorkManager.initialize(context, Configuration.Builder().build())
		}

		val workManager = WorkManager.getInstance(context)
		val prefs = PrefStore.open(context)
		val autoUpdate = prefs.getBoolean("AutoUpdate", false)

		if (autoUpdate) {
			val request = PeriodicWorkRequest.Builder(
				UpdateWorker::class.java,
				INTERVAL_HOURS,
				TimeUnit.HOURS,
			)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
				.build()

			workManager.enqueueUniquePeriodicWork(
				UPDATE_WORK_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				request,
			)
		} else {
			workManager.cancelUniqueWork(UPDATE_WORK_NAME)
		}
	}
}
