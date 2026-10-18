package sh.siava.pixelxpert.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.utils.PXPreferences

class NotificationActionHandler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent != null) {
            val versionToIgnore = intent.getIntExtra("updateVersionIgnored", BuildConfig.VERSION_CODE)
            PXPreferences.putInt("updateVersionIgnored", versionToIgnore)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notificationId = intent.getIntExtra("notificationId", -1)
            if (notificationId != -1) {
                notificationManager?.cancel(notificationId)
            }

            Log.d("NotificationActionHandler", "Update version ignored: $versionToIgnore")
        }
    }
}
