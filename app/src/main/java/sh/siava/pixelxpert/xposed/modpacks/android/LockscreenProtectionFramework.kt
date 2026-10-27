package sh.siava.pixelxpert.xposed.modpacks.android

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ModPackPriority
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

/**
 * Intercepts power menu invocations and the Power + Volume Up key chord
 * while the device is locked, when [BlockQSPowerOnLockscreen] is enabled.
 * Rejects the action silently with tactile vibration feedback.
 */
@FrameworkModPack
@ModPackPriority(priority = 10)
class LockscreenProtectionFramework(context: Context) : XposedModPack(context) {

	companion object {
		private var blockQSPowerOnLockscreen = false
		private var isPowerKeyHeld = false
		private var isVolumeUpKeyHeld = false
		private var lastPowerDownTime = 0L
		private var lastVolUpDownTime = 0L
		private var lastVibrateTime = 0L
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		blockQSPowerOnLockscreen = Xprefs.getBoolean("BlockQSPowerOnLockscreen", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")

		PhoneWindowManagerClass
			.before("showGlobalActions")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				if (isLocked(param.thisObject)) {
					param.setResult(null)
					rejectFeedback()
				}
			}

		PhoneWindowManagerClass
			.before("showGlobalActionsInternal")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				if (isLocked(param.thisObject)) {
					param.setResult(null)
					rejectFeedback()
				}
			}

		PhoneWindowManagerClass
			.before("interceptKeyBeforeQueueing")
			.run { param ->
				if (!blockQSPowerOnLockscreen) return@run
				val event = param.args[0] as? KeyEvent ?: return@run
				val keyCode = event.keyCode
				val action = event.action

				if (action == KeyEvent.ACTION_DOWN) {
					if (keyCode == KeyEvent.KEYCODE_POWER) {
						isPowerKeyHeld = true
						lastPowerDownTime = event.eventTime
					}
					if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
						isVolumeUpKeyHeld = true
						lastVolUpDownTime = event.eventTime
					}

					val now = event.eventTime
					val powerValid = isPowerKeyHeld && (now - lastPowerDownTime < 3000)
					val volValid = isVolumeUpKeyHeld && (now - lastVolUpDownTime < 3000)

					if (powerValid && volValid) {
						if (isLocked(param.thisObject)) {
							param.setResult(0)
							rejectFeedback()
						}
					}
				} else if (action == KeyEvent.ACTION_UP) {
					if (keyCode == KeyEvent.KEYCODE_POWER) isPowerKeyHeld = false
					if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolumeUpKeyHeld = false
				}
			}
	}

	private fun isLocked(pwm: Any?): Boolean {
		try {
			if (pwm != null) {
				val isShowing = callMethod(pwm, "isKeyguardShowingAndNotOccluded") as? Boolean
				if (isShowing == true) return true
				val kd = getObjectField(pwm, "mKeyguardDelegate")
				if (kd != null) {
					val showing = callMethod(kd, "isShowing") as? Boolean
					if (showing == true) return true
				}
			}
		} catch (ignored: Throwable) {}
		try {
			val km = mContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
			if (km != null && (km.isKeyguardLocked || km.isDeviceLocked)) {
				return true
			}
		} catch (ignored: Throwable) {}
		return false
	}

	private fun rejectFeedback() {
		val now = SystemClock.uptimeMillis()
		if (now - lastVibrateTime < 300) return
		lastVibrateTime = now
		try {
			val pattern = longArrayOf(0, 40, 50, 40)
			val effect = VibrationEffect.createWaveform(pattern, -1)
			SystemUtils.vibrate(effect, VibrationAttributes.USAGE_TOUCH)
		} catch (t: Throwable) {
			try {
				val vib = mContext.getSystemService(Vibrator::class.java)
				vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 40), -1))
			} catch (ignored: Throwable) {}
		}
	}
}
