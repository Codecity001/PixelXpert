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

object TimeSyncScheduler {
    private const val TIME_SYNC_WORK_NAME = "${BuildConfig.APPLICATION_ID}.TimeSyncSchedule"

    @JvmStatic
    fun scheduleTimeSync(context: Context) {
        if (BuildConfig.DEBUG) {
            Log.d("Time Scheduler", "Updating time sync schedule...")
        }

        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }

        val workManager = WorkManager.getInstance(context)
        val prefs = PixelXpert.instance.defaultPreferences

        val syncNTPTime = prefs.getBoolean("SyncNTPTime", false)
        val timeSyncInterval = prefs.getSliderInt("TimeSyncInterval", 24)

        if (syncNTPTime) {
            val builder = PeriodicWorkRequest.Builder(TimeSyncWorker::class.java, timeSyncInterval.toLong(), TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)

            workManager.enqueueUniquePeriodicWork(
                TIME_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                builder.build()
            )
        } else {
            workManager.cancelUniqueWork(TIME_SYNC_WORK_NAME)
        }
    }
}
