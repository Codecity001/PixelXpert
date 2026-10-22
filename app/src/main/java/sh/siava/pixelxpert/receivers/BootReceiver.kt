package sh.siava.pixelxpert.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.data.HooksMonitor
import sh.siava.pixelxpert.util.TimeSyncScheduler
import sh.siava.pixelxpert.util.UpdateScheduler

/**
 * Puts the periodic work back in place after a reboot, and runs the
 * check for tracked apps that started without the module.
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
		when (intent.action) {
			// Before unlock
			// an unhooked SystemUI is restarted behind lockscreen.
			Intent.ACTION_LOCKED_BOOT_COMPLETED -> HooksMonitor.schedule(context)

			Intent.ACTION_BOOT_COMPLETED -> {
				if (BuildConfig.DEBUG) {
					Log.d("BootReceiver", "Broadcast received: ${intent.action}")
				}

				UpdateScheduler.scheduleUpdates(context)
				TimeSyncScheduler.scheduleTimeSync(context)
				// Retries the check if it could not run before unlock.
				HooksMonitor.schedule(context)
			}
		}
	}
}
