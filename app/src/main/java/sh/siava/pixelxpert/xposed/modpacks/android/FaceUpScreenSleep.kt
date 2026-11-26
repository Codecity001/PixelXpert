package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.hardware.SensorEvent
import android.os.PowerManager
import android.os.SystemClock
import android.util.SparseArray
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getLongField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import java.time.Duration

/**
 * Sleeps the device once it has been lying still, face up, on a flat surface
 * for long enough - the "sleep on flat surface" feature.
 *
 * Runs in system server (services.jar) and integrates with the framework's
 * `FaceDownDetector` and `PowerManagerService`.
 *
 * Rather than recreating sensor listeners from scratch, this pack enables
 * `FaceDownDetector` when `SleepOnFlatScreen` is active by hooking `isEnabled()`.
 * Its `onSensorChanged` then supplies acceleration data and stationary time.
 * When the device remains face-up past [FACE_UP_Z_THRESHOLD] and stationary for
 * [FlatStandbyTimeMillis] with no user input, it triggers `PowerManager.goToSleep`.
 */
@FrameworkModPack
class FaceUpScreenSleep(context: Context) : XposedModPack(context) {

	companion object {
		const val FACE_UP_Z_THRESHOLD: Float = 9f
		const val DEFAULT_DISPLAY_GROUP = 0
		const val GO_TO_SLEEP_REASON_TIMEOUT = 2
		internal const val WAKE_LOCK_STAY_AWAKE = 1 shl 5

		private var SleepOnFlatScreen = false

		@JvmStatic
		var FlatStandbyTimeMillis: Long = 5000L

		private var SleepOnFlatRespectWakeLock = true
	}

	private var mFirstStableMillis: Long = 0L
	private var mPowerManagerServiceInstance: Any? = null
	private var mLastSleepOrderMillis: Long = 0L
	private var mGroupIDs: Any? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		SleepOnFlatScreen = Xprefs.getBoolean("SleepOnFlatScreen", false)
		FlatStandbyTimeMillis = Xprefs.getSliderInt("FlatStandbyTime", 5) * 1000L
		SleepOnFlatRespectWakeLock = Xprefs.getBoolean("SleepOnFlatRespectWakeLock", true)
		mFirstStableMillis = 0L
		updateDetectorState()
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val FaceDownDetectorClass = ReflectedClass.of("com.android.server.power.FaceDownDetector")
		val PowerManagerServiceClass = ReflectedClass.of("com.android.server.power.PowerManagerService")

		PowerManagerServiceClass
			.after("updatePowerStateLocked")
			.run { param ->
				mPowerManagerServiceInstance = param.thisObject
			}

		// Ensure FaceDownDetector activates when SleepOnFlatScreen is enabled
		FaceDownDetectorClass
			.before("isEnabled")
			.run { param ->
				if (SleepOnFlatScreen) {
					param.result = true
				}
			}

		FaceDownDetectorClass
			.after("onSensorChanged")
			.run { param ->
				// Device is already facing down or feature not enabled
				if (!SleepOnFlatScreen || getBooleanField(param.thisObject, "mFaceDown")) {
					mFirstStableMillis = 0L
					return@run
				}

				val event = param.args[0] as SensorEvent
				val currentTimeNanos = event.timestamp
				val currentTimeMillis = currentTimeNanos / 1_000_000L

				if (event.values[2] <= FACE_UP_Z_THRESHOLD) {
					mFirstStableMillis = 0L
					return@run // Not facing up
				}

				val mTimeThreshold = getObjectField(param.thisObject, "mTimeThreshold") as? Duration
				val timeThresholdNanos = mTimeThreshold?.toNanos() ?: 1_000_000_000L
				val moving = currentTimeNanos - getLongField(param.thisObject, "mPrevAccelerationTime") <= timeThresholdNanos

				if (moving) {
					mFirstStableMillis = 0L
					return@run // Still moving
				}

				if (mFirstStableMillis == 0L) {
					mFirstStableMillis = currentTimeMillis
					return@run
				}

				if (currentTimeMillis - mFirstStableMillis < FlatStandbyTimeMillis) {
					return@run // Still counting down standby time
				}

				// Check recent user activity (touch/key)
				val lastUserActivity = getLastUserActivityTime()
				if (SystemClock.uptimeMillis() - lastUserActivity < FlatStandbyTimeMillis) {
					mFirstStableMillis = currentTimeMillis
					return@run
				}

				// Check stay-awake wake locks if configured to respect them
				if (SleepOnFlatRespectWakeLock && hasStayAwakeWakeLock()) {
					mFirstStableMillis = currentTimeMillis
					return@run
				}

				if (currentTimeMillis - mLastSleepOrderMillis > 5000L) {
					mLastSleepOrderMillis = currentTimeMillis
					goToSleep()
				}
			}
	}

	private fun updateDetectorState() {
		val pms = mPowerManagerServiceInstance ?: return
		val fdd = getObjectField(pms, "mFaceDownDetector") ?: return
		runCatching {
			callMethod(fdd, "readValuesFromDeviceConfig")
			callMethod(fdd, "updateActiveState")
		}
	}

	private fun getLastUserActivityTime(): Long {
		val pms = mPowerManagerServiceInstance ?: return 0L
		val powerGroups = getObjectField(pms, "mPowerGroups") as? SparseArray<*>
		val defaultPowerGroup = powerGroups?.get(DEFAULT_DISPLAY_GROUP)
		return callMethod(defaultPowerGroup, "getLastUserActivityTimeLocked") as? Long ?: 0L
	}

	private fun hasStayAwakeWakeLock(): Boolean {
		val pms = mPowerManagerServiceInstance ?: return false
		val wakeLockSummary = getIntField(pms, "mWakeLockSummary")
		if ((wakeLockSummary and WAKE_LOCK_STAY_AWAKE) != 0) return true
		val powerGroups = getObjectField(pms, "mPowerGroups") as? SparseArray<*>
		val defaultPowerGroup = powerGroups?.get(DEFAULT_DISPLAY_GROUP)
		val groupSummary = callMethod(defaultPowerGroup, "getWakeLockSummaryLocked") as? Int ?: 0
		return (groupSummary and WAKE_LOCK_STAY_AWAKE) != 0
	}

	private fun goToSleep() {
		val pm = mContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
		val uptime = SystemClock.uptimeMillis()
		try {
			callMethod(pm, "goToSleep", uptime, GO_TO_SLEEP_REASON_TIMEOUT, 0)
		} catch (_: Throwable) {
			mPowerManagerServiceInstance?.let { pms ->
				runCatching {
					callMethod(pms, "goToSleepInternal", getPowerGroupIDs(), uptime, GO_TO_SLEEP_REASON_TIMEOUT, 0)
				}
			}
		}
	}

	@Suppress("UNCHECKED_CAST")
	private fun getPowerGroupIDs(): Any? {
		if (mGroupIDs != null) return mGroupIDs

		val intArrayClass = ReflectedClass.of("android.util.IntArray")
		val groupIds = intArrayClass.clazz?.getConstructor()?.newInstance() ?: return null

		val pms = mPowerManagerServiceInstance ?: return null
		val powerGroups = getObjectField(pms, "mPowerGroups") as? SparseArray<*> ?: return null
		for (i in 0 until powerGroups.size()) {
			val group = powerGroups.valueAt(i)
			if (callMethod(group, "isDefaultOrAdjacentGroup") as? Boolean == true) {
				callMethod(groupIds, "add", callMethod(group, "getGroupId"))
			}
		}
		mGroupIDs = groupIds
		return mGroupIDs
	}

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		mPowerManagerServiceInstance?.let { outState["powerManagerService"] = it }
		mGroupIDs?.let { outState["powerGroupIds"] = it }
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		mPowerManagerServiceInstance = savedState["powerManagerService"]
		mGroupIDs = savedState["powerGroupIds"]
	}
}
