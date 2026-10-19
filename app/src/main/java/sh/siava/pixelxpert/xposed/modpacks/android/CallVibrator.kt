package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import android.os.SystemClock.uptimeMillis
import android.os.VibrationAttributes.USAGE_ACCESSIBILITY
import android.os.VibrationEffect
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.annotations.ChildProcessModPack
import sh.siava.pixelxpert.annotations.MainProcessModPack
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.TelecomServerModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Buzzes the phone when a call is answered and when it drops, so the user
 * notices the transition without looking at the screen. The telecom server's
 * `InCallController.onCallStateChanged` is the one place that sees both edges
 * for every call, so a before-hook there watches the old/new pair against the
 * [DIALING] -> [ACTIVE] and [ACTIVE] -> [DISCONNECTED] transitions this
 * controller's call states use.
 *
 * The buzz is a fixed four-segment waveform (off/on/off/on, no repeat) fired
 * at accessibility usage so it survives silent mode, and [vibrate] rate-limits
 * itself to one vibration per 200ms - a state machine that flaps would
 * otherwise machine-gun the vibrator.
 *
 * `@MainProcessModPack` is kept alongside `@ChildProcessModPack("")` because
 * the telecom server must never be treated as a child-process-only pack; both
 * annotations are part of how the registry decides where this pack loads.
 */
@TelecomServerModPack
@MainProcessModPack //telecom server shouldn't fall under child process checks
@ChildProcessModPack(processNameContains = "")
class CallVibrator(context: Context) : XposedModPack(context) {

	companion object {
		const val DIALING = 3
		const val ACTIVE = 5
		const val DISCONNECTED = 7

		private val mVibrationEffect: VibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1) //100ms on, 100 off, 100 again. don't repeat
		private var mLastVibration = 0L
		private var vibrateOnAnswered = false
		private var vibrateOnDrop = false
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		vibrateOnAnswered = Xprefs.getBoolean("vibrateOnAnswered", false)
		vibrateOnDrop = Xprefs.getBoolean("vibrateOnDrop", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			val InCallControllerClass = ReflectedClass.of("com.android.server.telecom.InCallController")

			InCallControllerClass
				.before("onCallStateChanged")
				.run { param ->
					try {
						val oldState = param.args[1] as Int
						val newState = param.args[2] as Int

						if (
							(vibrateOnAnswered &&
								oldState == DIALING &&
								newState == ACTIVE)
							||
							(vibrateOnDrop &&
								oldState == ACTIVE &&
								newState == DISCONNECTED)
						) {
							vibrate()
						}
					} catch (ignored: Throwable) {
					}
				}
		} catch (ignored: Throwable) {
		}
	}

	private fun vibrate() {
		if (uptimeMillis() - mLastVibration > 200L) {
			mLastVibration = uptimeMillis()
			SystemUtils.vibrate(mVibrationEffect, USAGE_ACCESSIBILITY)
		}
	}
}
