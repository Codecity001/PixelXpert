package sh.siava.pixelxpert.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.utils.TimeSyncScheduler
import sh.siava.pixelxpert.utils.UpdateScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            if (BuildConfig.DEBUG) {
                Log.d("BootReceiver", "Broadcast received: ${intent.action}")
            }
            UpdateScheduler.scheduleUpdates(context)
            TimeSyncScheduler.scheduleTimeSync(context)
        }
    }
}
