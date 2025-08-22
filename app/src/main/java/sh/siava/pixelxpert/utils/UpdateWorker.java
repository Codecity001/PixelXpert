package sh.siava.pixelxpert.utils;

import static sh.siava.pixelxpert.ui.Constants.UPDATE_AVAILABLE_ID;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;
import com.topjohnwu.superuser.Shell;

import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.PixelXpert;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.receivers.NotificationActionHandler;
import sh.siava.pixelxpert.ui.activities.SettingsActivity;
import sh.siava.pixelxpert.ui.fragments.UpdateFragment;

public class UpdateWorker extends ListenableWorker {
	final Context mContext;
	public UpdateWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
		super(appContext, workerParams);
		mContext = appContext;
	}
	@NonNull
	@Override
	public ListenableFuture<Result> startWork() {
		ExtendedSharedPreferences prefs = PixelXpert.get().getDefaultPreferences();

		boolean UpdateWifiOnly = prefs.getBoolean("UpdateWifiOnly", true);

		ConnectivityManager connectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());

		boolean isGoodNetwork = capabilities != null &&
				(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
						&& !UpdateWifiOnly
						|| capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));

		if(isGoodNetwork)
			checkForUpdates();

		return CallbackToFutureAdapter.getFuture(completer -> {
			completer.set(isGoodNetwork ? Result.success() : Result.retry());
			return completer;
		});
	}

	private void checkForUpdates() {
		new UpdateFragment.updateChecker(onCheckedCallback).start();
	}

	private void showUpdateNotification(int latestVersionCode) {
		showBadgeDrawable(mContext, latestVersionCode);

		int ignoredVersion = PXPreferences.getInt("updateVersionIgnored", BuildConfig.VERSION_CODE);
		if (latestVersionCode == ignoredVersion) {
			Log.d("UpdateWorker", "Update ignored for version: " + latestVersionCode);
			return;
		}

		Intent notificationIntent = new Intent(mContext, SettingsActivity.class);
		notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		notificationIntent.putExtra("newUpdate", true);

		PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, notificationIntent, PendingIntent.FLAG_MUTABLE);

		Intent actionIntent = new Intent(mContext, NotificationActionHandler.class);
		actionIntent.putExtra("updateVersionIgnored", latestVersionCode);
		actionIntent.putExtra("notificationId", UPDATE_AVAILABLE_ID);
		PendingIntent actionPendingIntent = PendingIntent.getBroadcast(mContext, 1, actionIntent, PendingIntent.FLAG_MUTABLE);

		NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(mContext, mContext.getString(R.string.notification_channel_update))
				.setSmallIcon(R.drawable.ic_notification_foreground)
				.setContentTitle(mContext.getString(R.string.new_update_title))
				.setContentText(mContext.getString(R.string.new_update_desc))
				.setContentIntent(pendingIntent)
				.addAction(0, mContext.getString(R.string.skip_this_update), actionPendingIntent)
				.setOnlyAlertOnce(true)
				.setAutoCancel(true);

		NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
		createChannel(notificationManager);
		notificationManager.notify(UPDATE_AVAILABLE_ID, notificationBuilder.build());
	}

	@SuppressWarnings("deprecation")
	public static void showBadgeDrawable(Context context, int latestVersionCode) {
		PXPreferences.putInt("latestVersionCode", latestVersionCode);
		Intent broadcastIntent = new Intent(BuildConfig.APPLICATION_ID + ".UPDATE_CHECK");
		broadcastIntent.putExtra("latestVersionCode", latestVersionCode);
		LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent);
	}

	public void createChannel(NotificationManager notificationManager) {
		NotificationChannel channel = new NotificationChannel(mContext.getString(R.string.notification_channel_update), mContext.getString(R.string.notification_channel_update), NotificationManager.IMPORTANCE_DEFAULT);
		channel.setDescription(mContext.getString(R.string.notification_channel_update_desc));
		notificationManager.createNotificationChannel(channel);
	}

	UpdateFragment.TaskDoneCallback onCheckedCallback = result -> {
		try {
			Integer latestVersionCode = (Integer) result.get("versionCode");
			int currentVersionCode = BuildConfig.VERSION_CODE;
			Shell.cmd(String.format("pm grant %s android.permission.POST_NOTIFICATIONS", BuildConfig.APPLICATION_ID)).exec();

			if (latestVersionCode != null && latestVersionCode > currentVersionCode) {
				showUpdateNotification(latestVersionCode);
			}
		} catch (Exception e) {
			Log.e("PixelXpert", "Error while checking for updates", e);
		}
	};
}
