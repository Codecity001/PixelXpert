package sh.siava.pixelxpert.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.data.PrefStore

/**
 * The "Skip this update" action on the update notification: records that
 * version so it stops being announced, then dismisses the notification that
 * offered the choice.
 *
 * The key and the
 * `notificationId` extra are the same strings [sh.siava.pixelxpert.util.UpdateWorker]
 * writes, so the pair stays a contract rather than two coincidences - and
 * `updateVersionIgnored` is the same key the notification itself checks
 * before posting, which is what closes the loop.
 */
class NotificationActionHandler : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent?) {
		val versionToIgnore = intent
			?.getIntExtra(KEY_UPDATE_VERSION_IGNORED, BuildConfig.VERSION_CODE)
			?: return

		PrefStore.open(context)
			.edit()
			.putInt(KEY_UPDATE_VERSION_IGNORED, versionToIgnore)
			.apply()

		val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
		if (notificationId != -1) {
			context.getSystemService(NotificationManager::class.java)
				?.cancel(notificationId)
		}

		Log.d(TAG, "Update version ignored: $versionToIgnore")
	}

	companion object {
		private const val TAG = "NotificationActionHandler"
		private const val KEY_UPDATE_VERSION_IGNORED = "updateVersionIgnored"
		private const val EXTRA_NOTIFICATION_ID = "notificationId"
	}
}
