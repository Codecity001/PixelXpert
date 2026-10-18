package sh.siava.pixelxpert.xposed.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.DownloadManager
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraManager.TorchCallback
import android.hardware.camera2.CameraMetadata
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telephony.TelephonyManager
import android.view.WindowManager
import java.lang.reflect.Method
import java.util.ArrayList
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants.SYSTEM_UI_PACKAGE
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod
import sh.siava.pixelxpert.xposed.utils.toolkit.BootLoopProtector
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SystemUtils(context: Context) {

    var mContext: Context = context
    private var mPackageManager: PackageManager? = null
    private var mCameraManager: CameraManager? = null
    private var mVibrationManager: VibratorManager? = null
    private var mAudioManager: AudioManager? = null
    private var mPowerManager: PowerManager? = null
    private var mConnectivityManager: ConnectivityManager? = null
    private var mTelephonyManager: TelephonyManager? = null
    private var mAlarmManager: AlarmManager? = null
    private var mDownloadManager: DownloadManager? = null
    private var mNetworkStatsManager: NetworkStatsManager? = null
    private var mHasVibrator: Boolean = false
    private var maxFlashLevel: Int = -1

    private val mVolumeChangeListeners = ArrayList<ChangeListener>()
    private val mFlashlightLevelListeners = ArrayList<ChangeListener>()
    private var mWifiManager: WifiManager? = null
    private var mWindowManager: WindowManager? = null
    private var mUserManager: UserManager? = null
    private var mHandler: Handler? = null
    private var mSetTorchModeMethod: Method? = null

    init {
        instance = this
        HighBrightnessTorchController.init(context)
        registerVolumeChangeReceiver()
    }

    companion object {
        private const val THREAD_PRIORITY_BACKGROUND = 10
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val FADE_DURATION = 500

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var instance: SystemUtils? = null
            internal set

        @JvmStatic
        var isTorchOn: Boolean = false
            internal set

        @JvmStatic
        @Volatile
        var isInternalTorchCall: Boolean = false
            internal set

        private var darkSwitching = false

        @JvmStatic
        fun restart(what: String) {
            when (what.lowercase()) {
                "systemui" -> {
                    BootLoopProtector.resetCounter(SYSTEM_UI_PACKAGE)
                    runRootCommand("killall $SYSTEM_UI_PACKAGE")
                }
                "system" -> {
                    runRootCommand("am start -a android.intent.action.REBOOT")
                }
                "zygote", "android" -> {
                    runRootCommand("kill $(pidof zygote)")
                    runRootCommand("kill $(pidof zygote64)")
                }
                "bootloader" -> {
                    runRootCommand("reboot bootloader")
                }
                else -> {
                    runRootCommand("killall $what")
                }
            }
        }

        private fun runRootCommand(command: String) {
            XPLauncher.enqueueProxyCommand { proxy ->
                try {
                    proxy.runRootCommand(command)
                } catch (ignored: Throwable) {}
            }
        }

        @JvmStatic
        fun isFlashOn(): Boolean = instance?.isTorchOnInternal() ?: false

        @JvmStatic
        fun toggleFlash(animate: Boolean) {
            instance?.toggleFlashInternal(animate)
        }

        @JvmStatic
        fun setFlash(enabled: Boolean, level: Int, animate: Boolean) {
            instance?.setFlashInternalWithLevel(enabled, level, animate)
        }

        @JvmStatic
        fun setFlash(enabled: Boolean, animate: Boolean) {
            instance?.setFlashInternal(enabled, animate)
        }

        @JvmStatic
        fun WifiManager(): WifiManager? = instance?.getWifiManager()

        @JvmStatic
        fun NetworkStatsManager(): NetworkStatsManager? = instance?.getNetworkStatsManager()

        @JvmStatic
        fun CameraManager(): CameraManager? = instance?.getCameraManager()

        @JvmStatic
        fun PackageManager(): PackageManager? = instance?.getPackageManager()

        @JvmStatic
        fun AudioManager(): AudioManager? = instance?.getAudioManager()

        @JvmStatic
        fun WindowManager(): WindowManager? = instance?.getWindowManager()

        @JvmStatic
        fun UserManager(): UserManager? = instance?.getUserManager()

        @JvmStatic
        fun ConnectivityManager(): ConnectivityManager? = instance?.getConnectivityManager()

        @JvmStatic
        fun PowerManager(): PowerManager? = instance?.getPowerManager()

        @JvmStatic
        fun AlarmManager(): AlarmManager? = instance?.getAlarmManager()

        @JvmStatic
        fun TelephonyManager(): TelephonyManager? = instance?.getTelephonyManager()

        @JvmStatic
        fun DownloadManager(): DownloadManager? = instance?.getDownloadManager()

        @JvmStatic
        fun vibrate(effect: Int, vibrationUsage: Int?) {
            vibrate(VibrationEffect.createPredefined(effect), vibrationUsage)
        }

        @SuppressLint("MissingPermission")
        @JvmStatic
        fun vibrate(effect: VibrationEffect, vibrationUsage: Int?) {
            val inst = instance ?: return
            if (!inst.hasVibrator()) return
            try {
                val vm = inst.getVibrationManager() ?: return
                if (vibrationUsage != null) {
                    vm.defaultVibrator.vibrate(effect, VibrationAttributes.createForUsage(vibrationUsage))
                } else {
                    vm.defaultVibrator.vibrate(effect)
                }
            } catch (ignored: Exception) {}
        }

        @JvmStatic
        fun sleep() {
            instance?.let {
                try {
                    // 4 = GO_TO_SLEEP_REASON_POWER_BUTTON, forces immediate lock
                    PowerManager()?.let { pm -> callMethod<Any>(pm, "goToSleep", SystemClock.uptimeMillis(), 4, 0) }
                } catch (ignored: Throwable) {
                    try {
                        PowerManager()?.let { pm -> callMethod<Any>(pm, "goToSleep", SystemClock.uptimeMillis()) }
                    } catch (ignored2: Throwable) {}
                }
            }
        }

        @JvmStatic
        fun threadSleep(millis: Int) {
            try {
                Thread.sleep(millis.toLong())
            } catch (ignored: Throwable) {}
        }

        @JvmStatic
        fun registerFlashlightLevelListener(listener: ChangeListener) {
            instance?.let {
                if (!it.mFlashlightLevelListeners.contains(listener)) {
                    it.mFlashlightLevelListeners.add(listener)
                }
            }
        }

        @JvmStatic
        fun unregisterFlashlightLevelListener(listener: ChangeListener) {
            instance?.mFlashlightLevelListeners?.remove(listener)
        }

        @JvmStatic
        fun registerVolumeChangeListener(listener: ChangeListener) {
            instance?.mVolumeChangeListeners?.add(listener)
        }

        @JvmStatic
        fun unregisterVolumeChangeListener(listener: ChangeListener) {
            instance?.mVolumeChangeListeners?.remove(listener)
        }

        @JvmStatic
        fun getFlashlightLevel(flashPct: Float): Int {
            return instance?.getFlashlightLevelInternal(flashPct) ?: 1
        }

        @JvmStatic
        fun supportsFlashLevels(): Boolean {
            return instance?.supportsFlashLevelsInternal() ?: false
        }

        @JvmStatic
        fun getMaxFlashLevel(): Int {
            return instance?.getMaxFlashLevelInternal() ?: -1
        }

        @JvmStatic
        fun getFlashStrength(): Int {
            return instance?.getFlashStrengthInternal() ?: 0
        }

        @JvmStatic
        fun getFlashStrengthPCT(): Float {
            val max = getMaxFlashLevel()
            if (max <= 0) return 0f
            return (instance?.getFlashStrengthInternal() ?: 0) / max.toFloat()
        }

        @JvmStatic
        fun isDarkMode(): Boolean = instance?.getIsDark() ?: false

        @JvmStatic
        fun doubleToggleDarkMode() {
            XPLauncher.enqueueProxyCommand { proxy ->
                val isDark = isDarkMode()
                Thread {
                    try {
                        while (darkSwitching) {
                            threadSleep(100)
                        }
                        darkSwitching = true
                        var currentMode: String? = null
                        try {
                            val out = proxy.runRootCommand("cmd uimode night")
                            if (out != null) {
                                for (line in out) {
                                    if (line != null && line.lowercase().contains("night mode:")) {
                                        currentMode = line.substring(line.indexOf(":") + 1).trim()
                                        break
                                    }
                                }
                            }
                        } catch (ignored: Throwable) {}

                        proxy.runRootCommand("cmd uimode night " + (if (isDark) "no" else "yes"))
                        threadSleep(1000)
                        proxy.runRootCommand("cmd uimode night " + (if (isDark) "yes" else "no"))

                        if (!currentMode.isNullOrEmpty() && currentMode != "yes" && currentMode != "no") {
                            threadSleep(500)
                            proxy.runRootCommand("cmd uimode night $currentMode")
                        }

                        threadSleep(500)
                        darkSwitching = false
                    } catch (ignored: Exception) {}
                }.start()
            }
        }

        @JvmStatic
        fun killSelf() {
            BootLoopProtector.resetCounter(android.os.Process.myProcessName())
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        @JvmStatic
        fun toggleMute() {
            instance?.toggleMuteInternal()
        }

        @JvmStatic
        fun idOf(name: String): Int = resourceIdOf(name, "id")

        @JvmStatic
        fun dimenIdOf(name: String): Int = resourceIdOf(name, "dimen")

        @JvmStatic
        fun resourceIdOf(name: String, type: String): Int {
            return instance?.resourceIdOfInternal(name, type) ?: 0
        }
    }

    private fun isTorchOnInternal(): Boolean {
        if (Xprefs.getBoolean("enable_high_brightness_flashlight", false)) {
            val hbtc = HighBrightnessTorchController.getInstance()
            if (hbtc != null && hbtc.isSupported() && hbtc.isOn()) {
                return true
            }
        }
        if (getCameraManager() == null) {
            return false
        }
        return isTorchOn
    }

    private fun hasVibrator(): Boolean = getVibrationManager() != null && mHasVibrator

    private fun registerVolumeChangeReceiver() {
        getAudioManager()?.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                fireVolumeRefresh()
            }
        }, null)

        val volChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == STREAM_MUSIC) {
                    fireVolumeRefresh()
                }
            }
        }

        val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        mContext.registerReceiver(volChangeReceiver, volumeFilter, RECEIVER_EXPORTED)
    }

    private fun fireVolumeRefresh() {
        val level = getAudioManager()?.getStreamVolume(STREAM_MUSIC) ?: return
        mVolumeChangeListeners.forEach { it.onChanged(level) }
    }

    private fun setFlashInternal(enabled: Boolean, animate: Boolean) {
        val cam = getCameraManager() ?: return

        if (Xprefs.getBoolean("enable_high_brightness_flashlight", false)) {
            val hbtc = HighBrightnessTorchController.getInstance()
            if (hbtc != null && hbtc.isSupported()) {
                if (enabled) {
                    hbtc.toggle()
                } else {
                    hbtc.closeCamera()
                }
                return
            }
        }

        try {
            val flashID = getFlashID(cam)
            if (flashID.isEmpty()) return

            if (Xprefs.getBoolean("leveledFlashTile", false) &&
                Xprefs.getBoolean("isFlashLevelGlobal", false) &&
                supportsFlashLevelsInternal()) {
                val currentPct = Xprefs.getInt("flashPCT", 50) / 100f
                setFlashInternalWithLevel(enabled, getFlashlightLevelInternal(currentPct), animate)
            } else {
                setFlashInternalNoLevel(enabled, animate)
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                Logger.log("PixelXpert Error in setting flashlight")
                Logger.log(t)
            }
        }
    }

    private fun setFlashInternalNoLevel(enabled: Boolean, animate: Boolean) {
        try {
            if (animate && supportsFlashLevels()) {
                if (enabled) {
                    animateFlashLightOn(getFlashStrengthInternal())
                } else {
                    animateFlashLightOff()
                }
            } else {
                val cam = getCameraManager()
                if (cam != null && mSetTorchModeMethod != null) {
                    try {
                        isInternalTorchCall = true
                        ReflectedMethod.of(mSetTorchModeMethod).invokeOriginal(cam, getFlashID(cam), enabled)
                    } finally {
                        isInternalTorchCall = false
                    }
                }
            }
        } catch (ignored: Throwable) {}
    }

    private fun animateFlashLightOn(level: Int) {
        mHandler?.post {
            val valueAnimator = ValueAnimator.ofInt(0, level).apply {
                duration = FADE_DURATION.toLong()
                addUpdateListener { animation ->
                    setFlashLevel(true, animation.animatedValue as Int)
                }
            }
            valueAnimator.start()
        }
    }

    private fun animateFlashLightOff() {
        mHandler?.post {
            val valueAnimator = ValueAnimator.ofInt(getFlashStrengthInternal(), 0).apply {
                duration = 500L
                addUpdateListener { animation ->
                    setFlashLevel(true, animation.animatedValue as Int)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        setFlashInternalNoLevel(false, false)
                    }
                })
            }
            valueAnimator.start()
        }
    }

    private fun getFlashlightLevelInternal(flashPct: Float): Int {
        return max(min((getMaxFlashLevel() * flashPct).roundToInt(), getMaxFlashLevel()), 1)
    }

    private fun supportsFlashLevelsInternal(): Boolean {
        if (maxFlashLevel == -1) {
            refreshFlashLevel()
        }
        return maxFlashLevel > 0
    }

    private fun refreshFlashLevel() {
        try {
            val cam = getCameraManager() ?: return
            val flashID = getFlashID(cam)
            if (flashID.isEmpty()) {
                maxFlashLevel = -1
                return
            }
            if (maxFlashLevel == -1) {
                val token = android.os.Binder.clearCallingIdentity()
                try {
                    val key = getStaticObjectField<CameraCharacteristics.Key<Int>>(
                        CameraCharacteristics::class.java,
                        "FLASH_INFO_STRENGTH_MAXIMUM_LEVEL"
                    )
                    maxFlashLevel = cam.getCameraCharacteristics(flashID).get(key) ?: -1
                    try {
                        Xprefs.edit().putInt("cachedMaxFlashLevel", maxFlashLevel).apply()
                    } catch (ignored: Throwable) {}
                } catch (t: Throwable) {
                    maxFlashLevel = Xprefs.getInt("cachedMaxFlashLevel", 45)
                } finally {
                    android.os.Binder.restoreCallingIdentity(token)
                }
            }
        } catch (ignored: Throwable) {}
    }

    private fun getMaxFlashLevelInternal(): Int {
        if (Xprefs.getBoolean("enable_high_brightness_flashlight", false)) {
            val hbtc = HighBrightnessTorchController.getInstance()
            if (hbtc != null && hbtc.isSupported()) {
                return hbtc.getMaxBrightness()
            }
        }
        if (maxFlashLevel == -1) {
            refreshFlashLevel()
        }
        return maxFlashLevel
    }

    private fun setFlashInternalWithLevel(enabled: Boolean, level: Int, animate: Boolean) {
        if (animate) {
            if (enabled) {
                animateFlashLightOn(level)
            } else {
                animateFlashLightOff()
            }
        } else {
            setFlashLevel(enabled, level)
        }
    }

    private fun setFlashLevel(enabled: Boolean, level: Int) {
        if (Xprefs.getBoolean("enable_high_brightness_flashlight", false)) {
            val hbtc = HighBrightnessTorchController.getInstance()
            if (hbtc != null && hbtc.isSupported()) {
                if (enabled) {
                    hbtc.setBrightness(level)
                } else {
                    hbtc.closeCamera()
                }
                return
            }
        }
        try {
            val cam = getCameraManager() ?: return
            val flashID = getFlashID(cam)
            if (enabled) {
                if (supportsFlashLevels()) {
                    try {
                        val token = android.os.Binder.clearCallingIdentity()
                        try {
                            isInternalTorchCall = true
                            ReflectedMethod.ofName(
                                ReflectedClass.of(CameraManager::class.java),
                                "turnOnTorchWithStrengthLevel"
                            ).invokeOriginal(cam, flashID, max(level, 1))
                        } finally {
                            isInternalTorchCall = false
                            android.os.Binder.restoreCallingIdentity(token)
                        }
                    } catch (t: Throwable) {
                        setFlashInternalNoLevel(true, false)
                    }
                } else {
                    setFlashInternalNoLevel(true, false)
                }
            } else {
                setFlashInternalNoLevel(false, false)
            }
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) {
                Logger.log("PixelXpert Error in setting flashlight")
                Logger.log(t)
            }
        }
    }

    private fun informFlashListeners() {
        for (listener in mFlashlightLevelListeners) {
            listener.onChanged(getFlashStrengthInternal())
        }
    }

    private fun toggleFlashInternal(animate: Boolean) {
        setFlashInternal(!isTorchOn, animate)
    }

    private fun getFlashID(cameraManager: CameraManager): String {
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val ids = cameraManager.cameraIdList
            for (id in ids) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                    if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                        return id
                    }
                }
            }
        } catch (t: Throwable) {
            return "0"
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
        return "0"
    }

    private fun getFlashStrengthInternal(): Int {
        if (Xprefs.getBoolean("enable_high_brightness_flashlight", false)) {
            val hbtc = HighBrightnessTorchController.getInstance()
            if (hbtc != null && hbtc.isSupported()) {
                return hbtc.getCurrentBrightness()
            }
        }
        val cam = getCameraManager() ?: return 0
        val token = android.os.Binder.clearCallingIdentity()
        try {
            val level = cam.getTorchStrengthLevel(getFlashID(cam))
            try {
                Xprefs.edit().putInt("cachedFlashLevel", level).apply()
            } catch (ignored: Throwable) {}
            return level
        } catch (e: Throwable) {
            val defaultPct = Xprefs.getInt("flashPCT", 50) / 100f
            return Xprefs.getInt("cachedFlashLevel", (defaultPct * getMaxFlashLevel()).roundToInt())
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    private fun getIsDark(): Boolean {
        return (mContext.resources.configuration.uiMode and UI_MODE_NIGHT_YES) == UI_MODE_NIGHT_YES
    }

    private fun getAudioManager(): AudioManager? {
        if (mAudioManager == null) {
            try {
                mAudioManager = mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Logger.log(t)
            }
        }
        return mAudioManager
    }

    private fun getPackageManager(): PackageManager? {
        if (mPackageManager == null) {
            try {
                mPackageManager = mContext.packageManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Logger.log(t)
            }
        }
        return mPackageManager
    }

    private fun getWifiManager(): WifiManager? {
        if (mWifiManager == null) {
            try {
                mWifiManager = mContext.getSystemService(WifiManager::class.java)
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Logger.log("PixelXpert Error getting wifi manager")
                    Logger.log(t)
                }
            }
        }
        return mWifiManager
    }

    private fun getConnectivityManager(): ConnectivityManager? {
        if (mConnectivityManager == null) {
            try {
                mConnectivityManager = mContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Logger.log("PixelXpert Error getting connection manager")
                    Logger.log(t)
                }
            }
        }
        return mConnectivityManager
    }

    private fun getPowerManager(): PowerManager? {
        if (mPowerManager == null) {
            try {
                mPowerManager = mContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Logger.log("PixelXpert Error getting power manager")
                    Logger.log(t)
                }
            }
        }
        return mPowerManager
    }

    private fun getAlarmManager(): AlarmManager? {
        if (mAlarmManager == null) {
            try {
                mAlarmManager = mContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Logger.log("PixelXpert Error getting alarm manager")
                    Logger.log(t)
                }
            }
        }
        return mAlarmManager
    }

    private fun getTelephonyManager(): TelephonyManager? {
        if (mTelephonyManager == null) {
            try {
                mTelephonyManager = mContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Logger.log("PixelXpert Error getting telephony manager")
                    Logger.log(t)
                }
            }
        }
        return mTelephonyManager
    }

    private fun getVibrationManager(): VibratorManager? {
        if (mVibrationManager == null) {
            try {
                mVibrationManager = mContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mHasVibrator = mVibrationManager?.defaultVibrator?.hasVibrator() ?: false
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Logger.log("PixelXpert Error getting vibrator")
                    Logger.log(t)
                }
            }
        }
        return mVibrationManager
    }

    private fun getNetworkStatsManager(): NetworkStatsManager? {
        if (mNetworkStatsManager == null) {
            try {
                mNetworkStatsManager = mContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Logger.log("PixelXpert Error getting network stats")
                    Logger.log(t)
                }
            }
        }
        return mNetworkStatsManager
    }

    private fun getCameraManager(): CameraManager? {
        if (mCameraManager == null) {
            try {
                val thread = HandlerThread("", THREAD_PRIORITY_BACKGROUND).apply { start() }
                mHandler = Handler(thread.looper)
                mCameraManager = mContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

                mSetTorchModeMethod = CameraManager::class.java.getMethod(
                    "setTorchMode",
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )

                mCameraManager?.registerTorchCallback(object : TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        super.onTorchModeChanged(cameraId, enabled)
                        isTorchOn = enabled
                    }
                }, mHandler)

                mCameraManager?.registerTorchCallback(object : TorchCallback() {
                    override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
                        informFlashListeners()
                    }
                }, mHandler)
            } catch (t: Throwable) {
                mCameraManager = null
                if (BuildConfig.DEBUG) Logger.log(t)
            }
        }
        return mCameraManager
    }

    private fun getDownloadManager(): DownloadManager? {
        if (mDownloadManager == null) {
            mDownloadManager = mContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        }
        return mDownloadManager
    }

    private fun getWindowManager(): WindowManager? {
        if (mWindowManager == null) {
            try {
                mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Logger.log(t)
            }
        }
        return mWindowManager
    }

    private fun getUserManager(): UserManager? {
        if (mUserManager == null) {
            try {
                mUserManager = mContext.getSystemService(Context.USER_SERVICE) as? UserManager
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Logger.log(t)
            }
        }
        return mUserManager
    }

    private fun toggleMuteInternal() {
        try {
            val am = getAudioManager() ?: return
            if (am.isStreamMute(STREAM_MUSIC)) {
                val maxVol = am.getStreamMaxVolume(STREAM_MUSIC)
                val minVol = am.getStreamMinVolume(STREAM_MUSIC)
                val sliderInt = Xprefs.getSliderInt("UnMuteVolumePCT", 50)
                val unMuteVolume = ((maxVol - minVol) * (sliderInt / 100f)).roundToInt()
                am.setStreamVolume(STREAM_MUSIC, unMuteVolume, 0)
            } else {
                am.setStreamVolume(STREAM_MUSIC, 0, 0)
            }
        } catch (ignored: Throwable) {}
    }

    @SuppressLint("DiscouragedApi")
    private fun resourceIdOfInternal(name: String, type: String): Int {
        return mContext.resources.getIdentifier(name, type, mContext.packageName)
    }

    fun interface ChangeListener {
        fun onChanged(newVal: Int)
    }
}
