package sh.siava.pixelxpert.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.util.TimeSyncScheduler
import sh.siava.pixelxpert.util.UpdateScheduler

/**
 * Puts the periodic work back in place after a reboot.
 *
 * WorkManager's own rescheduling is not enough here: unique work is only
 * re-registered when the row that owns it is touched, so a device that reboots
 * with AutoUpdate or SyncNTPTime on needs the same calls those rows make.
 *
 * Neither call is conditional on the switch it serves: each reads its own
 * switch off the preference file and cancels its work when it finds that
 * switch off, so boot costs one read per feature rather than a shared
 * pre-check that would have to know about both.
 */
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
