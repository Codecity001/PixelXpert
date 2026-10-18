package sh.siava.pixelxpert.xposed.utils

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.usage.NetworkStats.Bucket
import android.app.usage.NetworkStatsManager.UsageCallback
import android.net.ConnectivityManager
import android.net.ConnectivityManager.TYPE_MOBILE
import android.net.ConnectivityManager.TYPE_WIFI
import android.net.wifi.WifiManager.UNKNOWN_SSID
import android.text.SpannableStringBuilder
import com.github.mfathi91.time.PersianDate
import java.awt.font.NumericShaper
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Calendar.DAY_OF_MONTH
import java.util.Calendar.DAY_OF_WEEK
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.regex.Pattern
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.xposed.modpacks.systemui.ThermalProvider
import sh.siava.pixelxpert.xposed.modpacks.systemui.ThermalProvider.BATTERY
import sh.siava.pixelxpert.xposed.modpacks.systemui.ThermalProvider.CPU
import sh.siava.pixelxpert.xposed.modpacks.systemui.ThermalProvider.GPU
import sh.siava.pixelxpert.xposed.modpacks.systemui.ThermalProvider.SKIN
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools

@Suppress("DEPRECATION")
class StringFormatter {

    companion object {
        const val NET_STAT_TYPE_DAY = 0
        const val NET_STAT_TYPE_WEEK = 1
        const val NET_STAT_TYPE_MONTH = 2

        private const val TYPE_RX = 1 shl 1
        private const val TYPE_TX = 1 shl 2
        private val instances = ArrayList<StringFormatter>()

        private const val NETWORK_STATS_NONE = 0
        private const val NETWORK_STATS_CELL = 1 shl 1
        private const val NETWORK_STATS_WIFI = 1 shl 2
        private const val NETSTAT_THRESHOLD_BYTES = 5L * 1024 * 1024 // 5MB

        @JvmField
        var RXColor: Int? = null
        @JvmField
        var TXColor: Int? = null
        @JvmField
        var NetStatsDayOf: Int = Calendar.THURSDAY
        @JvmField
        var NetStatsStartTime: LocalTime = LocalTime.of(0, 0)
        @JvmField
        var NetStatStartBase: Int = NET_STAT_TYPE_DAY

        @JvmStatic
        fun refreshAll() {
            instances.forEach { it.informCallbacks() }
        }
    }

    private val callbacks = ArrayList<FormattedStringCallback>()
    private var hasDate = false
    private var scheduler = Timer()

    private var ssidCallbackRegistered = false
    private var hasSSID = false

    private var mNetworkStatsType = NETWORK_STATS_NONE
    private var mRegisteredCallbackType = NETWORK_STATS_NONE

    private val networkUsageCallback = object : UsageCallback() {
        override fun onThresholdReached(i: Int, s: String?) {
            informCallbacks()
        }
    }

    private val ssidChangeCallback = ConnectivityManager.OnNetworkActiveListener {
        informCallbacks()
    }

    init {
        instances.add(this)
        scheduleNextDateUpdate()
    }

    private fun informCallbacks() {
        for (callback in callbacks) {
            callback.onRefreshNeeded()
        }
    }

    private fun scheduleNextDateUpdate() {
        try {
            val alarmManager = SystemUtils.AlarmManager() ?: return

            val alarmTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                add(Calendar.DATE, 1)
            }

            alarmManager.set(
                AlarmManager.RTC,
                alarmTime.timeInMillis,
                "",
                {
                    scheduleNextDateUpdate()
                    if (hasDate) {
                        informCallbacks()
                    }
                },
                null
            )
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                Logger.log("Error setting formatted string update schedule")
                Logger.log(t)
            }
        }
    }

    fun formatString(input: String): CharSequence {
        val result = SpannableStringBuilder(input)
        hasDate = false
        hasSSID = false
        mNetworkStatsType = NETWORK_STATS_NONE
        val pattern = Pattern.compile("\\$((T[a-zA-Z][0-9]*)|([A-Z][A-Za-z]+))")

        val matcher = pattern.matcher(input)
        while (matcher.find()) {
            val match = matcher.group(1) ?: continue
            val start = result.toString().indexOf("$$match")
            if (start >= 0) {
                result.replace(start, start + match.length + 1, valueOf(match))
            }
        }
        if (mNetworkStatsType != NETWORK_STATS_NONE) {
            registerUsageCallback()
        }

        ensureSSIDCallback()
        return result
    }

    private fun ensureSSIDCallback() {
        val cm = SystemUtils.ConnectivityManager() ?: return
        if (hasSSID == ssidCallbackRegistered) return

        if (hasSSID) {
            cm.addDefaultNetworkActiveListener(ssidChangeCallback)
        } else {
            cm.removeDefaultNetworkActiveListener(ssidChangeCallback)
        }
        ssidCallbackRegistered = hasSSID
    }

    private fun registerUsageCallback() {
        val nsm = SystemUtils.NetworkStatsManager() ?: return
        if (mRegisteredCallbackType != mNetworkStatsType) {
            unregisterUsageCallback()
            try {
                nsm.registerUsageCallback(TYPE_MOBILE, null, NETSTAT_THRESHOLD_BYTES, networkUsageCallback)
                mRegisteredCallbackType = mRegisteredCallbackType or NETWORK_STATS_CELL
                if ((mNetworkStatsType and NETWORK_STATS_WIFI) != 0) {
                    nsm.registerUsageCallback(TYPE_WIFI, null, NETSTAT_THRESHOLD_BYTES, networkUsageCallback)
                    mRegisteredCallbackType = mRegisteredCallbackType or NETWORK_STATS_WIFI
                }
            } catch (ignored: Throwable) {}
        }
    }

    private fun unregisterUsageCallback() {
        try {
            SystemUtils.NetworkStatsManager()?.unregisterUsageCallback(networkUsageCallback)
            mRegisteredCallbackType = NETWORK_STATS_NONE
        } catch (ignored: Throwable) {}
    }

    private fun valueOf(match: String): CharSequence {
        if (match.isEmpty()) return "$$match"
        return when (match.substring(0, 1)) {
            "G" -> georgianDateOf(match.substring(1))
            "H" -> hijrahDateOf(match.substring(1))
            "P" -> persianDateOf(match.substring(1))
            "N" -> networkStatOf(match.substring(1))
            "T" -> temperatureOf(match.substring(1))
            else -> "$$match"
        }
    }

    private fun networkStatOf(variable: String): CharSequence {
        var traffic = 0L
        var textColor: Int? = null
        val lower = variable.lowercase()
        var transformed: CharSequence? = null

        try {
            when (lower) {
                "crx", "rx" -> {
                    textColor = RXColor
                    traffic = getTrafficStats(TYPE_RX, !lower.startsWith("c"))
                }
                "ctx", "tx" -> {
                    textColor = TXColor
                    traffic = getTrafficStats(TYPE_TX, !lower.startsWith("c"))
                }
                "call", "all" -> {
                    traffic = getTrafficStats(TYPE_RX or TYPE_TX, !lower.startsWith("c"))
                }
                "ssid" -> {
                    transformed = fetchCurrentWifiSSID()
                    hasSSID = true
                }
            }

            if (transformed == null) {
                transformed = ObjectTools.getHumanizedBytes(traffic, false, 0.6f, "", "", textColor)
            }
            return transformed
        } catch (ignored: Exception) {
            return "\$N$variable"
        }
    }

    private fun getStartTime(): Long {
        return when (NetStatStartBase) {
            NET_STAT_TYPE_DAY -> getStartTime(NetStatsStartTime)
            NET_STAT_TYPE_WEEK -> getStartTime(DAY_OF_WEEK, NetStatsDayOf)
            NET_STAT_TYPE_MONTH -> getStartTime(DAY_OF_MONTH, NetStatsDayOf)
            else -> -1
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentWifiSSID(): String {
        val wm = SystemUtils.WifiManager() ?: return ""
        var theSsid = wm.connectionInfo?.ssid ?: return ""
        if (theSsid.startsWith("\"") && theSsid.endsWith("\"") && theSsid.length >= 2) {
            theSsid = theSsid.substring(1, theSsid.length - 1)
        }
        if (UNKNOWN_SSID != theSsid) {
            return theSsid
        }
        return ""
    }

    private fun getTrafficStats(type: Int, includeWiFi: Boolean): Long {
        val startTime = getStartTime()
        if (startTime < 0) return 0

        var ret = 0L
        val nsm = SystemUtils.NetworkStatsManager() ?: return 0
        try {
            var bucket: Bucket? = nsm.querySummaryForDevice(
                TYPE_MOBILE,
                null,
                startTime,
                Calendar.getInstance().timeInMillis
            )
            if (bucket != null) {
                ret += getTraffic(bucket, type)
            }

            if (includeWiFi) {
                mNetworkStatsType = mNetworkStatsType or NETWORK_STATS_WIFI
                bucket = nsm.querySummaryForDevice(
                    TYPE_WIFI,
                    null,
                    startTime,
                    Calendar.getInstance().timeInMillis
                )
                if (bucket != null) {
                    ret += getTraffic(bucket, type)
                }
            }
        } catch (ignored: Throwable) {
            ret = 0
        }
        mNetworkStatsType = mNetworkStatsType or NETWORK_STATS_CELL
        return ret
    }

    private fun getTraffic(bucket: Bucket, type: Int): Long {
        var ret = 0L
        if ((type and TYPE_RX) != 0) {
            ret += bucket.rxBytes
        }
        if ((type and TYPE_TX) != 0) {
            ret += bucket.txBytes
        }
        return ret
    }

    private fun getStartTime(timeOfDay: LocalTime): Long {
        val startTimeCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, timeOfDay.hour)
            set(Calendar.MINUTE, timeOfDay.minute)
        }
        if (startTimeCalendar.after(Calendar.getInstance())) {
            return -1
        }
        return startTimeCalendar.time.time
    }

    private fun getStartTime(dayType: Int, dayOf: Int): Long {
        val startTimeCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
        }

        when (dayType) {
            DAY_OF_MONTH -> {
                startTimeCalendar.set(DAY_OF_MONTH, dayOf)
                if (startTimeCalendar.after(Calendar.getInstance())) {
                    startTimeCalendar.add(Calendar.MONTH, -1)
                }
            }
            DAY_OF_WEEK -> {
                startTimeCalendar.set(DAY_OF_WEEK, dayOf)
                if (startTimeCalendar.after(Calendar.getInstance())) {
                    startTimeCalendar.add(Calendar.DATE, -7)
                }
            }
            else -> return Calendar.getInstance().time.time
        }

        return startTimeCalendar.time.time
    }

    private fun georgianDateOf(format: String): CharSequence {
        return try {
            @SuppressLint("SimpleDateFormat")
            val result = SimpleDateFormat(format).format(Calendar.getInstance().time)
            hasDate = true
            result
        } catch (ignored: Exception) {
            "\$G$format"
        }
    }

    private fun temperatureOf(format: String): CharSequence {
        return try {
            val match = Pattern.compile("^([A-Za-z])([0-9]*)$").matcher(format)
            if (!match.find()) {
                throw Exception()
            }
            val typeStr = match.group(1) ?: throw Exception()

            val nextUpdateSec = try {
                val group2 = match.group(2)
                if (!group2.isNullOrEmpty()) group2.toInt() else 60
            } catch (ignored: Throwable) {
                60
            }
            val nextUpdate = nextUpdateSec * 1000L

            val type = when (typeStr.lowercase()) {
                "b" -> BATTERY
                "c" -> CPU
                "g" -> GPU
                "s" -> SKIN
                else -> throw Exception()
            }

            val temperature = ThermalProvider.getTemperatureMaxInt(type)
            if (temperature < -990) {
                scheduleUpdate(1000L)
                return "Err"
            }

            scheduleUpdate(nextUpdate)
            temperature.toString()
        } catch (ignored: Exception) {
            "\$T$format"
        }
    }

    private fun scheduleUpdate(nextUpdate: Long) {
        scheduler.cancel()
        scheduler = Timer()
        scheduler.schedule(object : TimerTask() {
            override fun run() {
                informCallbacks()
            }
        }, nextUpdate)
    }

    private fun hijrahDateOf(format: String): CharSequence {
        return try {
            val result = HijrahDate.from(LocalDate.now()).format(
                DateTimeFormatter.ofPattern(format, Locale.forLanguageTag("ar"))
            )
            hasDate = true
            val bytes = result.toCharArray()
            NumericShaper.getShaper(NumericShaper.ARABIC).shape(bytes, 0, bytes.size)
            String(bytes)
        } catch (ignored: Exception) {
            "\$H$format"
        }
    }

    private fun persianDateOf(format: String): CharSequence {
        return try {
            val result = PersianDate.now().format(
                DateTimeFormatter.ofPattern(format, Locale.forLanguageTag("fa"))
            )
            hasDate = true
            val bytes = result.toCharArray()
            NumericShaper.getShaper(NumericShaper.EASTERN_ARABIC).shape(bytes, 0, bytes.size)
            String(bytes)
        } catch (ignored: Exception) {
            "\$P$format"
        }
    }

    fun registerCallback(callback: FormattedStringCallback) {
        callbacks.add(callback)
    }

    @Suppress("unused")
    fun unRegisterCallback(callback: FormattedStringCallback) {
        callbacks.remove(callback)
    }

    @Suppress("unused")
    fun resetCallbacks() {
        callbacks.clear()
    }

    fun interface FormattedStringCallback {
        fun onRefreshNeeded()
    }
}
