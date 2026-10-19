package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Restricts screen brightness to the range picked in the "brightness range"
 * slider, in both places brightness is decided. In system_server a before-hook
 * on `DisplayPowerController.clampScreenBrightness` rewrites the candidate
 * brightness every time the framework clamps it, and in SystemUI (and anywhere
 * else it is constructed) a constructor hook on `android.hardware.display.
 * BrightnessInfo` overwrites `brightnessMinimum`/`brightnessMaximum` so the
 * slider UI itself cannot go outside the range.
 *
 * Both hooks no-op while the range is the untouched default (0..1), which is
 * why the preference read stores the defaults rather than disabling the
 * hooks. Each half sits in its own
 * `Throwable` swallow, so the framework side still applies on a build where
 * the SystemUI class is missing, and vice versa.
 */
@SystemUIModPack
@FrameworkModPack
class BrightnessRange(context: Context) : XposedModPack(context) {

	companion object {
		private var minimumBrightnessLevel = 0f
		private var maximumBrightnessLevel = 1f
	}

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {

		try {
			val BrightnessRange = Xprefs.getSliderValues("BrightnessRange", 100f)
			if (BrightnessRange.size == 2) {
				minimumBrightnessLevel = BrightnessRange[0] / 100
				maximumBrightnessLevel = BrightnessRange[1] / 100
			} else {
				// The row's reset icon clears the key instead of writing the
				// default back, so the stale clamp has to be released here -
				// 0..1 is what the guard above recognises as "no override".
				minimumBrightnessLevel = 0f
				maximumBrightnessLevel = 1f
			}
		} catch (ignored: Throwable) {
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try { //framework
			val DisplayPowerControllerClass = ReflectedClass.of("com.android.server.display.DisplayPowerController")

			DisplayPowerControllerClass
				.before("clampScreenBrightness")
				.run { param ->
					if (minimumBrightnessLevel == 0f && maximumBrightnessLevel == 1f) return@run

					param.args[0] = Math.min(
						Math.max(
							param.getArg<Float>(0),
							minimumBrightnessLevel,
						),
						maximumBrightnessLevel,
					)
				}

		} catch (ignored: Throwable) {
		}

		try { //SystemUI
			val BrightnessInfoClass = ReflectedClass.of("android.hardware.display.BrightnessInfo")

			BrightnessInfoClass
				.afterConstruction()
				.run { param ->
					if (minimumBrightnessLevel > 0f) {
						setObjectField(param.thisObject, "brightnessMinimum", minimumBrightnessLevel)
					}
					if (maximumBrightnessLevel < 1f) {
						setObjectField(param.thisObject, "brightnessMaximum", maximumBrightnessLevel)
					}
				}
		} catch (ignored: Throwable) {
		}
	}
}
