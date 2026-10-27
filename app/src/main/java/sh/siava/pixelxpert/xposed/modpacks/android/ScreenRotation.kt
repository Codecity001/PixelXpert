package sh.siava.pixelxpert.xposed.modpacks.android

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

/**
 * Implements "Force-unlock all screen rotations" by taking over the answer
 * `DisplayRotation.rotationForOrientation` gives the window manager.
 *
 * With the toggle off the hook returns immediately and the framework keeps
 * its own policy. With it on, the before-hook supplies the rotation itself: a
 * locked user rotation (`mUserRotationMode == USER_ROTATION_LOCKED`) keeps
 * the display where it already is, and otherwise the orientation listener's
 * sensor proposal wins, falling back to the current rotation when the sensor
 * has none. The whole body sits in a `Throwable` swallow so a build whose
 * `DisplayRotation` differs simply leaves rotation alone.
 */
@FrameworkModPack
class ScreenRotation(context: Context) : XposedModPack(context) {

	companion object {
		private const val USER_ROTATION_LOCKED = 1

		private var allScreenRotations = false
	}

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {
		allScreenRotations = Xprefs.getBoolean("allScreenRotations", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		try {
			val DisplayRotationClass = ReflectedClass.of("com.android.server.wm.DisplayRotation")

			DisplayRotationClass
				.before("rotationForOrientation")
				.run { param ->
					try {
						if (!allScreenRotations) return@run

						val lastRotation = param.args[1] as Int

						if (getIntField(param.thisObject, "mUserRotationMode") == USER_ROTATION_LOCKED) {
							param.setResult(lastRotation)
							return@run
						}

						val mOrientationListener: Any? = getObjectField(param.thisObject, "mOrientationListener")
						var sensorRotation = if (mOrientationListener != null) {
							callMethod(mOrientationListener, "getProposedRotation") as Int // may be -1
						} else {
							-1
						}
						if (sensorRotation < 0) {
							sensorRotation = lastRotation
						}
						param.setResult(sensorRotation)
					} catch (ignored: Throwable) {
					}
				}
		} catch (ignored: Exception) {
		}
	}
}
