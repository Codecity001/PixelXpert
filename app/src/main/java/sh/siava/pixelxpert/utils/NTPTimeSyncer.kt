package sh.siava.pixelxpert.utils

import android.content.Context
import com.lyft.kronos.AndroidClockFactory
import com.lyft.kronos.KronosClock
import com.lyft.kronos.SyncListener
import com.topjohnwu.superuser.Shell
import java.text.DecimalFormat
import java.util.ArrayList
import sh.siava.pixelxpert.PixelXpert

class NTPTimeSyncer(private val mContext: Context) {

    companion object {
        const val TIME_SYNC_TIMEOUT = 10000L
    }

    private val lock = Object()
    private var timeChecker: KronosClock
    private var success = false

    private val resultListener = object : SyncListener {
        override fun onStartSync(s: String) {}

        override fun onSuccess(l: Long, l1: Long) {
            try {
                val currentNTPTime = timeChecker.getCurrentNtpTimeMs() ?: return
                Shell.cmd("date -s @" + DecimalFormat("#0.000").format(currentNTPTime / 1000.0)).exec()
                success = true
            } finally {
                synchronized(lock) {
                    lock.notify()
                }
            }
        }

        override fun onError(s: String, throwable: Throwable) {
            synchronized(lock) {
                lock.notify()
            }
        }
    }

    init {
        timeChecker = AndroidClockFactory.createKronosClock(mContext, resultListener, getNTPServers())
    }

    fun syncTimeNow(): Boolean {
        synchronized(lock) {
            success = false
            timeChecker.syncInBackground()

            try {
                lock.wait(TIME_SYNC_TIMEOUT)
            } catch (ignored: Throwable) {}

            return success
        }
    }

    private fun getNTPServers(): List<String> {
        val prefs = PixelXpert.instance.defaultPreferences
        val ntpServerString = prefs.getString("NTPServers", "") ?: ""
        val ntpServers = ntpServerString.split(";")

        val servers = ArrayList<String>()
        for (ntpServer in ntpServers) {
            val trimmed = ntpServer.trim()
            if (trimmed.isNotEmpty()) {
                servers.add(trimmed)
            }
        }

        if (servers.isEmpty()) {
            servers.add("time.nist.gov")
        }

        return servers
    }
}
