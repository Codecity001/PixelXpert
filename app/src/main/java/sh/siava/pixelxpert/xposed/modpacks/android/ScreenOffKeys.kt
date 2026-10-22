package sh.siava.pixelxpert.xposed.modpacks.android

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.os.VibrationAttributes.USAGE_ACCESSIBILITY
import android.os.VibrationEffect.EFFECT_TICK
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_CAMERA
import android.view.KeyEvent.KEYCODE_POWER
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import android.view.ViewConfiguration
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import org.apache.commons.lang3.SystemProperties
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedMethod

/**
 * Remaps the physical keys that matter with the screen off: the power key's
 * long press, the double-tap-power camera gesture, and the volume keys - each
 * resolved to whatever action the Settings page bound to it (torch, camera,
 * assistant, media transport, or nothing at all).
 *
 * The hooks live in system server's `PhoneWindowManager` (the `PowerKeyRule`
 * long press, `startedWakingUp`, `interceptKeyBeforeQueueing`) and in
 * `GestureLauncherService`, and every hook body sits inside a `Throwable`
 * swallow so a build whose policy classes differ simply keeps its stock
 * behaviour. `startedWakingUp` only timestamps `mWakeTime`: whether the
 * screen counts as on is uptime minus that stamp, because the display state
 * lags the very power press being judged.
 *
 * With `controlFlashWithVolKeys` set and the torch already lit, the volume
 * keys instead step the torch level - one step per press, or a 100ms ramp
 * while held - so the flashlight stays reachable from the pocket.
 *
 * Kotlin has no class-level `@SuppressWarnings("RedundantThrows")`
 * equivalent, so it is absent here; `onPackageLoaded` does not declare `throws`
 * either way, because the base class already carries `@Throws`.
 */
@FrameworkModPack
class ScreenOffKeys(context: Context) : XposedModPack(context) {

	companion object {
		const val PHYSICAL_ACTION_NONE = -1

		const val PHYSICAL_ACTION_DEFAULT = 0
		const val PHYSICAL_ACTION_TORCH = 1
		const val PHYSICAL_ACTION_CAMERA = 2
		const val PHYSICAL_ACTION_ASSISTANT = 3
		/**
		 * @noinspection unused
		 */
		const val PHYSICAL_ACTION_DND = 4
		const val PHYSICAL_ACTION_PLAY_PAUSE = 5
		const val PHYSICAL_ACTION_MEDIA_NEXT = 6
		const val PHYSICAL_ACTION_MEDIA_PREV = 7

		const val WAKE_REASON_POWER_BUTTON = 1
		const val CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP = 1
		private const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6
		const val ACTION_COMPLETE = 1 // SingleKeyGestureEvent

		private var longPressPowerButtonScreenOff = 0
		private var longPressPowerButtonScreenOn = 0
		private var doublePressPowerButtonScreenOff = 0
		private var doublePressPowerButtonScreenOn = 0
		private var longPressVolumeUpButtonScreenOff = 0
		private var longPressVolumeDownButtonScreenOff = 0
		private var AnimateFlashlight = false

		private var controlFlashWithVolKeys = false
	}

	private var launchAssistActionMethod: ReflectedMethod? = null
	private var windowMan: Any? = null
	private var mWakeTime = 0L

	private var mVolumeLongPress = VolumeLongPressRunnable(PHYSICAL_ACTION_DEFAULT)
	private var mFlashAdjustRunnable = FlashAdjustLongRunnable(KEYCODE_VOLUME_DOWN)
	private val mLock = Any()
	private var mKeyIsDown = false
	private var mLoopRan = false

	@SuppressLint("CheckResult")
	override fun onPreferenceUpdated(vararg key: String?) {
		try {
			longPressPowerButtonScreenOff = Integer.parseInt(Xprefs.getString("longPressPowerButtonScreenOff", "0"))
			longPressPowerButtonScreenOn = Integer.parseInt(Xprefs.getString("longPressPowerButtonScreenOn", "0"))

			doublePressPowerButtonScreenOff = Integer.parseInt(Xprefs.getString("doublePressPowerButtonScreenOff", "0"))
			doublePressPowerButtonScreenOn = Integer.parseInt(Xprefs.getString("doublePressPowerButtonScreenOn", "0"))

			longPressVolumeUpButtonScreenOff = Integer.parseInt(Xprefs.getString("longPressVolumeUpButtonScreenOff", "0"))
			longPressVolumeDownButtonScreenOff = Integer.parseInt(Xprefs.getString("longPressVolumeDownButtonScreenOff", "0"))

			controlFlashWithVolKeys = Xprefs.getBoolean("controlFlashWithVolKeys", false)

			AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false)
			//noinspection ResultOfMethodCallIgnored
			SystemUtils.CameraManager() //init CameraManager to listen to flash status
		} catch (ignored: Throwable) {
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			val PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")
			val PowerKeyRuleClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager\$PowerKeyRule")
			val GestureLauncherServiceClass = ReflectedClass.of("com.android.server.GestureLauncherService")

			launchAssistActionMethod = ReflectedMethod.ofName(PhoneWindowManagerClass, "launchAssistAction")

			GestureLauncherServiceClass
				.before("handleCameraGesture")
				.run { param ->
					val screenIsOn = screenIsOn()

					val handled = launchAction(
						resolveAction(KEYCODE_CAMERA, screenIsOn),
						screenIsOn,
						true,
					)

					if (handled) {
						param.setResult(true)
					}
				}

			PhoneWindowManagerClass
				.after("enableScreen")
				.run { param ->
					windowMan = param.thisObject

					setObjectField(
						getObjectField(param.thisObject, "mGestureLauncherService"),
						"mCameraDoubleTapPowerEnabled",
						true,
					)
				}

			PowerKeyRuleClass
				.before("onLongPress")
				.run { param ->
					var actionType = ACTION_COMPLETE
					try { //TODO: no need to try/catch once QPR1 stable is released
						actionType = callMethod(
							param.args[0],
							"getAction",
						) as Int
					} catch (ignored: Throwable) {
					}

					val screenIsOn = screenIsOn()
					val customAction = resolveAction(KEYCODE_POWER, screenIsOn)

					if (customAction != PHYSICAL_ACTION_DEFAULT) {
						// Suppress OS handling of this long press (both ACTION_START and ACTION_COMPLETE)
						param.setResult(null)

						if (actionType == ACTION_COMPLETE && customAction != PHYSICAL_ACTION_NONE) {
							launchAction(customAction, screenIsOn, false)
						}
					}
				}

			PhoneWindowManagerClass
				.before("startedWakingUp")
				.run { param ->
					for (arg in param.args) {
						if (arg is Int && arg == WAKE_REASON_POWER_BUTTON) {
							mWakeTime = SystemClock.uptimeMillis()
							break
						}
					}
				}

			PhoneWindowManagerClass
				.before("interceptKeyBeforeQueueing")
				.run { param ->
					try {
						val event = param.args[0] as KeyEvent
						val keyCode = event.keyCode

						if ((keyCode == KEYCODE_VOLUME_UP || keyCode == KEYCODE_VOLUME_DOWN) &&
							controlFlashWithVolKeys &&
							SystemUtils.isFlashOn()
						) {
							val handler = getObjectField(param.thisObject, "mHandler") as Handler
							handleFlashKeys(event, handler)
							param.setResult(0)
							return@run
						}

						if (!deviceIsInteractive() &&
							((keyCode == KEYCODE_VOLUME_UP && longPressVolumeUpButtonScreenOff != PHYSICAL_ACTION_DEFAULT) ||
								(keyCode == KEYCODE_VOLUME_DOWN && longPressVolumeDownButtonScreenOff != PHYSICAL_ACTION_DEFAULT))
						) {
							val handler = getObjectField(param.thisObject, "mHandler") as Handler

							when (event.action) {
								KeyEvent.ACTION_UP -> {
									if (handler.hasCallbacks(mVolumeLongPress)) {
										SystemUtils.AudioManager()!!.adjustStreamVolume(
											AudioManager.STREAM_MUSIC,
											if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
											0,
										)
										handler.removeCallbacks(mVolumeLongPress)
										param.setResult(0)
									}
									return@run
								}
								KeyEvent.ACTION_DOWN -> {
									val action = resolveAction(keyCode, false)

									mVolumeLongPress = VolumeLongPressRunnable(action)
									if (isActionLaunchable(action)) {
										handler.postDelayed(mVolumeLongPress, ViewConfiguration.getLongPressTimeout().toLong())
										param.setResult(0)
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
		val action = event.action

		when (action) {
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
		val step = (if (keyCode == KEYCODE_VOLUME_UP) 1f else -1f) /
			SystemProperties.getInt("ro.config.media_vol_steps") { 10 }

		val newLevel = SystemUtils.getFlashlightLevel(SystemUtils.getFlashStrengthPCT() + step)
		SystemUtils.setFlash(true, newLevel, false)
	}

	private fun resolveAction(keyCode: Int, screenIsOn: Boolean): Int {
		val flashIsOn = SystemUtils.isFlashOn()

		val action = when (keyCode) {
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
		return action
	}

	private fun isActionLaunchable(action: Int): Boolean = when (action) {
		PHYSICAL_ACTION_MEDIA_NEXT, PHYSICAL_ACTION_MEDIA_PREV ->
			//noinspection DataFlowIssue
			SystemUtils.AudioManager()!!.isMusicActive()
		else -> true
	}

	private fun screenIsOn(): Boolean { //for power button, display state isn't reliable enough because pressing power will trigger it
		return SystemClock.uptimeMillis() - mWakeTime > 1000
	}

	private fun deviceIsInteractive(): Boolean {
		//noinspection DataFlowIssue
		return SystemUtils.PowerManager()!!.isInteractive()
	}

	/**
	 * @noinspection DataFlowIssue
	 */
	private fun launchAction(action: Int, screenIsOn: Boolean, delaySleep: Boolean): Boolean {
		try {
			var handled = false
			var shouldSleep = true

			when (action) {
				PHYSICAL_ACTION_NONE -> handled = true
				PHYSICAL_ACTION_TORCH -> {
					SystemUtils.toggleFlash(AnimateFlashlight)
					handled = true
				}
				PHYSICAL_ACTION_CAMERA -> {
					try {
						val gestureLauncherService = getObjectField(windowMan, "mGestureLauncherService")
						handled = callMethod(gestureLauncherService, "handleCameraGesture", false, CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP) as Boolean
						shouldSleep = false
					} catch (ignored: Throwable) {
					}
				}
				PHYSICAL_ACTION_ASSISTANT -> {
					try {
						launchAssistActionMethod!!.invoke(windowMan, null, -2, SystemClock.uptimeMillis(), INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS)
						handled = true
						shouldSleep = false
					} catch (ignored: Throwable) {
					}
				}
				PHYSICAL_ACTION_PLAY_PAUSE -> {
					dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
					handled = true
				}
				PHYSICAL_ACTION_MEDIA_NEXT -> {
					if (SystemUtils.AudioManager()!!.isMusicActive()) {
						dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_NEXT)
					}
					handled = true
				}
				PHYSICAL_ACTION_MEDIA_PREV -> {
					if (SystemUtils.AudioManager()!!.isMusicActive()) {
						dispatchAudioKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
					}
					handled = true
				}
			}

			if (handled) {
				if (action != PHYSICAL_ACTION_NONE) {
					SystemUtils.vibrate(EFFECT_TICK, USAGE_ACCESSIBILITY)
				}
				if (!screenIsOn && shouldSleep) {
					Thread {
						if (delaySleep) {
							SystemUtils.threadSleep(500)
						}
						SystemUtils.sleep()
					}.start()
				}
			}

			return handled
		} catch (ignored: Throwable) {
		}
		return false
	}

	private fun dispatchAudioKey(keyCode: Int) {
		//noinspection DataFlowIssue
		SystemUtils.AudioManager()!!.dispatchMediaKeyEvent(KeyEvent(ACTION_DOWN, keyCode))

		SystemUtils.AudioManager()!!.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
	}

	private inner class VolumeLongPressRunnable(val mAction: Int) : Runnable {
		override fun run() {
			launchAction(mAction, false, false)
		}
	}

	private inner class FlashAdjustLongRunnable(val mKeyCode: Int) : Runnable {
		override fun run() {
			synchronized(mLock) {
				mLoopRan = true
			}
			Thread {
				try {
					while (true) {
						adjustFlashStep(mKeyCode)

						//noinspection BusyWait
						Thread.sleep(100)

						synchronized(mLock) {
							if (!mKeyIsDown) break
						}
					}
				} catch (ignored: Throwable) {
				}
			}.start()
		}
	}
}
