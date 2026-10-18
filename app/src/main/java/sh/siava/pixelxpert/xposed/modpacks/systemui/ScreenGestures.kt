// Credits of double tap to wake go to nijel8 @XDA Thanks!

package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.SystemClock.uptimeMillis
import android.os.VibrationAttributes.USAGE_ACCESSIBILITY
import android.os.VibrationEffect.EFFECT_TICK
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.sleep
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.thread

@SystemUIModPack
class ScreenGestures(context: Context) : XposedModPack(context) {

    private var doubleTap = false
    private var isDozing = false
    private var notificationPanelViewController: Any? = null
    private var timer: Timer? = null
    private var statusBarKeyguardViewManager: Any? = null
    private var dozeTouchTrigger: Any? = null
    private var keyguardInteractor: Any? = null
    private var shadeInteractorSceneContainerImpl: Any? = null
    private var lastKGSingleTap: Long = 0
    private var lockscreenDoubleTapToSleep: GestureDetector? = null

    companion object {
        private const val HOLD_DURATION: Long = 500
        private const val SHADE = 0
        private const val REASON_SENSOR_TAP = 9
        private const val REASON_SENSOR_PICKUP = 3
        private const val PULSE_REASON_INTENT = 0

        @JvmField
        var doubleTapToSleepStatusbarEnabled: Boolean = false
        private var doubleTapToSleepLockscreenEnabled = false
        private var doubleTapToWake = false
        private var holdScreenTorchEnabled = false

        private var tapToShowAmbient = false
        private var pickToShowAmbient = false
        private var turnedByTTT = false
        private var mDoubleTap = false
        private var disableLockScreenPill = false
    }

    override fun onPreferenceUpdated(vararg key: String) {
        doubleTapToWake = Xprefs.getBoolean("doubleTapToWake", false)
        holdScreenTorchEnabled = Xprefs.getBoolean("holdScreenTorchEnabled", false)
        doubleTapToSleepStatusbarEnabled = Xprefs.getBoolean("DoubleTapSleep", false)
        doubleTapToSleepLockscreenEnabled = Xprefs.getBoolean("DoubleTapSleepLockscreen", false)
        tapToShowAmbient = Xprefs.getBoolean("TapToShowAmbient", false)
        pickToShowAmbient = Xprefs.getBoolean("PickToShowAmbient", false)
        disableLockScreenPill = Xprefs.getBoolean("DisableLockScreenPill", false)
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        lockscreenDoubleTapToSleep = GestureDetector(mContext, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                sleep()
                return true
            }
        })

        val notificationShadeWindowViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationShadeWindowViewController")
        val notificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController")
        val dozeTriggersClass = ReflectedClass.of("com.android.systemui.doze.DozeTriggers")
        val phoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")
        val triggerSensorClass = ReflectedClass.of("com.android.systemui.doze.DozeSensors\$TriggerSensor")
        val defaultSettingsPopupMenuSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection")

        // A17QPR1 Scene implementation
        val sceneWindowRootViewClass = ReflectedClass.ofIfPossible("com.android.systemui.scene.ui.view.SceneWindowRootView")
        val shadeInteractorSceneContainerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl")
        val pulsingGestureListenerClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.PulsingGestureListener")
        val keyguardInteractorClass = ReflectedClass.of("com.android.systemui.keyguard.domain.interactor.KeyguardInteractor")
        val settingsMenuElementProviderClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.composable.elements.SettingsMenuElementProvider")

        shadeInteractorSceneContainerImplClass?.afterConstruction()?.run { runParam ->
            shadeInteractorSceneContainerImpl = runParam.thisObject
        }

        pulsingGestureListenerClass?.before("onSingleTapUp")?.run {
            lastKGSingleTap = SystemClock.uptimeMillis()
        }

        pulsingGestureListenerClass?.before("onDoubleTapEvent")?.run { runParam ->
            if (runParam.args.isEmpty()) {
                try {
                    val controller = notificationPanelViewController
                    if (isQSExpanded() || (controller != null && getBooleanField(controller, "mBouncerShowing"))) {
                        return@run
                    }
                } catch (_: Throwable) {}

                var dozingState = false
                try {
                    val controller = getObjectField<Any>(runParam.thisObject, "statusBarStateController")
                    if (controller != null) {
                        dozingState = callMethod(controller, "isDozing")
                    }
                } catch (_: Throwable) {
                    dozingState = isDozing
                }

                if (doubleTapToSleepLockscreenEnabled && !dozingState) {
                    sleep()
                }
            }
        }

        settingsMenuElementProviderClass?.before("SettingsMenu")?.run { runParam ->
            if (disableLockScreenPill) {
                runParam.result = null
            }
        }

        keyguardInteractorClass.afterConstruction().run { runParam ->
            keyguardInteractor = runParam.thisObject
        }

        sceneWindowRootViewClass?.before("dispatchTouchEvent")?.run { runParam ->
            if (keyguardNotShowingCompose()) return@run

            val ev = runParam.getArg<MotionEvent>(0)
            val action = ev.actionMasked

            if (isSingleTap() && action == ACTION_UP) {
                if (doubleTapToSleepLockscreenEnabled && !isDozing) {
                    sleep()
                }
            }

            if (!holdScreenTorchEnabled) return@run

            if (action == ACTION_DOWN || action == ACTION_MOVE) {
                if (turnedByTTT) {
                    ev.action = ACTION_DOWN
                }
                if (isSingleTap() && !SystemUtils.isFlashOn() && uptimeMillis() - ev.downTime > HOLD_DURATION) {
                    turnedByTTT = true

                    val powerManager = SystemUtils.PowerManager()
                    if (powerManager != null) {
                        callMethod<Any>(powerManager, "wakeUp", uptimeMillis())
                    }
                    SystemUtils.setFlash(true, false)
                    SystemUtils.vibrate(EFFECT_TICK, USAGE_ACCESSIBILITY)

                    thread {
                        while (turnedByTTT) {
                            try {
                                SystemUtils.threadSleep(200)
                                if (keyguardNotShowingCompose()) {
                                    turnOffTTT()
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } else if (turnedByTTT) {
                turnOffTTT()
            }
        }

        phoneStatusBarViewClass
            .before("onTouchEvent")
            .run { runParam ->
                if (!doubleTapToSleepStatusbarEnabled) return@run

                val controller = notificationPanelViewController
                if (controller == null ||
                    (!getBooleanField(controller, "mPulsing") &&
                     !getBooleanField(controller, "mDozing") &&
                     getObjectField<Int>(controller, "mBarState") == SHADE &&
                     callMethod<Boolean>(controller, "isFullyCollapsed"))
                ) {
                    val event = runParam.args.lastOrNull() as? MotionEvent
                    if (event != null) {
                        lockscreenDoubleTapToSleep?.onTouchEvent(event)
                    }
                }
            }

        triggerSensorClass
            .afterConstruction()
            .run { runParam ->
                if (getObjectField<Any>(runParam.thisObject, "mPulseReason") == REASON_SENSOR_TAP) {
                    dozeTouchTrigger = runParam.thisObject
                }
            }

        dozeTriggersClass
            .before("onSensor")
            .run { runParam ->
                if ((tapToShowAmbient && !doubleTapToWake && runParam.args[0] == REASON_SENSOR_TAP) ||
                    (pickToShowAmbient && runParam.args[0] == REASON_SENSOR_PICKUP)
                ) {
                    showAmbientDisplay(runParam.thisObject)
                    runParam.result = null
                    return@run
                }

                if (doubleTapToWake && (runParam.args[0] as? Int) == REASON_SENSOR_TAP) {
                    if (!mDoubleTap) {
                        dozeTouchTrigger?.let { callMethod<Any>(it, "updateListening") }
                        mDoubleTap = true
                        timer = Timer().apply {
                            schedule(object : TimerTask() {
                                override fun run() {
                                    mDoubleTap = false
                                    if (tapToShowAmbient) {
                                        showAmbientDisplay(runParam.thisObject)
                                    }
                                }
                            }, 400)
                        }
                        runParam.result = null
                    } else {
                        timer?.cancel()
                        mDoubleTap = false
                    }
                }
            }

        defaultSettingsPopupMenuSectionClass
            .before("bindData")
            .run { runParam ->
                if (disableLockScreenPill) {
                    runParam.result = null
                }
            }

        notificationShadeWindowViewControllerClass
            .afterConstruction()
            .run { runParam ->
                thread {
                    try {
                        SystemUtils.threadSleep(5000)
                        setHooks(runParam)
                    } catch (_: Throwable) {}
                }
            }

        notificationPanelViewControllerClass
            .afterConstruction()
            .run { runParam ->
                notificationPanelViewController = runParam.thisObject
                statusBarKeyguardViewManager = getObjectField(runParam.thisObject, "mStatusBarKeyguardViewManager")
            }

        notificationPanelViewControllerClass
            .after("createTouchHandler")
            .run { runParam ->
                notificationPanelViewController = runParam.thisObject
            }
    }

    private fun showAmbientDisplay(dozeTrigger: Any) {
        if (mDoubleTap) return

        Handler(Looper.getMainLooper()).post {
            callMethod<Any>(dozeTrigger, "requestPulse", PULSE_REASON_INTENT, false, null)
        }
    }

    private fun isSingleTap(): Boolean {
        return lastKGSingleTap + HOLD_DURATION * 2 > SystemClock.uptimeMillis()
    }

    private fun setHooks(param: HookHelper.RunParam) {
        val mPulsingWakeupGestureHandler = getObjectField<Any>(param.thisObject, "mPulsingWakeupGestureHandler") ?: return
        val mListener = getObjectField<Any>(mPulsingWakeupGestureHandler, "mListener") ?: return
        val mStatusBarStateController = getObjectField<Any>(param.thisObject, "mStatusBarStateController")

        val listenerClass = ReflectedClass.of(mListener.javaClass)

        listenerClass
            .before("onSingleTapUp")
            .run { param3 ->
                if (doubleTapToWake) {
                    param3.result = false
                }
            }

        listenerClass
            .before("onDoubleTapEvent")
            .run {
                val controller = notificationPanelViewController
                if (isQSExpanded() || (controller != null && getBooleanField(controller, "mBouncerShowing"))) {
                    return@run
                }
                doubleTap = true
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        doubleTap = false
                    }
                }, HOLD_DURATION * 2)

                if (mStatusBarStateController != null) {
                    isDozing = callMethod(mStatusBarStateController, "isDozing")
                }
            }

        ReflectedClass.of(mPulsingWakeupGestureHandler.javaClass)
            .before("onTouchEvent")
            .run { param1 ->
                if (keyguardNotShowing(statusBarKeyguardViewManager)) {
                    return@run
                }
                val ev = param1.args[0] as? MotionEvent ?: return@run
                val action = ev.actionMasked

                if (doubleTap && action == ACTION_UP) {
                    if (doubleTapToSleepLockscreenEnabled && !isDozing) {
                        sleep()
                    }
                    doubleTap = false
                }

                if (!holdScreenTorchEnabled) return@run

                if (action == ACTION_DOWN || action == ACTION_MOVE) {
                    if (doubleTap || turnedByTTT) {
                        ev.action = ACTION_DOWN
                    }
                    if (doubleTap && !SystemUtils.isFlashOn() && uptimeMillis() - ev.downTime > HOLD_DURATION) {
                        turnedByTTT = true

                        val powerManager = SystemUtils.PowerManager()
                        if (powerManager != null) {
                            callMethod<Any>(powerManager, "wakeUp", uptimeMillis())
                        }
                        SystemUtils.setFlash(true, false)
                        SystemUtils.vibrate(EFFECT_TICK, USAGE_ACCESSIBILITY)

                        thread {
                            while (turnedByTTT) {
                                try {
                                    SystemUtils.threadSleep(200)
                                    if (keyguardNotShowing(statusBarKeyguardViewManager)) {
                                        turnOffTTT()
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                } else if (turnedByTTT) {
                    turnOffTTT()
                }
            }
    }

    private fun isQSExpanded(): Boolean {
        val controller = notificationPanelViewController ?: return false
        return try {
            callMethod(controller, "isShadeFullyExpanded")
        } catch (_: Throwable) {
            false
        }
    }

    private fun keyguardNotShowing(manager: Any?): Boolean {
        if (manager == null) return false
        return try {
            !(callMethod<Boolean>(manager, "isShowing"))
        } catch (_: Throwable) {
            !getBooleanField(manager, "mLastShowing")
        }
    }

    private fun keyguardNotShowingCompose(): Boolean {
        return try {
            val interactor = keyguardInteractor ?: return false
            val isShowingFlow = getObjectField<Any>(interactor, "isKeyguardShowing")
            val isShowing = callMethod<Any>(isShowingFlow, "getValue")
            val primaryBouncerFlow = getObjectField<Any>(interactor, "primaryBouncerShowing")
            val primaryBouncerShowing = callMethod<Any>(primaryBouncerFlow, "getValue")

            val shadeInteractor = shadeInteractorSceneContainerImpl
            val isAnyExpanded = if (shadeInteractor != null) {
                val flow = callMethod<Any>(shadeInteractor, "isAnyExpanded")
                callMethod<Boolean>(flow, "getValue")
            } else {
                false
            }

            isShowing == false || primaryBouncerShowing == true || isAnyExpanded
        } catch (_: Throwable) {
            false
        }
    }

    private fun turnOffTTT() {
        turnedByTTT = false
        SystemUtils.setFlash(false, false)
    }
}
