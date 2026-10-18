package sh.siava.pixelxpert.xposed.modpacks.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.os.VibrationAttributes.USAGE_ACCESSIBILITY
import android.os.VibrationEffect.EFFECT_TICK
import android.provider.Settings
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_CAMERA
import android.view.KeyEvent.KEYCODE_POWER
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import android.view.ViewConfiguration
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import org.apache.commons.lang3.SystemProperties
import sh.siava.pixelxpert.xposed.XPrefs
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.AudioManager
import sh.siava.pixelxpert.xposed.utils.SystemUtils.CameraManager
import sh.siava.pixelxpert.xposed.utils.SystemUtils.PowerManager
import sh.siava.pixelxpert.xposed.utils.SystemUtils.getFlashStrengthPCT
import sh.siava.pixelxpert.xposed.utils.SystemUtils.isFlashOn
import sh.siava.pixelxpert.xposed.utils.SystemUtils.sleep
import sh.siava.pixelxpert.xposed.utils.SystemUtils.threadSleep
import sh.siava.pixelxpert.xposed.utils.SystemUtils.toggleFlash
import sh.siava.pixelxpert.xposed.utils.SystemUtils.vibrate
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod
import kotlin.concurrent.thread

@FrameworkModPack
class ScreenOffKeys(context: Context) : XposedModPack(context) {

    companion object {
        const val PHYSICAL_ACTION_NONE = -1
        const val PHYSICAL_ACTION_DEFAULT = 0
        const val PHYSICAL_ACTION_TORCH = 1
        const val PHYSICAL_ACTION_CAMERA = 2
        const val PHYSICAL_ACTION_ASSISTANT = 3
        const val PHYSICAL_ACTION_WALLET = 4
        const val PHYSICAL_ACTION_PLAY_PAUSE = 5
        const val PHYSICAL_ACTION_MEDIA_NEXT = 6
        const val PHYSICAL_ACTION_MEDIA_PREV = 7

        const val WAKE_REASON_POWER_BUTTON = 1
        const val CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP = 1
        private const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6
        const val ACTION_COMPLETE = 1

        private val sIsExecutingGesture = ThreadLocal.withInitial { false }

        private var longPressPowerButtonScreenOff = 0
        private var longPressPowerButtonScreenOn = 0
        private var doublePressPowerButtonScreenOff = 0
        private var doublePressPowerButtonScreenOn = 0
        private var longPressVolumeUpButtonScreenOff = 0
        private var longPressVolumeDownButtonScreenOff = 0
        private var animateFlashlight = false
        private var controlFlashWithVolKeys = false
    }

    private var launchAssistActionMethod: ReflectedMethod? = null
    private var windowMan: Any? = null
    private var mWakeTime: Long = 0

    private var mVolumeLongPress = VolumeLongPressRunnable(PHYSICAL_ACTION_DEFAULT)
    private var mFlashAdjustRunnable = FlashAdjustLongRunnable(KEYCODE_VOLUME_DOWN)
    private val mLock = Any()
    private var mKeyIsDown = false
    private var mLoopRan = false

    override fun onPreferenceUpdated(vararg key: String) {
        try {
            longPressPowerButtonScreenOff = Xprefs.getString("longPressPowerButtonScreenOff", "0")?.toIntOrNull() ?: 0
            longPressPowerButtonScreenOn = Xprefs.getString("longPressPowerButtonScreenOn", "0")?.toIntOrNull() ?: 0

            doublePressPowerButtonScreenOff = Xprefs.getString("doublePressPowerButtonScreenOff", "0")?.toIntOrNull() ?: 0
            doublePressPowerButtonScreenOn = Xprefs.getString("doublePressPowerButtonScreenOn", "0")?.toIntOrNull() ?: 0

            longPressVolumeUpButtonScreenOff = Xprefs.getString("longPressVolumeUpButtonScreenOff", "0")?.toIntOrNull() ?: 0
            longPressVolumeDownButtonScreenOff = Xprefs.getString("longPressVolumeDownButtonScreenOff", "0")?.toIntOrNull() ?: 0

            controlFlashWithVolKeys = Xprefs.getBoolean("controlFlashWithVolKeys", false)
            animateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false)

            CameraManager() // init CameraManager to listen to flash status
            ensureDoubleTapPowerEnabledIfNeeded()
        } catch (ignored: Throwable) {
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val phoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")
            val powerKeyRuleClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager\$PowerKeyRule")
            val gestureLauncherServiceClass = ReflectedClass.of("com.android.server.GestureLauncherService")

            launchAssistActionMethod = ReflectedMethod.ofName(phoneWindowManagerClass, "launchAssistAction")

            gestureLauncherServiceClass.before("handleCameraGesture").run { hookParam ->
                if (sIsExecutingGesture.get()) return@run

                val screenIsOn = screenIsOn()
                val action = resolveAction(KEYCODE_CAMERA, screenIsOn)

                if (action == PHYSICAL_ACTION_DEFAULT) {
                    val sysAction = getSystemDoubleTapPowerAction()
                    if (sysAction == 1) { // 1 = Wallet
                        val handled = launchAction(PHYSICAL_ACTION_WALLET, screenIsOn, true)
                        if (handled) {
                            hookParam.result = true
                            return@run
                        }
                    } else if (!isSystemDoubleTapPowerGestureEnabled()) {
                        hookParam.result = true
                        return@run
                    }
                    return@run
                }

                if (action != PHYSICAL_ACTION_CAMERA) {
                    val handled = launchAction(action, screenIsOn, true)
                    if (handled) {
                        hookParam.result = true
                    }
                }
            }

            try {
                gestureLauncherServiceClass.before("handleWalletGesture").run { hookParam ->
                    if (sIsExecutingGesture.get()) return@run

                    val screenIsOn = screenIsOn()
                    val action = resolveAction(KEYCODE_CAMERA, screenIsOn)

                    if (action == PHYSICAL_ACTION_DEFAULT) {
                        if (!isSystemDoubleTapPowerGestureEnabled()) {
                            hookParam.result = true
                        }
                        return@run
                    }

                    if (action != PHYSICAL_ACTION_WALLET) {
                        val handled = launchAction(action, screenIsOn, true)
                        if (handled) {
                            hookParam.result = true
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }

            phoneWindowManagerClass
                .after("enableScreen")
                .run { hookParam ->
                    windowMan = hookParam.thisObject
                    ensureDoubleTapPowerEnabledIfNeeded()
                }

            powerKeyRuleClass
                .before("onLongPress")
                .run { hookParam ->
                    val actionType = try {
                        val arg0 = hookParam.args.getOrNull(0)
                        if (arg0 != null) {
                            callMethod(arg0, "getAction") as? Int ?: ACTION_COMPLETE
                        } else {
                            ACTION_COMPLETE
                        }
                    } catch (ignored: Throwable) {
                        ACTION_COMPLETE
                    }

                    val screenIsOn = screenIsOn()
                    val customAction = resolveAction(KEYCODE_POWER, screenIsOn)

                    if (customAction != PHYSICAL_ACTION_DEFAULT) {
                        hookParam.result = null

                        if (actionType == ACTION_COMPLETE && customAction != PHYSICAL_ACTION_NONE) {
                            launchAction(customAction, screenIsOn, false)
                        }
                    }
                }

            phoneWindowManagerClass
                .before("startedWakingUp")
                .run { hookParam ->
                    for (arg in hookParam.args) {
                        if (arg is Int && arg == WAKE_REASON_POWER_BUTTON) {
                            mWakeTime = SystemClock.uptimeMillis()
                            break
                        }
                    }
                }

            phoneWindowManagerClass
                .before("interceptKeyBeforeQueueing")
                .run { hookParam ->
                    try {
                        val event = hookParam.args.getOrNull(0) as? KeyEvent ?: return@run
                        val keyCode = event.keyCode
                        val thisObj = hookParam.thisObject ?: return@run

                        if ((keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN)
                            && controlFlashWithVolKeys
                            && isFlashOn()
                        ) {
                            val handler = getObjectField(thisObj, "mHandler") as? Handler
                            if (handler != null) {
                                handleFlashKeys(event, handler)
                            }
                            hookParam.result = 0
                            return@run
                        }

                        if (!deviceIsInteractive() &&
                            ((keyCode == KEYCODE_VOLUME_UP && longPressVolumeUpButtonScreenOff != PHYSICAL_ACTION_DEFAULT) ||
                                    (keyCode == KEYCODE_VOLUME_DOWN && longPressVolumeDownButtonScreenOff != PHYSICAL_ACTION_DEFAULT))
                        ) {
                            val handler = getObjectField(thisObj, "mHandler") as? Handler ?: return@run

                            when (event.action) {
                                KeyEvent.ACTION_UP -> {
                                    if (handler.hasCallbacks(mVolumeLongPress)) {
                                        AudioManager()?.adjustStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            if (keyCode == KEYCODE_VOLUME_DOWN) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
                                            0
                                        )
                                        handler.removeCallbacks(mVolumeLongPress)
                                        hookParam.result = 0
                                    }
                                }
                                KeyEvent.ACTION_DOWN -> {
                                    val action = resolveAction(keyCode, false)
                                    mVolumeLongPress = VolumeLongPressRunnable(action)
                                    if (isActionLaunchable(action)) {
                                        handler.postDelayed(mVolumeLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                                        hookParam.result = 0
                                    }
                                }
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun handleFlashKeys(event: KeyEvent, handler: Handler) {
        when (event.action) {
            ACTION_DOWN -> {
                mFlashAdjustRunnable = FlashAdjustLongRunnable(event.keyCode)
                synchronized(mLock) {
                    mKeyIsDown = true
                    mLoopRan = false
                }
                handler.postDelayed(mFlashAdjustRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            ACTION_UP -> {
                synchronized(mLock) {
                    mKeyIsDown = false
                }
                if (handler.hasCallbacks(mFlashAdjustRunnable)) {
                    handler.removeCallbacks(mFlashAdjustRunnable)
                }
                synchronized(mLock) {
                    if (!mLoopRan) {
                        adjustFlashStep(event.keyCode)
                    }
                }
            }
        }
    }

    private fun adjustFlashStep(keyCode: Int) {
        val volSteps = try {
            SystemProperties.getInt("ro.config.media_vol_steps") { 10 }
        } catch (t: Throwable) {
            10
        }

        val step = (if (keyCode == KEYCODE_VOLUME_UP) 1f else -1f) / volSteps
        val newLevel = SystemUtils.getFlashlightLevel(getFlashStrengthPCT() + step)
        SystemUtils.setFlash(true, newLevel, false)
    }

    private fun resolveAction(keyCode: Int, screenIsOn: Boolean): Int {
        val flashIsOn = isFlashOn()

        return when (keyCode) {
            KEYCODE_CAMERA -> {
                if (doublePressPowerButtonScreenOff == PHYSICAL_ACTION_TORCH && flashIsOn) {
                    PHYSICAL_ACTION_TORCH
                } else if (screenIsOn) {
                    doublePressPowerButtonScreenOn
                } else {
                    doublePressPowerButtonScreenOff
                }
            }
            KEYCODE_VOLUME_DOWN -> longPressVolumeDownButtonScreenOff
            KEYCODE_VOLUME_UP -> longPressVolumeUpButtonScreenOff
            KEYCODE_POWER -> {
                if (longPressPowerButtonScreenOff == PHYSICAL_ACTION_TORCH && flashIsOn) {
                    PHYSICAL_ACTION_TORCH
                } else if (screenIsOn) {
                    longPressPowerButtonScreenOn
                } else {
                    longPressPowerButtonScreenOff
                }
            }
            else -> PHYSICAL_ACTION_DEFAULT
        }
    }

    private fun isActionLaunchable(action: Int): Boolean {
        return when (action) {
            PHYSICAL_ACTION_MEDIA_NEXT, PHYSICAL_ACTION_MEDIA_PREV ->
                AudioManager()?.isMusicActive == true
            else -> true
        }
    }

    private fun screenIsOn(): Boolean {
        return SystemClock.uptimeMillis() - mWakeTime > 1000
    }

    private fun deviceIsInteractive(): Boolean {
        return PowerManager()?.isInteractive == true
    }

    private fun launchAction(action: Int, screenIsOn: Boolean, delaySleep: Boolean): Boolean {
        try {
            var handled = false
            var shouldSleep = true

            when (action) {
                PHYSICAL_ACTION_NONE -> {
                    handled = true
                }
                PHYSICAL_ACTION_TORCH -> {
                    toggleFlash(animateFlashlight)
                    handled = true
                }
                PHYSICAL_ACTION_CAMERA -> {
                    try {
                        val gestureLauncherService = windowMan?.let { getObjectField(it, "mGestureLauncherService") }
                        sIsExecutingGesture.set(true)
                        try {
                            handled = callMethod(gestureLauncherService, "handleCameraGesture", false, CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) as? Boolean ?: false
                        } finally {
                            sIsExecutingGesture.set(false)
                        }
                        shouldSleep = false
                    } catch (ignored: Throwable) {
                    }
                }
                PHYSICAL_ACTION_WALLET -> {
                    try {
                        val gestureLauncherService = windowMan?.let { getObjectField(it, "mGestureLauncherService") }
                        sIsExecutingGesture.set(true)
                        try {
                            handled = callMethod(gestureLauncherService, "handleWalletGesture") as? Boolean ?: false
                        } catch (ignored: Throwable) {
                        } finally {
                            sIsExecutingGesture.set(false)
                        }
                        if (!handled) {
                            try {
                                val sbm = ReflectedClass.of("com.android.server.LocalServices")
                                    .callStaticMethod("getService", ReflectedClass.of("com.android.server.statusbar.StatusBarManagerInternal").clazz)
                                if (sbm != null) {
                                    callMethod(sbm, "onWalletLaunchGestureDetected")
                                    handled = true
                                }
                            } catch (ignored: Throwable) {
                            }
                        }
                        if (!handled) {
                            try {
                                val walletIntent = Intent("android.service.quickaccesswallet.action.VALUABLES").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                val ctx = getSafeContext()
                                if (ctx != null) {
                                    ctx.startActivity(walletIntent)
                                    handled = true
                                }
                            } catch (ignored: Throwable) {
                            }
                        }
                        shouldSleep = false
                    } catch (ignored: Throwable) {
                    }
                }
                PHYSICAL_ACTION_ASSISTANT -> {
                    try {
                        val wm = windowMan
                        if (wm != null) {
                            launchAssistActionMethod?.invoke(wm, null, -2, SystemClock.uptimeMillis(), INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS)
                            handled = true
                            shouldSleep = false
                        }
                    } catch (ignored: Throwable) {
                    }
                }
                PHYSICAL_ACTION_PLAY_PAUSE -> {
                    dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    handled = true
                }
                PHYSICAL_ACTION_MEDIA_NEXT -> {
                    if (AudioManager()?.isMusicActive == true) {
                        dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    }
                    handled = true
                }
                PHYSICAL_ACTION_MEDIA_PREV -> {
                    if (AudioManager()?.isMusicActive == true) {
                        dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                    handled = true
                }
            }

            if (handled) {
                if (action != PHYSICAL_ACTION_NONE) {
                    vibrate(EFFECT_TICK, USAGE_ACCESSIBILITY)
                }
                if (!screenIsOn && shouldSleep) {
                    thread {
                        if (delaySleep) {
                            threadSleep(500)
                        }
                        sleep()
                    }
                }
            }

            return handled
        } catch (ignored: Throwable) {
        }
        return false
    }

    private fun dispatchAudioKey(keyCode: Int) {
        val am = AudioManager() ?: return
        am.dispatchMediaKeyEvent(KeyEvent(ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(ACTION_UP, keyCode))
    }

    private fun getSafeContext(): Context? {
        if (mContext != null) return mContext
        if (windowMan != null) {
            try {
                val gls = getObjectField(windowMan, "mGestureLauncherService")
                if (gls != null) {
                    return getObjectField(gls, "mContext") as? Context
                }
            } catch (ignored: Throwable) {
            }
        }
        return null
    }

    private fun isSystemDoubleTapPowerGestureEnabled(): Boolean {
        try {
            val context = getSafeContext()
            if (context != null) {
                val enabled = Settings.Secure.getInt(
                    context.contentResolver,
                    "double_tap_power_button_gesture_enabled",
                    -1
                )
                if (enabled != -1) {
                    return enabled == 1
                }
                val cameraDisabled = Settings.Secure.getInt(
                    context.contentResolver,
                    "camera_double_tap_power_gesture_disabled",
                    0
                )
                return cameraDisabled == 0
            }
        } catch (ignored: Throwable) {
        }
        return true
    }

    private fun getSystemDoubleTapPowerAction(): Int {
        try {
            val context = getSafeContext()
            if (context != null) {
                return Settings.Secure.getInt(
                    context.contentResolver,
                    "double_tap_power_button_gesture",
                    0
                )
            }
        } catch (ignored: Throwable) {
        }
        return 0
    }

    private fun isDoublePressRemapped(): Boolean {
        return doublePressPowerButtonScreenOff != PHYSICAL_ACTION_DEFAULT
                || doublePressPowerButtonScreenOn != PHYSICAL_ACTION_DEFAULT
    }

    private fun ensureDoubleTapPowerEnabledIfNeeded() {
        if (windowMan != null && isDoublePressRemapped()) {
            try {
                val gls = getObjectField(windowMan, "mGestureLauncherService")
                if (gls != null) {
                    val cameraEnabled = getObjectField(gls, "mCameraDoubleTapPowerEnabled") as? Boolean ?: false
                    var walletEnabled = false
                    try {
                        walletEnabled = getObjectField(gls, "mWalletDoubleTapPowerEnabled") as? Boolean ?: false
                    } catch (ignored: Throwable) {
                    }
                    if (!cameraEnabled && !walletEnabled) {
                        setObjectField(gls, "mCameraDoubleTapPowerEnabled", true)
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    inner class VolumeLongPressRunnable(private val action: Int) : Runnable {
        override fun run() {
            launchAction(action, screenIsOn = false, delaySleep = false)
        }
    }

    inner class FlashAdjustLongRunnable(private val keyCode: Int) : Runnable {
        override fun run() {
            synchronized(mLock) {
                mLoopRan = true
            }
            thread {
                try {
                    while (true) {
                        adjustFlashStep(keyCode)
                        Thread.sleep(100)
                        synchronized(mLock) {
                            if (!mKeyIsDown) return@thread
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
    }
}
