package sh.siava.pixelxpert.xposed.utils

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.usage.NetworkStats.Bucket
import android.app.usage.NetworkStatsManager.UsageCallback
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import com.github.mfathi91.time.PersianDate
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.xposed.modpacks.systemui.ThermalProvider
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools
import java.awt.font.NumericShaper
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

/**
 * Resolves the `$` variables a user can place in clock, carrier, keyguard or
 * status-bar strings (Georgian, Hijrah and Persian dates, thermal readings,
 * network traffic counters and the current SSID) into ready-to-display text,
 * consulting the module preferences for colours, the network-stats window
 * start and the temperature poll interval, and waking registered callbacks
 * whenever any of those values can have changed - midnight rollover, network
 * usage thresholds, SSID changes and temperature poll timers alike.
 *
 * The nested [FormattedStringCallback] interface keeps its name and
 * position because other packs import it as
 * `StringFormatter.FormattedStringCallback`.
 */
@Suppress("DEPRECATION")
class StringFormatter {
	private val callbacks = ArrayList<FormattedStringCallback>()
	private var hasDate = false
	private val handler = Handler(Looper.getMainLooper())
	private val scheduledUpdate = Runnable { informCallbacks() }

	private var alarmManager: AlarmManager? = null
	private val dateAlarmListener =
		AlarmManager.OnAlarmListener {
			scheduleNextDateUpdate()
			if (hasDate) {
				informCallbacks()
			}
		}

	private var ssidCallbackRegistered = false
	private var hasSSID = false

	private var mNetworkStatsType = NETWORK_STATS_NONE
	private var mRegisteredCallbackType = NETWORK_STATS_NONE
	private val networkUsageCallback: UsageCallback = object : UsageCallback() {
		override fun onThresholdReached(i: Int, s: String?) {
			informCallbacks()
		}
	}
	private val ssidChangeCallback = ConnectivityManager.OnNetworkActiveListener { informCallbacks() }

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

		@JvmStatic
		var RXColor: Int? = null

		@JvmStatic
		var TXColor: Int? = null

		@JvmStatic
		var NetStatsDayOf: Int = Calendar.THURSDAY

		@JvmStatic
		var NetStatsStartTime: LocalTime = LocalTime.of(0, 0)

		@JvmStatic
		var NetStatStartBase: Int = NET_STAT_TYPE_DAY

		@JvmStatic
		fun refreshAll() {
			instances.forEach { it.informCallbacks() }
		}
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
			this.alarmManager = alarmManager

			val alarmTime = Calendar.getInstance()
			alarmTime.set(Calendar.HOUR_OF_DAY, 0)
			alarmTime.set(Calendar.MINUTE, 0)
			alarmTime.add(Calendar.DATE, 1)

			alarmManager.set(
				AlarmManager.RTC,
				alarmTime.timeInMillis,
				"",
				dateAlarmListener,
				null,
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
		// variables start with $ and continue with characters, until they don't!
		val pattern = Pattern.compile("\\\$((T[a-zA-Z][0-9]*)|([A-Z][A-Za-z]+))")

		// We'll locate each variable and replace it with a value, if possible
		val matcher = pattern.matcher(input)
		while (matcher.find()) {
			// Group 1 is the whole alternation and is never optional, so it is
			// non-null whenever `find()` is true - the SDK stubs just annotate
			// `group()` @Nullable and that is where the "only safe calls"
			// diagnostic came from. `!!` throws the same NPE the implicit
			// null-check already threw, so this names it rather than changing it.
			val match = matcher.group(1)!!

			val start = result.toString().indexOf("\$" + match)
			result.replace(start, start + match.length + 1, valueOf(match))
		}
		if (mNetworkStatsType != NETWORK_STATS_NONE) {
			registerUsageCallback()
		}

		ensureSSIDCallback()
		return result
	}

	private fun ensureSSIDCallback() {
		if (hasSSID == ssidCallbackRegistered || SystemUtils.ConnectivityManager() == null) {
			return
		}

		if (hasSSID) {
			SystemUtils.ConnectivityManager()!!.addDefaultNetworkActiveListener(ssidChangeCallback)
		} else {
			SystemUtils.ConnectivityManager()!!.removeDefaultNetworkActiveListener(ssidChangeCallback)
		}
		ssidCallbackRegistered = hasSSID
	}

	private fun registerUsageCallback() {
		if (mRegisteredCallbackType != mNetworkStatsType) {
			unregisterUsageCallback()
			try {
				SystemUtils.NetworkStatsManager()!!.registerUsageCallback(
					ConnectivityManager.TYPE_MOBILE,
					null,
					NETSTAT_THRESHOLD_BYTES,
					networkUsageCallback,
				)
				mRegisteredCallbackType = mRegisteredCallbackType or NETWORK_STATS_CELL
				if ((mNetworkStatsType and NETWORK_STATS_WIFI) != 0) {
					SystemUtils.NetworkStatsManager()!!.registerUsageCallback(
						ConnectivityManager.TYPE_WIFI,
						null,
						NETSTAT_THRESHOLD_BYTES,
						networkUsageCallback,
					)
				}
				mRegisteredCallbackType = mRegisteredCallbackType or NETWORK_STATS_WIFI
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun unregisterUsageCallback() {
		try {
			SystemUtils.NetworkStatsManager()!!.unregisterUsageCallback(networkUsageCallback)
			mRegisteredCallbackType = NETWORK_STATS_NONE
		} catch (ignored: Throwable) {
		}
	}

	private fun valueOf(match: String): CharSequence = when (match.substring(0, 1)) {
		"G" -> georgianDateOf(match.substring(1))
		"H" -> hijrahDateOf(match.substring(1))
		"P" -> persianDateOf(match.substring(1))
		"N" -> networkStatOf(match.substring(1))
		"T" -> temperatureOf(match.substring(1))
		else -> "\$" + match
	}

	private fun networkStatOf(variable: String): CharSequence {
		var traffic = 0L
		var textColor: Int? = null
		// Locale.getDefault() keeps this on the default locale's rules
		val lowerVariable = variable.lowercase(Locale.getDefault())
		var transformed: CharSequence? = null

		try {
			when (lowerVariable) {
				"crx", "rx" -> {
					textColor = RXColor
					traffic = getTrafficStats(TYPE_RX, !lowerVariable.startsWith("c"))
				}

				"ctx", "tx" -> {
					textColor = TXColor
					traffic = getTrafficStats(TYPE_TX, !lowerVariable.startsWith("c"))
				}

				"call", "all" -> {
					traffic = getTrafficStats(TYPE_RX or TYPE_TX, !lowerVariable.startsWith("c"))
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
			return "\$N" + lowerVariable
		}
	}

	private fun getStartTime(): Long = when (NetStatStartBase) {
		NET_STAT_TYPE_DAY -> getStartTime(NetStatsStartTime)
		NET_STAT_TYPE_WEEK -> getStartTime(Calendar.DAY_OF_WEEK, NetStatsDayOf)
		NET_STAT_TYPE_MONTH -> getStartTime(Calendar.DAY_OF_MONTH, NetStatsDayOf)
		else -> -1
	}

	@SuppressLint("MissingPermission")
	private fun fetchCurrentWifiSSID(): String {
		// method is deprecated, but will continue to work until further notice. the new way can be found here:
		// https://cs.android.com/android/platform/superproject/main/+/main:packages/modules/Wifi/framework/java/android/net/wifi/WifiManager.java;l=3660?q=wifimanager
		var theSsid = SystemUtils.WifiManager()!!.connectionInfo.getSSID()
		if (theSsid.startsWith("\"") && theSsid.endsWith("\"")) {
			theSsid = theSsid.substring(1, theSsid.length - 1)
		}
		if (WifiManager.UNKNOWN_SSID != theSsid) {
			return theSsid
		}

		return ""
	}

	private fun getTrafficStats(type: Int, includeWiFi: Boolean): Long {
		val startTime = getStartTime()

		if (startTime < 0) {
			return 0
		}

		var ret = 0L
		try {
			var bucket = SystemUtils.NetworkStatsManager()!!.querySummaryForDevice(
				ConnectivityManager.TYPE_MOBILE,
				null,
				startTime,
				Calendar.getInstance().timeInMillis,
			)
			ret += getTraffic(bucket, type)

			if (includeWiFi) {
				mNetworkStatsType = mNetworkStatsType or NETWORK_STATS_WIFI

				bucket = SystemUtils.NetworkStatsManager()!!.querySummaryForDevice(
					ConnectivityManager.TYPE_WIFI,
					null,
					startTime,
					Calendar.getInstance().timeInMillis,
				)
				ret += getTraffic(bucket, type)
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
		val startTimeCalendar = Calendar.getInstance()
		startTimeCalendar.set(Calendar.HOUR_OF_DAY, timeOfDay.hour)
		startTimeCalendar.set(Calendar.MINUTE, timeOfDay.minute)

		if (startTimeCalendar.after(Calendar.getInstance())) {
			return -1
		}
		return startTimeCalendar.time.time
	}

	private fun getStartTime(dayType: Int, dayOf: Int): Long {
		val startTimeCalendar = Calendar.getInstance()

		startTimeCalendar.set(Calendar.HOUR_OF_DAY, 0)
		startTimeCalendar.set(Calendar.MINUTE, 0)

		when (dayType) {
			Calendar.DAY_OF_MONTH -> {
				startTimeCalendar.set(Calendar.DAY_OF_MONTH, dayOf)
				if (startTimeCalendar.after(Calendar.getInstance())) {
					startTimeCalendar.add(Calendar.MONTH, -1)
				}
			}

			Calendar.DAY_OF_WEEK -> {
				startTimeCalendar.set(Calendar.DAY_OF_WEEK, dayOf)
				if (startTimeCalendar.after(Calendar.getInstance())) {
					startTimeCalendar.add(Calendar.DATE, -7)
				}
			}

			else -> return Calendar.getInstance().time.time
		}

		return startTimeCalendar.time.time
	}

	private fun georgianDateOf(format: String): CharSequence {
		try {
			@SuppressLint("SimpleDateFormat")
			val result = SimpleDateFormat(format)
				.format(
					Calendar.getInstance().time,
				)
			hasDate = true
			return result
		} catch (ignored: Exception) {
			return "\$G" + format
		}
	}

	private fun temperatureOf(format: String): CharSequence {
		try {
			val match = Pattern.compile("^([A-Za-z])([0-9]*)$").matcher(format)

			if (!match.find()) {
				throw Exception()
			}
			// `([A-Za-z])` is mandatory, so group 1 exists whenever `find()`
			// was true; the stubs annotate `group()` @Nullable all the same.
			val typeStr = match.group(1)!!

			var nextUpdate: Long

			try {
				nextUpdate = Integer.parseInt(match.group(2) ?: "60").toLong()
			} catch (ignored: Throwable) {
				nextUpdate = 60
			}

			nextUpdate *= 1000L

			val type = when (typeStr.lowercase(Locale.getDefault())) {
				"b" -> ThermalProvider.BATTERY
				"c" -> ThermalProvider.CPU
				"g" -> ThermalProvider.GPU
				"s" -> ThermalProvider.SKIN
				else -> throw Exception()
			}

			val temperature = ThermalProvider.getTemperatureMaxInt(type)

			if (temperature < -990) {
				scheduleUpdate(1000L)
				return "Err"
			}

			scheduleUpdate(nextUpdate)

			return temperature.toString()
		} catch (ignored: Exception) {
			return "\$T" + format
		}
	}

	private fun scheduleUpdate(nextUpdate: Long) {
		handler.removeCallbacks(scheduledUpdate)
		handler.postDelayed(scheduledUpdate, nextUpdate)
	}

	private fun hijrahDateOf(format: String): CharSequence {
		try {
			val result = HijrahDate.from(
				LocalDate.now(),
			).format(
				DateTimeFormatter.ofPattern(
					format,
					Locale.forLanguageTag("ar"),
				),
			)
			hasDate = true
			val bytes = result.toCharArray()
			NumericShaper.getShaper(NumericShaper.ARABIC).shape(bytes, 0, bytes.size) // Numbers to be shown in correct font
			return String(bytes)
		} catch (ignored: Exception) {
			return "\$H" + format
		}
	}

	private fun persianDateOf(format: String): CharSequence {
		try {
			val result = PersianDate.now().format(
				DateTimeFormatter.ofPattern(
					format,
					Locale.forLanguageTag("fa"),
				),
			)
			hasDate = true
			val bytes = result.toCharArray()
			// Numbers to be shown in correct font
			NumericShaper.getShaper(NumericShaper.EASTERN_ARABIC).shape(bytes, 0, bytes.size)
			return String(bytes)
		} catch (ignored: Exception) {
			return "\$P" + format
		}
	}

	fun registerCallback(callback: FormattedStringCallback) {
		callbacks.add(callback)
	}

	fun unRegisterCallback(callback: FormattedStringCallback) {
		callbacks.remove(callback)
	}

	fun resetCallbacks() {
		callbacks.clear()
	}

	/** Releases every process callback and timer this formatter registered. */
	fun release() {
		handler.removeCallbacksAndMessages(null)
		runCatching { alarmManager?.cancel(dateAlarmListener) }
		alarmManager = null

		runCatching { unregisterUsageCallback() }

		if (ssidCallbackRegistered) {
			runCatching {
				SystemUtils.ConnectivityManager()?.removeDefaultNetworkActiveListener(ssidChangeCallback)
			}
			ssidCallbackRegistered = false
		}

		resetCallbacks()
		instances.remove(this)
	}

	interface FormattedStringCallback {
		fun onRefreshNeeded()
	}
}
