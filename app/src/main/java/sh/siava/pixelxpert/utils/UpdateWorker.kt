package sh.siava.pixelxpert.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.topjohnwu.superuser.Shell
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.PixelXpert
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.receivers.NotificationActionHandler
import sh.siava.pixelxpert.ui.activities.SettingsActivity

class UpdateWorker(
    private val mContext: Context,
    workerParams: WorkerParameters
) : ListenableWorker(mContext, workerParams) {

    companion object {
        private const val UPDATE_AVAILABLE_ID = 1001

        @JvmStatic
        fun showBadgeDrawable(context: Context, latestVersionCode: Int) {
            PXPreferences.putInt("latestVersionCode", latestVersionCode)
            val broadcastIntent = Intent("${BuildConfig.APPLICATION_ID}.UPDATE_CHECK").apply {
                putExtra("latestVersionCode", latestVersionCode)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent)
        }
    }

    override fun startWork(): ListenableFuture<Result> {
        val prefs = PixelXpert.instance.defaultPreferences
        val updateWifiOnly = prefs.getBoolean("UpdateWifiOnly", true)

        val connectivityManager = mContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)

        val isGoodNetwork = capabilities != null &&
                (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !updateWifiOnly ||
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))

        if (isGoodNetwork) {
            checkForUpdates()
        }

        return CallbackToFutureAdapter.getFuture { completer ->
            completer.set(if (isGoodNetwork) Result.success() else Result.retry())
            completer
        }
    }

    private fun checkForUpdates() {
        UpdateChecker.check { result ->
            try {
                val latestVersionCode = result["versionCode"] as? Int
                val currentVersionCode = BuildConfig.VERSION_CODE
                Shell.cmd("pm grant ${BuildConfig.APPLICATION_ID} android.permission.POST_NOTIFICATIONS").exec()

                if (latestVersionCode != null && latestVersionCode > currentVersionCode) {
                    showUpdateNotification(latestVersionCode)
                }
            } catch (e: Exception) {
                Log.e("PixelXpert", "Error while checking for updates", e)
            }
        }
    }

    private fun showUpdateNotification(latestVersionCode: Int) {
        showBadgeDrawable(mContext, latestVersionCode)

        val ignoredVersion = PXPreferences.getInt("updateVersionIgnored", BuildConfig.VERSION_CODE)
        if (latestVersionCode == ignoredVersion) {
            Log.d("UpdateWorker", "Update ignored for version: $latestVersionCode")
            return
        }

        val notificationIntent = Intent(mContext, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("newUpdate", true)
        }

        val pendingIntent = PendingIntent.getActivity(mContext, 0, notificationIntent, PendingIntent.FLAG_MUTABLE)

        val actionIntent = Intent(mContext, NotificationActionHandler::class.java).apply {
            putExtra("updateVersionIgnored", latestVersionCode)
            putExtra("notificationId", UPDATE_AVAILABLE_ID)
        }
        val actionPendingIntent = PendingIntent.getBroadcast(mContext, 1, actionIntent, PendingIntent.FLAG_MUTABLE)

        val notificationBuilder = NotificationCompat.Builder(mContext, mContext.getString(R.string.notification_channel_update))
            .setSmallIcon(R.drawable.ic_notification_foreground)
            .setContentTitle(mContext.getString(R.string.new_update_title))
            .setContentText(mContext.getString(R.string.new_update_desc))
            .setContentIntent(pendingIntent)
            .addAction(0, mContext.getString(R.string.skip_this_update), actionPendingIntent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        val notificationManager = mContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (notificationManager != null) {
            createChannel(notificationManager)
            notificationManager.notify(UPDATE_AVAILABLE_ID, notificationBuilder.build())
        }
    }

    private fun createChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            mContext.getString(R.string.notification_channel_update),
            mContext.getString(R.string.notification_channel_update),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = mContext.getString(R.string.notification_channel_update_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
