package sh.siava.pixelxpert.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.MainActivity
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.data.PrefStore
import sh.siava.pixelxpert.receivers.NotificationActionHandler
import sh.siava.pixelxpert.ui.update.fetchUpdateInfo

/**
 * The scheduled update check behind the AutoUpdate switch.
 *
 * It extends [CoroutineWorker]: `fetchUpdateInfo` is already a suspend
 * function, so the check can simply be awaited.
 *
 * The channel is derived from [BuildConfig.VERSION_NAME] alone, so a
 * background run asks the same channel the screen would ask before anyone
 * touched it.
 */
class UpdateWorker(
	appContext: Context,
	params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

	override suspend fun doWork(): Result {
		val prefs = PrefStore.open(applicationContext)

		// Any network is acceptable when the user allows metered downloads,
		// otherwise the link must be unmetered. Failure to see a usable network
		// is a retry, not a give-up - the LINEAR 1-hour backoff re-checks once
		// the objection lifts.
		if (!goodNetwork(prefs.getBoolean(KEY_WIFI_ONLY, true))) {
			return Result.retry()
		}

		return try {
			checkForUpdates(prefs)
			Result.success()
		} catch (t: Throwable) {
			// Logged and swallowed rather than retried: the next 12-hour run
			// is the retry.
			Log.w(TAG, "scheduled update check failed", t)
			Result.success()
		}
	}

	private suspend fun checkForUpdates(prefs: SharedPreferences) {
		val canary = BuildConfig.VERSION_NAME.lowercase().contains("canary")
		val info = withContext(Dispatchers.IO) {
			// Honour the endpoint entered on the Updates page, so a
			// scheduled check looks where the user last pointed it rather
			// than always falling back to the shipped URL.
			fetchUpdateInfo(canary, prefs.getString(Constants.KEY_UPDATE_JSON_URL, null))
		}
		val latestCode = info.versionCode ?: return
		if (latestCode <= BuildConfig.VERSION_CODE) return

		// The bottom bar reads this key to badge itself - a direct write
		// rather than a LocalBroadcastManager.
		prefs.edit().putInt(KEY_LATEST_VERSION_CODE, latestCode).apply()

		// "Skip this update" records this version so it is not re-announced
		// on every run until a newer one appears.
		val ignored = prefs.getInt(KEY_UPDATE_VERSION_IGNORED, BuildConfig.VERSION_CODE)
		if (ignored == latestCode) return

		// Android 13 drops a notification posted without the runtime grant,
		// so it is requested through root before notifying, as the Update
		// screen does; without root this fails quietly and the notification
		// is simply not shown.
		runCatching {
			Shell.cmd("pm grant ${BuildConfig.APPLICATION_ID} android.permission.POST_NOTIFICATIONS")
				.exec()
		}

		notifyUpdate(latestCode)
	}

	private fun notifyUpdate(latestCode: Int) {
		val context = applicationContext

		// The app has a single activity, so the tap lands on it, where the
		// badge written above points at the Update tab.
		val contentIntent = PendingIntent.getActivity(
			context,
			0,
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			PendingIntent.FLAG_MUTABLE,
		)

		val skipIntent = PendingIntent.getBroadcast(
			context,
			1,
			Intent(context, NotificationActionHandler::class.java)
				.putExtra(KEY_UPDATE_VERSION_IGNORED, latestCode)
				.putExtra(EXTRA_NOTIFICATION_ID, Constants.UPDATE_AVAILABLE_ID),
			PendingIntent.FLAG_MUTABLE,
		)

		val notification = NotificationCompat.Builder(context, Constants.UPDATES_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_notification_foreground)
			.setContentTitle(context.getString(R.string.new_update_title))
			.setContentText(context.getString(R.string.new_update_desc))
			.setContentIntent(contentIntent)
			.addAction(0, context.getString(R.string.skip_this_update), skipIntent)
			.setOnlyAlertOnce(true)
			.setAutoCancel(true)
			.build()

		context.getSystemService(NotificationManager::class.java)
			?.notify(Constants.UPDATE_AVAILABLE_ID, notification)
	}

	private fun goodNetwork(wifiOnly: Boolean): Boolean {
		val manager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
			as ConnectivityManager
		val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)

		return capabilities != null &&
			(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !wifiOnly ||
				capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
	}

	companion object {
		private const val TAG = "UpdateWorker"

		// Two of these names double as intent extras.
		private const val KEY_WIFI_ONLY = "UpdateWifiOnly"
		private const val KEY_UPDATE_VERSION_IGNORED = "updateVersionIgnored"
		private const val KEY_LATEST_VERSION_CODE = "latestVersionCode"
		private const val EXTRA_NOTIFICATION_ID = "notificationId"
	}
}
