package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.view.GestureDetector
import android.view.MotionEvent
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

/**
 * Screen-off and lockscreen gestures: double tap to sleep on the status bar
 * and on the lockscreen, double tap to wake, tap or pick up to show the
 * ambient display, and press-and-hold on the screen to light the torch.
 *
 * Covers both traditional view hierarchy and Compose Scene Container pipelines.
 */
@SystemUIModPack
class ScreenGestures(context: Context) : XposedModPack(context) {

	companion object {
		private const val HOLD_DURATION = 500L

		// frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/StatusBarState.java -
		// screen unlocked - pulsing means screen is locked - shade locked means (Q)QS is open on lockscreen
		private const val SHADE = 0

		private const val REASON_SENSOR_TAP = 9 // from DozeTriggers
		private const val REASON_SENSOR_PICKUP = 3 // from DozeTriggers
		private const val PULSE_REASON_INTENT = 0 // from DozeLog

		@JvmStatic
		var doubleTapToSleepStatusbarEnabled = false
		private var doubleTapToSleepLockscreenEnabled = false
		private var doubleTapToWake = false
		private var holdScreenTorchEnabled = false

		private var TapToShowAmbient = false
		private var PickToShowAmbient = false
		private var turnedByTTT = false
		private var mDoubleTap = false // double tap to wake when AOD off
		private var DisableLockScreenPill = false
	}

	// event callback for double tap to sleep detection of statusbar only
	private lateinit var mLockscreenDoubleTapToSleep: GestureDetector

	private var NotificationPanelViewController: Any? = null
	private val handler = Handler(Looper.getMainLooper())
	private var mDoubleTapReset: Runnable? = null
	private var mStatusBarKeyguardViewManager: Any? = null
	private var mDozeTouchTrigger: Any? = null
	private var mKeyguardInteractor: Any? = null
	private var mShadeInteractorSceneContainerImpl: Any? = null
	private var mStatusBarStateController: Any? = null
	private var mDozeTriggers: Any? = null
	private var mLastKGSingleTap = 0L

	private val isDozing: Boolean
		get() = try {
			callMethod(mStatusBarStateController, "isDozing") as? Boolean
				?: (NotificationPanelViewController?.let { getObjectField(it, "mDozing") as? Boolean })
				?: false
		} catch (_: Throwable) {
			false
		}

	override fun onPreferenceUpdated(vararg key: String?) {
		doubleTapToWake = Xprefs.getBoolean("doubleTapToWake", false)
		holdScreenTorchEnabled = Xprefs.getBoolean("holdScreenTorchEnabled", false)
		doubleTapToSleepStatusbarEnabled = Xprefs.getBoolean("DoubleTapSleep", false)
		doubleTapToSleepLockscreenEnabled = Xprefs.getBoolean("DoubleTapSleepLockscreen", false)
		TapToShowAmbient = Xprefs.getBoolean("TapToShowAmbient", false)
		PickToShowAmbient = Xprefs.getBoolean("PickToShowAmbient", false)
		DisableLockScreenPill = Xprefs.getBoolean("DisableLockScreenPill", false)

		if (!holdScreenTorchEnabled && turnedByTTT) {
			turnOffTTT()
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		mLockscreenDoubleTapToSleep = GestureDetector(
			mContext,
			object : GestureDetector.SimpleOnGestureListener() {
				override fun onDoubleTap(e: MotionEvent): Boolean {
					SystemUtils.sleep()
					return true
				}
			},
		)

		val NotificationPanelViewControllerClass = ReflectedClass.of(
			"com.android.systemui.shade.NotificationPanelViewController",
		)
		val DozeTriggersClass = ReflectedClass.of("com.android.systemui.doze.DozeTriggers")
		val PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")
		val TriggerSensorClass = ReflectedClass.of("com.android.systemui.doze.DozeSensors\$TriggerSensor")
		val DefaultSettingsPopupMenuSectionClass = ReflectedClass.of(
			"com.android.systemui.keyguard.ui.view.layout.sections.DefaultSettingsPopupMenuSection",
		)

		val StatusBarStateControllerImplClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.statusbar.StatusBarStateControllerImpl",
		)
		val SceneWindowRootViewClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.scene.ui.view.SceneWindowRootView",
		)
		val ShadeInteractorSceneContainerImplClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl",
		)
		val PulsingGestureListenerClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.shade.PulsingGestureListener",
		)
		val KeyguardInteractorClass = ReflectedClass.of(
			"com.android.systemui.keyguard.domain.interactor.KeyguardInteractor",
		)
		val SettingsMenuElementProviderClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.keyguard.ui.composable.elements.SettingsMenuElementProvider",
		)

		StatusBarStateControllerImplClass
			.afterConstruction()
			.run { param -> mStatusBarStateController = param.thisObject }

		ShadeInteractorSceneContainerImplClass // used to know if shade is open or not
			.afterConstruction()
			.run { param -> mShadeInteractorSceneContainerImpl = param.thisObject }

		PulsingGestureListenerClass
			.afterConstruction()
			.run { param ->
				if (mStatusBarStateController == null) {
					mStatusBarStateController = getObjectField(param.thisObject, "statusBarStateController")
				}
			}

		PulsingGestureListenerClass // used to detect real taps on keyguard / transition
			.before("onSingleTapUp")
			.run { param ->
				mLastKGSingleTap = SystemClock.uptimeMillis()

				if (doubleTapToWake || TapToShowAmbient) {
					param.setResult(false)

					if (TapToShowAmbient) {
						mDoubleTapReset?.let { handler.removeCallbacks(it) }
						val delay = if (doubleTapToWake) 400L else 0L
						mDoubleTapReset = Runnable {
							mDoubleTap = false
							showAmbientDisplay(mDozeTriggers)
						}
						handler.postDelayed(mDoubleTapReset!!, delay)
					}
				}
			}

		PulsingGestureListenerClass // For Lockscreen double tap to sleep & waking gesture intercept
			.before("onDoubleTapEvent")
			.run { param ->
				mDoubleTapReset?.let { handler.removeCallbacks(it) }

				if (param.args.isEmpty()) { // hook the no-arg method
					try {
						if (isQSExpanded() || getBooleanField(NotificationPanelViewController, "mBouncerShowing")) {
							return@run // don't sleep if QS or bouncer is open
						}
					} catch (ignored: Throwable) {
					}

					if (doubleTapToSleepLockscreenEnabled && !isDozing) {
						SystemUtils.sleep()
					}
				}
			}

		SettingsMenuElementProviderClass // preventing initialization of settings pill on startup
			.before("SettingsMenu")
			.run { param ->
				if (DisableLockScreenPill) {
					param.setResult(null)
				}
			}

		KeyguardInteractorClass // used to know if KG is showing
			.afterConstruction()
			.run { param -> mKeyguardInteractor = param.thisObject }

		val WindowRootTouchClass = if (SceneWindowRootViewClass.clazz != null) {
			SceneWindowRootViewClass
		} else {
			ReflectedClass.ofIfPossible("com.android.systemui.shade.NotificationShadeWindowView")
		}

		WindowRootTouchClass // gestures on Scene implementation or NotificationShadeWindowView (cd1a)
			.before("dispatchTouchEvent")
			.run { param ->
				if (isKeyguardNotShowing()) return@run

				val ev: MotionEvent = param.getArg(0)

				val action = ev.getActionMasked()

				if (isSingleTap() && action == MotionEvent.ACTION_UP) {
					if (doubleTapToSleepLockscreenEnabled && !isDozing) {
						SystemUtils.sleep()
					}
				}

				if (!holdScreenTorchEnabled) return@run

				if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
					if (turnedByTTT) { // we really don't want to see swipe gestures during TTT
						ev.setAction(MotionEvent.ACTION_DOWN)
					}
					if (isSingleTap() && !SystemUtils.isFlashOn() && SystemClock.uptimeMillis() - ev.getDownTime() > HOLD_DURATION) {
						turnedByTTT = true

						callMethod(SystemUtils.PowerManager(), "wakeUp", SystemClock.uptimeMillis())
						SystemUtils.setFlash(true, false)
						SystemUtils.vibrate(VibrationEffect.EFFECT_TICK, VibrationAttributes.USAGE_ACCESSIBILITY)

						startTrackedThread("px-torch-session") {
							// if keyguard is dismissed for any reason (face or udfps touch), then:
							while (turnedByTTT && !Thread.currentThread().isInterrupted) {
								try {
									SystemUtils.threadSleep(200)
									if (isKeyguardNotShowing()) {
										turnOffTTT()
									}
								} catch (ignored: Throwable) {
								}
							}
						}
					}
				} else if (turnedByTTT) {
					turnOffTTT()
				}
			}

		PhoneStatusBarViewClass
			.before("onTouchEvent")
			.run { param ->
				if (!doubleTapToSleepStatusbarEnabled) return@run

				//noinspection ConstantValue
				if (NotificationPanelViewController == null ||
					(
						!(getObjectField(NotificationPanelViewController, "mPulsing") as Boolean) &&
							!(getObjectField(NotificationPanelViewController, "mDozing") as Boolean) &&
							(getObjectField(NotificationPanelViewController, "mBarState") as Int) == SHADE &&
							(callMethod(NotificationPanelViewController, "isFullyCollapsed") as Boolean)
						)
				) {
					mLockscreenDoubleTapToSleep.onTouchEvent(param.args[param.args.size - 1] as MotionEvent)
				}
			}

		TriggerSensorClass
			.afterConstruction()
			.run { param ->
				if (getObjectField(param.thisObject, "mPulseReason") == REASON_SENSOR_TAP) {
					mDozeTouchTrigger = param.thisObject
				}
			}

		DozeTriggersClass
			.afterConstruction()
			.run { param -> mDozeTriggers = param.thisObject }

		DozeTriggersClass
			.before("onSensor")
			.run { param ->
				mDozeTriggers = param.thisObject

				if ((TapToShowAmbient && !doubleTapToWake && param.args[0]!!.equals(REASON_SENSOR_TAP)) ||
					(PickToShowAmbient && param.args[0]!!.equals(REASON_SENSOR_PICKUP))
				) {
					showAmbientDisplay(param.thisObject)
					param.setResult(null)
					return@run // don't proceed anymore
				}

				if (doubleTapToWake && (param.args[0] as Int) == REASON_SENSOR_TAP) {
					if (!mDoubleTap) {
						callMethod(mDozeTouchTrigger, "updateListening") // we wasted the event! let's listen again
						mDoubleTap = true
						mDoubleTapReset = Runnable {
							mDoubleTap = false
							if (TapToShowAmbient) {
								showAmbientDisplay(param.thisObject)
							}
						}
						handler.postDelayed(mDoubleTapReset!!, 400)

						param.setResult(null)
					} else {
						mDoubleTapReset?.let { handler.removeCallbacks(it) }
						mDoubleTap = false
					}
				}
			}

		DefaultSettingsPopupMenuSectionClass
			.before("bindData") // we prevent binding to happen in the first place. otherwise, very hard to control
			.run { param ->
				if (DisableLockScreenPill) {
					param.setResult(null)
				}
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

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		NotificationPanelViewController?.let { outState["notificationPanel"] = it }
		mStatusBarKeyguardViewManager?.let { outState["keyguardViewManager"] = it }
		mDozeTouchTrigger?.let { outState["dozeTouchTrigger"] = it }
		mKeyguardInteractor?.let { outState["keyguardInteractor"] = it }
		mShadeInteractorSceneContainerImpl?.let { outState["shadeInteractor"] = it }
		mStatusBarStateController?.let { outState["statusBarStateController"] = it }
		mDozeTriggers?.let { outState["dozeTriggers"] = it }
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		NotificationPanelViewController = savedState["notificationPanel"]
		mStatusBarKeyguardViewManager = savedState["keyguardViewManager"]
			?: NotificationPanelViewController?.let {
				try {
					getObjectField(it, "mStatusBarKeyguardViewManager")
				} catch (_: Throwable) {
					null
				}
			}
		mDozeTouchTrigger = savedState["dozeTouchTrigger"]
		mKeyguardInteractor = savedState["keyguardInteractor"]
		mShadeInteractorSceneContainerImpl = savedState["shadeInteractor"]
		mStatusBarStateController = savedState["statusBarStateController"]
		mDozeTriggers = savedState["dozeTriggers"]
	}

	override fun onUnloaded() {
		handler.removeCallbacksAndMessages(null)
		mDoubleTapReset = null
		mDoubleTap = false
		mLastKGSingleTap = 0L
		if (turnedByTTT) {
			turnOffTTT()
		}
	}

	private fun showAmbientDisplay(dozeTrigger: Any?) {
		if (dozeTrigger == null || mDoubleTap) return

		Handler(Looper.getMainLooper()).post {
			// call must be made from main thread
			try {
				callMethod(
					dozeTrigger,
					"requestPulse",
					PULSE_REASON_INTENT,
					// performedProxCheck
					false,
					// onPulseSuppressedListener
					null,
				)
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun isSingleTap(): Boolean = mLastKGSingleTap + HOLD_DURATION * 2 > SystemClock.uptimeMillis()

	private fun isQSExpanded(): Boolean = try {
		callMethod(NotificationPanelViewController, "isShadeFullyExpanded") as? Boolean ?: false
	} catch (ignored: Throwable) {
		false
	}

	private fun keyguardNotShowing(mStatusBarKeyguardViewManager: Any?): Boolean = try {
		!(callMethod(mStatusBarKeyguardViewManager, "isShowing") as Boolean)
	} catch (ignored: Throwable) {
		!getBooleanField(mStatusBarKeyguardViewManager, "mLastShowing")
	}

	private fun isKeyguardNotShowing(): Boolean {
		if (NotificationPanelViewController != null) {
			try {
				if (isQSExpanded() || getBooleanField(NotificationPanelViewController, "mBouncerShowing")) {
					return true
				}
				if (mStatusBarKeyguardViewManager != null && keyguardNotShowing(mStatusBarKeyguardViewManager)) {
					return true
				}
			} catch (ignored: Throwable) {
			}
		}

		val keyguardShowing = try {
			callMethod(getObjectField(mKeyguardInteractor, "isKeyguardShowing"), "getValue") as? Boolean
		} catch (ignored: Throwable) {
			null
		}

		if (keyguardShowing == false) return true

		if (keyguardShowing == null) {
			val keyguardManager = mContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
			if (keyguardManager?.isKeyguardLocked == false) return true

			// Without the interactor a locked keyguard may still be showing a
			// bouncer, so keep the gesture handler out of the way.
			return false
		}

		val bouncerShowing = try {
			callMethod(getObjectField(mKeyguardInteractor, "primaryBouncerShowing"), "getValue") as? Boolean
		} catch (ignored: Throwable) {
			false
		}
		if (bouncerShowing == true) return true

		return try {
			callMethod(callMethod(mShadeInteractorSceneContainerImpl, "isAnyExpanded"), "getValue") as? Boolean == true
		} catch (ignored: Throwable) {
			false
		}
	}

	private fun turnOffTTT() {
		turnedByTTT = false
		SystemUtils.setFlash(false, false)
	}
}
