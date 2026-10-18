package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.hardware.SensorEvent
import android.os.SystemClock
import android.util.SparseArray
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getLongField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.time.Duration
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

@FrameworkModPack
class FaceUpScreenSleep(context: Context) : XposedModPack(context) {

    companion object {
        const val FACE_UP_Z_THRESHOLD = 9f
        const val DEFAULT_DISPLAY_GROUP = 0
        const val GO_TO_SLEEP_REASON_TIMEOUT = 2
        const val WAKE_LOCK_STAY_AWAKE = 1 shl 5
        private const val FLAG_DEBUG = false

        private var sleepOnFlatScreen = false
        var flatStandbyTimeMillis = 5000L
        private var sleepOnFlatRespectWakeLock = true
    }

    private var mFirstStableMillis = 0L
    private var mIsMoving = true
    private var mPowerManagerServiceInstance: Any? = null
    private var mLastSleepOrderMillis = 0L
    var minimumSleepTime = 0L
    private var mGroupIDs: Any? = null

    override fun onPreferenceUpdated(vararg keys: String) {
        sleepOnFlatScreen = XPrefs.getBoolean("SleepOnFlatScreen", false)
        flatStandbyTimeMillis = XPrefs.getSliderInt("FlatStandbyTime", 5) * 1000L
        sleepOnFlatRespectWakeLock = XPrefs.getBoolean("SleepOnFlatRespectWakeLock", true)
        resetTime("pref update")
    }

    override fun onPackageLoaded(param: PackageReadyParam) {
        val faceDownDetectorClass = ReflectedClass.of("com.android.server.power.FaceDownDetector", param.classLoader)
        val powerManagerServiceClass = ReflectedClass.of("com.android.server.power.PowerManagerService", param.classLoader)

        val unHooks = mutableListOf<Set<HookHandle>>()
        unHooks.add(
            powerManagerServiceClass
                .after("updatePowerStateLocked")
                .run { hookParam ->
                    mPowerManagerServiceInstance = hookParam.thisObject
                    unHooks.firstOrNull()?.forEach { it.unhook() }
                    unHooks.clear()
                }
        )

        faceDownDetectorClass
            .after("onSensorChanged")
            .run { hookParam ->
                val detector = hookParam.thisObject ?: return@run
                if (!sleepOnFlatScreen || getBooleanField(detector, "mFaceDown")) return@run

                val event = hookParam.args.getOrNull(0) as? SensorEvent ?: return@run
                val currentTimeNanos = event.timestamp
                val currentTimeMillis = currentTimeNanos / 1_000_000L

                if (currentTimeMillis < minimumSleepTime) return@run

                val pms = mPowerManagerServiceInstance ?: return@run
                val mPowerGroups = getObjectField(pms, "mPowerGroups")
                val defaultPowerGroup = callMethod(mPowerGroups, "get", DEFAULT_DISPLAY_GROUP)

                if (event.values[2] <= FACE_UP_Z_THRESHOLD) {
                    resetTime("direction")
                    return@run
                }

                val lastUserActivityMillis = callMethod(defaultPowerGroup, "getLastUserActivityTimeLocked") as Long
                if (SystemClock.uptimeMillis() - lastUserActivityMillis < flatStandbyTimeMillis) {
                    resetTime("user activity")
                    return@run
                }

                val mTimeThreshold = getObjectField(detector, "mTimeThreshold") as Duration
                val moving = currentTimeNanos - getLongField(detector, "mPrevAccelerationTime") <= mTimeThreshold.toNanos()

                if (mIsMoving && !moving) {
                    mIsMoving = false
                    mFirstStableMillis = currentTimeMillis
                } else if (!mIsMoving && moving) {
                    mIsMoving = true
                }

                if (mIsMoving || currentTimeMillis < minimumSleepTime) {
                    resetTime("movement")
                    return@run
                }

                if (sleepOnFlatRespectWakeLock) {
                    callMethod(pms, "updatePowerStateLocked")
                    val wakeLockSummary = getIntField(pms, "mWakeLockSummary")
                    if ((wakeLockSummary and WAKE_LOCK_STAY_AWAKE) != 0) {
                        resetTime("wake lock")
                        return@run
                    }
                }

                if ((currentTimeMillis - mLastSleepOrderMillis) > 5000) {
                    mLastSleepOrderMillis = currentTimeMillis
                    callMethod(
                        pms,
                        "goToSleepInternal",
                        getPowerGroupIDs(),
                        SystemClock.uptimeMillis(),
                        GO_TO_SLEEP_REASON_TIMEOUT,
                        0
                    )
                }
            }
    }

    private fun getPowerGroupIDs(): Any? {
        if (mGroupIDs != null) return mGroupIDs

        val intArrayClass = ReflectedClass.of("android.util.IntArray")
        val instance = intArrayClass.clazz?.getConstructor()?.newInstance() ?: return null
        mGroupIDs = instance

        val pms = mPowerManagerServiceInstance ?: return instance
        val mPowerGroups = getObjectField(pms, "mPowerGroups") as? SparseArray<*> ?: return instance
        for (i in 0 until mPowerGroups.size()) {
            val group = mPowerGroups.valueAt(i)
            if (callMethod(group, "isDefaultOrAdjacentGroup") as Boolean) {
                callMethod(instance, "add", callMethod(group, "getGroupId"))
            }
        }
        return mGroupIDs
    }

    private fun resetTime(reason: String) {
        if (FLAG_DEBUG) {
            log("resetting time for $reason")
        }
        minimumSleepTime = SystemClock.elapsedRealtime() + flatStandbyTimeMillis
    }
}
