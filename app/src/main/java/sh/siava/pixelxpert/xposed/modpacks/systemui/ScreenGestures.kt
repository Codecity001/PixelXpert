package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.view.GestureDetector
import android.view.MotionEvent
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Screen-off and lockscreen gestures: double tap to sleep on the status bar
 * and on the lockscreen, double tap to wake, tap or pick up to show the
 * ambient display, and press-and-hold on the screen to light the torch.
 *
 * SystemUI spreads those interactions over several pipelines - the status bar
 * view, the scene-window touch dispatch, the pulsing wakeup gesture handler
 * and the doze tap sensor - so the pack hooks every one it can resolve and
 * keeps its own timing state (the double-tap window, the single-tap grace
 * period, the latched "torch turned by tap" flag) in the fields below. Builds
 * that lack a class (everything scene-container related arrived in 17QPR1+)
 * are looked up tolerantly and simply hook nothing, and the 5 second delay
 * before installing the handler hooks works around LSPosed not
 * seeing those methods earlier.
 */
@SystemUIModPack
class ScreenGestures(context: Context) : XposedModPack(context) {

	companion object {
		private const val HOLD_DURATION = 500L
		private const val SHADE = 0 //frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/StatusBarState.java - screen unlocked - pulsing means screen is locked - shade locked means (Q)QS is open on lockscreen

		private const val REASON_SENSOR_TAP = 9 // from DozeTriggers
		private const val REASON_SENSOR_PICKUP = 3 // from DozeTriggers
		private const val PULSE_REASON_INTENT = 0 //from DozeLog

		@JvmStatic
		var doubleTapToSleepStatusbarEnabled = false
		private var doubleTapToSleepLockscreenEnabled = false
		private var doubleTapToWake = false
		private var holdScreenTorchEnabled = false

		private var TapToShowAmbient = false
		private var PickToShowAmbient = false
		private var turnedByTTT = false
		private var mDoubleTap = false  //double tap to wake when AOD off
		private var DisableLockScreenPill = false
	}

	private var doubleTap = false //double tap event for TTT

	private lateinit var mLockscreenDoubleTapToSleep: GestureDetector //event callback for double tap to sleep detection of statusbar only

	private var isDozing = false //determiner for wakeup or sleep decision
	private var NotificationPanelViewController: Any? = null
	private val handler = Handler(Looper.getMainLooper())
	private var mDoubleTapReset: Runnable? = null
	private var mStatusBarKeyguardViewManager: Any? = null
	private var mDozeTouchTrigger: Any? = null
	private var mKeyguardInteractor: Any? = null
	private var mShadeInteractorSceneContainerImpl: Any? = null
	private var mLastKGSingleTap = 0L

	override fun onPreferenceUpdated(vararg key: String?) {
		doubleTapToWake = Xprefs.getBoolean("doubleTapToWake", false)
		holdScreenTorchEnabled = Xprefs.getBoolean("holdScreenTorchEnabled", false)
		doubleTapToSleepStatusbarEnabled = Xprefs.getBoolean("DoubleTapSleep", false)
		doubleTapToSleepLockscreenEnabled = Xprefs.getBoolean("DoubleTapSleepLockscreen", false)
		TapToShowAmbient = Xprefs.getBoolean("TapToShowAmbient", false)
		PickToShowAmbient = Xprefs.getBoolean("PickToShowAmbient", false)
		DisableLockScreenPill = Xprefs.getBoolean("DisableLockScreenPill", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		mLockscreenDoubleTapToSleep = GestureDetector(mContext, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDoubleTap(e: MotionEvent): Boolean {
				SystemUtils.sleep()
				return true
			}
		})

		val NotificationShadeWindowViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationShadeWindowViewController")
		val NotificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController")
		val DozeTriggersClass = ReflectedClass.of("com.android.systemui.doze.DozeTriggers")
		val PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")
		val TriggerSensorClass = ReflectedClass.of("com.android.systemui.doze.DozeSensors\$TriggerSensor")
		val DefaultSettingsPopupMenuSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection")


		//A17QPR1 Scene implementation
		val SceneWindowRootViewClass = ReflectedClass.ofIfPossible("com.android.systemui.scene.ui.view.SceneWindowRootView")
		val ShadeInteractorSceneContainerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl")
		val PulsingGestureListenerClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.PulsingGestureListener")
		val KeyguardInteractorClass = ReflectedClass.of("com.android.systemui.keyguard.domain.interactor.KeyguardInteractor")
		val SettingsMenuElementProviderClass = ReflectedClass.ofIfPossible("com.android.systemui.keyguard.ui.composable.elements.SettingsMenuElementProvider")

		ShadeInteractorSceneContainerImplClass //used to know if shade is open or not
			.afterConstruction()
			.run { param -> mShadeInteractorSceneContainerImpl = param.thisObject }

		PulsingGestureListenerClass //used to detect when a real single tap done on keyguard
			.before("onSingleTapUp")
			.run { param -> mLastKGSingleTap = SystemClock.uptimeMillis() }

		PulsingGestureListenerClass // For A17QPR1+ Lockscreen double tap to sleep
			.before("onDoubleTapEvent")
			.run { param ->
				if (param.args.isEmpty()) { // hook the no-arg method
					try {
						if (isQSExpanded() || getBooleanField(NotificationPanelViewController, "mBouncerShowing")) {
							return@run // don't sleep if QS or bouncer is open
						}
					} catch (ignored: Throwable) {
						// Fields missing in A17QPR1+. We'll just assume they are false.
					}

					var dozing = false
					try {
						val controller = getObjectField(param.thisObject, "statusBarStateController")
						if (controller != null) {
							dozing = callMethod(controller, "isDozing") as Boolean
						}
					} catch (ignored: Throwable) {
						dozing = isDozing
					}

					if (doubleTapToSleepLockscreenEnabled && !dozing) {
						SystemUtils.sleep()
					}
				}
			}


		SettingsMenuElementProviderClass //preventing initialization of settings pill on startup
			.before("SettingsMenu")
			.run { param ->
				if (DisableLockScreenPill)
					param.setResult(null)
			}

		KeyguardInteractorClass //used to know if KG is showing
			.afterConstruction()
			.run { param -> mKeyguardInteractor = param.thisObject }

		SceneWindowRootViewClass //gestures on Scene implementation
			.before("dispatchTouchEvent")
			.run { param ->
				if (keyguardNotShowingCompose()) return@run

				val ev: MotionEvent = param.getArg(0)

				val action = ev.getActionMasked()

				if (isSingleTap() && action == MotionEvent.ACTION_UP) {
					if (doubleTapToSleepLockscreenEnabled && !isDozing)
						SystemUtils.sleep()
				}

				if (!holdScreenTorchEnabled) return@run

				if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
					if (turnedByTTT) //we really don't want to see swipe gestures during TTT
					{
						ev.setAction(MotionEvent.ACTION_DOWN)
					}
					if (isSingleTap() && !SystemUtils.isFlashOn() && SystemClock.uptimeMillis() - ev.getDownTime() > HOLD_DURATION) {
						turnedByTTT = true

						callMethod(SystemUtils.PowerManager(), "wakeUp", SystemClock.uptimeMillis())
						SystemUtils.setFlash(true, false)
						SystemUtils.vibrate(VibrationEffect.EFFECT_TICK, VibrationAttributes.USAGE_ACCESSIBILITY)

						Thread { //if keyguard is dismissed for any reason (face or udfps touch), then:
							while (turnedByTTT) {
								try {
									SystemUtils.threadSleep(200)
									if (keyguardNotShowingCompose()) {
										turnOffTTT()
									}
								} catch (ignored: Throwable) {
								}
							}
						}.start()
					}
				}
				else if (turnedByTTT) {
					turnOffTTT()
				}
			}

		PhoneStatusBarViewClass
			.before("onTouchEvent")
			.run { param ->
				if (!doubleTapToSleepStatusbarEnabled) return@run

				//noinspection ConstantValue
				if (NotificationPanelViewController == null || //if ViewController is null it means it's not initiated. meaning it's 17QPR1+
						    (!(getObjectField(NotificationPanelViewController, "mPulsing") as Boolean) && !(getObjectField(NotificationPanelViewController, "mDozing") as Boolean) && (getObjectField(NotificationPanelViewController, "mBarState") as Int) == SHADE && (callMethod(NotificationPanelViewController, "isFullyCollapsed") as Boolean))) {
					mLockscreenDoubleTapToSleep.onTouchEvent(param.args[param.args.size - 1] as MotionEvent)
				}
			}

		TriggerSensorClass
			.afterConstruction()
			.run { param ->
				if (getObjectField(param.thisObject, "mPulseReason").equals(REASON_SENSOR_TAP))
				{
					mDozeTouchTrigger = param.thisObject
				}

			}

		DozeTriggersClass
			.before("onSensor")
			.run { param ->
				if ((TapToShowAmbient && !doubleTapToWake && param.args[0]!!.equals(REASON_SENSOR_TAP)) ||
						(PickToShowAmbient && param.args[0]!!.equals(REASON_SENSOR_PICKUP))) {
					showAmbientDisplay(param.thisObject)
					param.setResult(null)
					return@run //don't proceed anymore
				}

				if (doubleTapToWake && (param.args[0] as Int) == REASON_SENSOR_TAP) {
					if (!mDoubleTap) {
						callMethod(mDozeTouchTrigger, "updateListening") //we wasted the event! let's listen again
						mDoubleTap = true
						mDoubleTapReset = Runnable {
							mDoubleTap = false
							if (TapToShowAmbient)
								showAmbientDisplay(param.thisObject)
						}
						handler.postDelayed(mDoubleTapReset!!, 400)

						param.setResult(null)
					}
					else
					{
						mDoubleTapReset?.let { handler.removeCallbacks(it) }
						mDoubleTap = false
					}
				}
			}

		DefaultSettingsPopupMenuSectionClass
			.before("bindData") //we prevent binding to happen in the first place. otherwise, very hard to control
			.run { param ->
				if (DisableLockScreenPill) {
					param.setResult(null)
				}
			}

		NotificationShadeWindowViewControllerClass
			.afterConstruction()
			.run { param ->
				Thread {
					try {
						SystemUtils.threadSleep(5000) //for some reason lsposed doesn't find methods in the class. so we'll hook to constructor and wait a bit!
						setHooks(param)
					}
					catch (ignored: Throwable) //probably 17QPR1+
					{
					}
				}.start()
			}

		NotificationPanelViewControllerClass
			.afterConstruction()
			.run { param ->
				NotificationPanelViewController = param.thisObject

				mStatusBarKeyguardViewManager = getObjectField(param.thisObject, "mStatusBarKeyguardViewManager")
			}

		NotificationPanelViewControllerClass
			.after("createTouchHandler")
			.run { param -> NotificationPanelViewController = param.thisObject }
	}

	private fun showAmbientDisplay(dozeTrigger: Any?) {
		if (mDoubleTap) return

		Handler(Looper.getMainLooper()).post { //call must be made from main thread
			callMethod(dozeTrigger, "requestPulse", PULSE_REASON_INTENT, false /* performedProxCheck */, null /* onPulseSuppressedListener */)
		}
	}

	private fun isSingleTap(): Boolean {
		return mLastKGSingleTap + HOLD_DURATION * 2 > SystemClock.uptimeMillis()
	}

	private fun setHooks(param: HookHelper.RunParam) {
		val mPulsingWakeupGestureHandler = getObjectField(param.thisObject, "mPulsingWakeupGestureHandler") //A13 R18

		val mListener = getObjectField(mPulsingWakeupGestureHandler, "mListener")

		val mStatusBarStateController = getObjectField(param.thisObject, "mStatusBarStateController")

		val listenerClass = ReflectedClass.of(mListener.javaClass)

		listenerClass
			.before("onSingleTapUp")
			.run { param3 ->
				if (doubleTapToWake)
					param3.setResult(false)
			} //A13 R18

		val listenerClass2 = ReflectedClass.of(mListener.javaClass)

		listenerClass2
			.before("onDoubleTapEvent")
			.run { param2 ->
				if (isQSExpanded() || getBooleanField(NotificationPanelViewController, "mBouncerShowing")) {
					return@run
				}
				doubleTap = true
				handler.postDelayed({ doubleTap = false }, HOLD_DURATION * 2)

				isDozing = callMethod(mStatusBarStateController, "isDozing") as Boolean
			} //A13 R18

		ReflectedClass.of(mPulsingWakeupGestureHandler.javaClass)
			.before("onTouchEvent")
			.run { param1 ->
				if (keyguardNotShowing(mStatusBarKeyguardViewManager)) {
					return@run
				}
				val ev = param1.args[0] as MotionEvent

				val action = ev.getActionMasked()

				if (doubleTap && action == MotionEvent.ACTION_UP) {
					if (doubleTapToSleepLockscreenEnabled && !isDozing)
						SystemUtils.sleep()
					doubleTap = false
				}

				if (!holdScreenTorchEnabled) return@run

				if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
					if (doubleTap || turnedByTTT) //we really don't want to see swipe gestures during TTT
					{
						ev.setAction(MotionEvent.ACTION_DOWN)
					}
					if (doubleTap && !SystemUtils.isFlashOn() && SystemClock.uptimeMillis() - ev.getDownTime() > HOLD_DURATION) {
						turnedByTTT = true

						callMethod(SystemUtils.PowerManager(), "wakeUp", SystemClock.uptimeMillis())
						SystemUtils.setFlash(true, false)
						SystemUtils.vibrate(VibrationEffect.EFFECT_TICK, VibrationAttributes.USAGE_ACCESSIBILITY)

						Thread { //if keyguard is dismissed for any reason (face or udfps touch), then:
							while (turnedByTTT) {
								try {
									SystemUtils.threadSleep(200)
									if (keyguardNotShowing(mStatusBarKeyguardViewManager)) {
										turnOffTTT()
									}
								} catch (ignored: Throwable) {
								}
							}
						}.start()
					}
				} else if (turnedByTTT) {
					turnOffTTT()
				}
			}
	}

	private fun isQSExpanded(): Boolean {
		return callMethod(NotificationPanelViewController, "isShadeFullyExpanded") as Boolean
	}

	private fun keyguardNotShowing(mStatusBarKeyguardViewManager: Any?): Boolean {
		return try {
			!(callMethod(mStatusBarKeyguardViewManager, "isShowing") as Boolean)
		} catch (ignored: Throwable) {
			!getBooleanField(mStatusBarKeyguardViewManager, "mLastShowing")
		}
	}

	private fun keyguardNotShowingCompose(): Boolean
	{
		return callMethod(getObjectField(mKeyguardInteractor, "isKeyguardShowing"), "getValue").equals(false)
					|| callMethod(getObjectField(mKeyguardInteractor, "primaryBouncerShowing"), "getValue").equals(true)
					|| (callMethod(callMethod(mShadeInteractorSceneContainerImpl, "isAnyExpanded"), "getValue") as Boolean)
	}

	private fun turnOffTTT() {
		turnedByTTT = false

		SystemUtils.setFlash(false, false)
	}
}
