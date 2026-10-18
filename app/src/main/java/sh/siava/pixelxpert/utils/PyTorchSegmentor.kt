package sh.siava.pixelxpert.utils

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Pair
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.downloader.Error
import com.downloader.OnDownloadListener
import com.downloader.PRDownloader
import java.io.File
import java.util.HashSet
import java.util.Locale
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.R.string.download_channel_name

object PyTorchSegmentor {

    private const val TAG = "PyTorchSegmentor"
    private const val PYTORCH_LIB = "libpytorch_jni_lite.so"
    private const val LIB_BASE_URL = "https://github.com/Codecity001/PixelXpert/raw/refs/heads/canary/app/lib/"
    private const val MODEL_FILENAME = "u2net.ptl"
    private const val MODEL_BASE_URL = "https://github.com/Codecity001/PixelXpert/raw/refs/heads/canary/app/pytorchModel/"
    private const val DOWNLOAD_CHANNEL_ID = "download_channel"
    private const val ASSETS_DOWNLOADING_ID = 1002
    private val activeNotifications = HashSet<String>()

    @JvmStatic
    fun extractSubject(context: Context, input: Bitmap): Bitmap? {
        return try {
            if (!loadAssets(context)) return null
            val modelPath = modelPath(context)
            PyTorchBackgroundRemover(modelPath).removeBackground(input)
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun createNotificationChannel(context: Context) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(DOWNLOAD_CHANNEL_ID, context.getString(download_channel_name), IMPORTANCE_DEFAULT)
        )
    }

    /** Loads assets from web. Returns true if everything is pre-loaded and false if anything is still ongoing */
    @JvmStatic
    fun loadAssets(context: Context): Boolean {
        createNotificationChannel(context)
        val libLoaded = loadPyTorchLibrary(context)
        val modelLoaded = downloadAIModel(context)
        return libLoaded && modelLoaded
    }

    private fun downloadAIModel(context: Context): Boolean {
        val aiPath = modelPath(context)
        if (File(aiPath).exists()) return true

        val downloadURL = "$MODEL_BASE_URL/$MODEL_FILENAME"
        downloadFile(downloadURL, aiPath, "ai_model", context)
        return false
    }

    private fun downloadFile(downloadURL: String, destPath: String, notificationTag: String, context: Context) {
        if (!activeNotifications.contains(notificationTag)) {
            activeNotifications.add(notificationTag)

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val notificationPair = postNotification(context, notificationManager, notificationTag)
            val notificationBuilder = notificationPair.first
            val notification = arrayOf(notificationPair.second)

            try {
                val tempFile = File.createTempFile("DLTmp", "tmp")
                val parentDir = tempFile.parentFile ?: return false
                val lastUpdateTime = longArrayOf(0)

                PRDownloader
                    .download(downloadURL, parentDir.absolutePath, tempFile.name)
                    .build()
                    .setOnProgressListener { progress ->
                        val currentTime = SystemClock.elapsedRealtime()
                        if (currentTime - lastUpdateTime[0] >= 1000) {
                            val progressPercent = if (progress.totalBytes > 0) progress.currentBytes * 100 / progress.totalBytes else 0
                            val progressText = getProgressDisplayLine(progress.currentBytes, progress.totalBytes)

                            notificationBuilder
                                .setContentText("${context.getText(R.string.assets_download_body)}\n${context.getString(R.string.assets_download_size_body, progressText)}")
                                .setProgress(100, progressPercent.toInt(), false)

                            notification[0] = notificationBuilder.build()
                            notification[0].flags = notification[0].flags or Notification.FLAG_ONLY_ALERT_ONCE
                            notificationManager.notify(notificationTag, ASSETS_DOWNLOADING_ID, notification[0])
                            lastUpdateTime[0] = currentTime
                        }
                    }
                    .start(object : OnDownloadListener {
                        override fun onDownloadComplete() {
                            tempFile.renameTo(File(destPath))
                            removeNotification(context, notificationTag)
                            Log.i(TAG, "PRDownloader $notificationTag download completed successfully")

                            try {
                                val intent = Intent(BuildConfig.APPLICATION_ID + ".ACTION_MODEL_DOWNLOADED")
                                LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                            } catch (ignored: Throwable) {}
                        }

                        override fun onError(error: Error?) {
                            tempFile.delete()
                            removeNotification(context, notificationTag)

                            val logMessage = StringBuilder("PRDownloader $notificationTag download failed:\n")
                            if (error?.isConnectionError == true) {
                                logMessage.append(String.format(Locale.ENGLISH, "Connection Exception: %s\n", error.connectionException))
                            }
                            if (error?.isServerError == true) {
                                logMessage.append(String.format(Locale.ENGLISH, "Response Code: %d\nServer Error Message: %s\n", error.responseCode, error.serverErrorMessage))
                            }

                            Log.e(TAG, logMessage.toString().trim())
                        }
                    })
            } catch (throwable: Throwable) {
                Log.e(TAG, "downloadFile: ", throwable)
            }
        }
    }

    private fun postNotification(context: Context, notificationManager: NotificationManager, tag: String): Pair<NotificationCompat.Builder, Notification> {
        val notificationBuilder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_foreground)
            .setContentTitle(context.getText(R.string.assets_download_title))
            .setContentText(context.getText(R.string.assets_download_body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setProgress(100, 0, false)
            .setAutoCancel(false)
        val notification = notificationBuilder.build()

        notificationManager.notify(tag, ASSETS_DOWNLOADING_ID, notification)
        return Pair(notificationBuilder, notification)
    }

    private fun removeNotification(context: Context, tag: String) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(tag, ASSETS_DOWNLOADING_ID)
        activeNotifications.remove(tag)
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun loadPyTorchLibrary(context: Context): Boolean {
        val libPath = libPath(context)
        if (File(libPath).exists()) {
            try {
                System.loadLibrary("fbjni")
                System.load(libPath)
                return true
            } catch (throwable: Throwable) {
                Log.e(TAG, "loadPyTorchLibrary: ", throwable)
            }
        }

        downloadLibrary(context)
        return false
    }

    private fun downloadLibrary(context: Context) {
        val architecture = Build.SUPPORTED_ABIS[0]
        val downloadURL = "$LIB_BASE_URL$architecture/$PYTORCH_LIB"
        val libPath = libPath(context)

        downloadFile(downloadURL, libPath, "ai_lib", context)
    }

    private fun getProgressDisplayLine(currentBytes: Long, totalBytes: Long): String {
        return "${getBytesToMBString(currentBytes)} / ${getBytesToMBString(totalBytes)}"
    }

    private fun getBytesToMBString(bytes: Long): String {
        return String.format(Locale.ENGLISH, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    private fun libPath(context: Context): String {
        return "${context.filesDir}/$PYTORCH_LIB"
    }

    private fun modelPath(context: Context): String {
        return "${context.filesDir.absolutePath}/$MODEL_FILENAME"
    }
}
