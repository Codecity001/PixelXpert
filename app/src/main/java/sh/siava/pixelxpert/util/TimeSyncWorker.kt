package sh.siava.pixelxpert.util

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture

/**
 * The run [TimeSyncScheduler] schedules.
 *
 * Stays on [ListenableWorker] rather than the tidier `CoroutineWorker` style
 * [UpdateWorker] uses.
 * [CallbackToFutureAdapter] turns the blocking sync into the future WorkManager
 * waits on, so the worker's contract is satisfied by completing that future
 * rather than by returning.
 *
 * A sync that fails is a [Result.retry], not a give-up: the LINEAR 1-hour
 * backoff re-runs the query, which is why a device whose NTP is unreachable
 * retries on the next hour instead of going quiet until the next interval.
 * The blocking happens on WorkManager's own worker thread, never on the
 * main thread the tap in [sh.siava.pixelxpert.ui.prefs.syncNTP] uses.
 */
class TimeSyncWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {

	override fun startWork(): ListenableFuture<Result> = CallbackToFutureAdapter.getFuture { completer ->
		completer.set(
			if (NTPTimeSyncer(applicationContext).syncTimeNow()) {
				Result.success()
			} else {
				Result.retry()
			},
		)
		completer
	}
}
