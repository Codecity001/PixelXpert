package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.hardware.SensorEvent
import android.os.SystemClock
import android.util.SparseArray
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getLongField
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import java.time.Duration
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Sleeps the device once it has been lying still, face up, for long enough -
 * the "sleep on flat screen" feature. It lives in system server (services.jar)
 * next to the two classes it talks to, and only does anything while the
 * `SleepOnFlatScreen` preference is on.
 *
 * The detector is the framework's own `FaceDownDetector.onSensorChanged`: the
 * sensor event supplies the Z tilt and the timestamp, and the pack layers its
 * rules on top of it - face up past [FACE_UP_Z_THRESHOLD], no user activity
 * within [FlatStandbyTimeMillis], stationary for as long as the detector's own
 * `mTimeThreshold` declares "not moving", and no stay-awake wakelock when
 * `SleepOnFlatRespectWakeLock` asks for that. Only then does it call
 * `PowerManagerService.goToSleepInternal`, and never twice inside five
 * seconds, so a slow power state pass cannot queue sleep orders.
 *
 * `PowerManagerService` has no constructor hook worth using, so the instance is
 * captured from the first `updatePowerStateLocked` call and that hook unhooks
 * itself; the power group ids handed to `goToSleepInternal` are built once from
 * `android.util.IntArray` and cached the same way.
 */
@FrameworkModPack
class FaceUpScreenSleep(context: Context) : XposedModPack(context) {

	companion object {
		const val FACE_UP_Z_THRESHOLD: Float = 9f
		const val DEFAULT_DISPLAY_GROUP = 0
		const val GO_TO_SLEEP_REASON_TIMEOUT = 2
		internal const val WAKE_LOCK_STAY_AWAKE = 1 shl 5
		private const val FLAG_DEBUG = false

		private var SleepOnFlatScreen = false

		@JvmStatic
		var FlatStandbyTimeMillis: Long = 5000

		private var SleepOnFlatRespectWakeLock = true
	}

	internal var mFirstStableMillis: Long = 0
	internal var mIsMoving = true
	private var mPowerManagerServiceInstance: Any? = null
	internal var mLastSleepOrderMillis: Long = 0
	var minimumSleepTime: Long = 0
	private var mGroupIDs: Any? = null

	override fun onPreferenceUpdated(vararg key: String?) {
		SleepOnFlatScreen = Xprefs.getBoolean("SleepOnFlatScreen", false)
		FlatStandbyTimeMillis = Xprefs.getSliderInt("FlatStandbyTime", 5) * 1000L
		SleepOnFlatRespectWakeLock = Xprefs.getBoolean("SleepOnFlatRespectWakeLock", true)
		resetTime("pref update")
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val FaceDownDetectorClass = ReflectedClass.of("com.android.server.power.FaceDownDetector")
		val PowerManagerServiceClass = ReflectedClass.of("com.android.server.power.PowerManagerService")

		val unHooks = ArrayList<Set<XposedInterface.HookHandle>>()
		unHooks.add(
			PowerManagerServiceClass
				.after("updatePowerStateLocked")
				.run { param ->
					mPowerManagerServiceInstance = param.thisObject
					unHooks[0].forEach(XposedInterface.HookHandle::unhook)
					unHooks.clear()
				}
		)

		FaceDownDetectorClass
			.after("onSensorChanged")
			.run { param ->
				//noinspection ConstantValue
				if (!SleepOnFlatScreen || getBooleanField(param.thisObject, "mFaceDown")) return@run //device is already facing down or feature not enabled

				val event = param.args[0] as SensorEvent
				val currentTimeNanos = event.timestamp
				val currentTimeMillis = currentTimeNanos / 1_000_000L

				if (currentTimeMillis < minimumSleepTime) return@run

				val mPowerGroups = getObjectField(mPowerManagerServiceInstance, "mPowerGroups")
				val defaultPowerGroup = callMethod(mPowerGroups, "get", DEFAULT_DISPLAY_GROUP)

				if (!(event.values[2] > FACE_UP_Z_THRESHOLD)) {
					resetTime("direction")
					return@run //not facing up
				}

				val lastUserActivityMillis = callMethod(defaultPowerGroup, "getLastUserActivityTimeLocked") as Long

				if (SystemClock.uptimeMillis() - lastUserActivityMillis < FlatStandbyTimeMillis) {
					resetTime("user activity")
					return@run //user is touching the screen
				}

				val mTimeThreshold = getObjectField(param.thisObject, "mTimeThreshold") as Duration

				val moving = currentTimeNanos - getLongField(param.thisObject, "mPrevAccelerationTime") <= mTimeThreshold.toNanos()

				if (mIsMoving && !moving) {
					mIsMoving = false
					mFirstStableMillis = currentTimeMillis
				} else if (!mIsMoving && moving) {
					mIsMoving = true
				}

				if (mIsMoving || currentTimeMillis < minimumSleepTime) {
					resetTime("movement")
					return@run //not stationary enough
				}

				if (SleepOnFlatRespectWakeLock) {
					callMethod(mPowerManagerServiceInstance, "updatePowerStateLocked")
					val wakeLockSummary = getIntField(mPowerManagerServiceInstance, "mWakeLockSummary")
					if ((wakeLockSummary and WAKE_LOCK_STAY_AWAKE) != 0) {
						resetTime("wake lock")
						return@run //wake lock detected
					}
				}

				if ((currentTimeMillis - mLastSleepOrderMillis) > 5000) { //avoid multiple sleep orders
					mLastSleepOrderMillis = currentTimeMillis
					callMethod(mPowerManagerServiceInstance, "goToSleepInternal", getPowerGroupIDs(), SystemClock.uptimeMillis(), GO_TO_SLEEP_REASON_TIMEOUT, 0 /* flag */)
				}
			}
	}

	@Suppress("UNCHECKED_CAST")
	private fun getPowerGroupIDs(): Any? {
		if (mGroupIDs != null) return mGroupIDs

		val IntArrayClass = ReflectedClass.of("android.util.IntArray")
		mGroupIDs = IntArrayClass.clazz!!.getConstructor().newInstance()

		val mPowerGroups = getObjectField(mPowerManagerServiceInstance, "mPowerGroups") as SparseArray<*>
		for (i in 0 until mPowerGroups.size()) {
			if (callMethod(mPowerGroups.valueAt(i), "isDefaultOrAdjacentGroup") as Boolean) {
				callMethod(mGroupIDs, "add", callMethod(mPowerGroups.valueAt(i), "getGroupId"))
			}
		}
		return mGroupIDs
	}

	private fun resetTime(reason: String) {
		if (FLAG_DEBUG) {
			log("resetting time for " + reason)
		}
		minimumSleepTime = SystemClock.elapsedRealtime() + FlatStandbyTimeMillis
	}
}
