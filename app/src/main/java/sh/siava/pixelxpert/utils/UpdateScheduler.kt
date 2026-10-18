package sh.siava.pixelxpert.utils

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.PixelXpert

object UpdateScheduler {
    private const val UPDATE_WORK_NAME = "${BuildConfig.APPLICATION_ID}.UpdateSchedule"

    @JvmStatic
    fun scheduleUpdates(context: Context) {
        if (BuildConfig.DEBUG) {
            Log.d("Update Scheduler", "Updating update schedule...")
        }

        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }

        val workManager = WorkManager.getInstance(context)
        val prefs = PixelXpert.instance.defaultPreferences

        val autoUpdate = prefs.getBoolean("AutoUpdate", true)

        if (autoUpdate) {
            val builder = PeriodicWorkRequest.Builder(UpdateWorker::class.java, 12, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)

            workManager.enqueueUniquePeriodicWork(
                UPDATE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                builder.build()
            )
        } else {
            workManager.cancelUniqueWork(UPDATE_WORK_NAME)
        }
    }
}
