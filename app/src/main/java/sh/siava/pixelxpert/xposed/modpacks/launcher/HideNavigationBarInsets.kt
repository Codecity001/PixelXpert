package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Removes the launcher taskbar's reservation of the navigation bar inset.
 *
 * With `HideNavbarInsets` on, the pack hooks `TaskbarActivityContext
 * .notifyUpdateLayoutParams` and rewrites every `WindowManager.LayoutParams`
 * the taskbar is about to apply - the live one plus its per-rotation copies -
 * so that each `InsetsFrame` describing `type=navigationBars` gets its size
 * zeroed via `setInsetsSize(Insets.NONE)`. That makes the taskbar draw
 * edge-to-edge where the system would otherwise leave room for the gesture
 * handle.
 *
 * The insets frame is matched by string (`toString().contains(...)`) rather
 * than by framework constants on purpose: the `InsetsFrame` API moved across
 * Android versions and the string form is the one thing that stayed stable.
 * Toggling the preference kills the launcher so the rewritten layout params
 * take effect from a clean start.
 */
@LauncherModPack
class HideNavigationBarInsets(context: Context) : XposedModPack(context) {
	private var HideNavbarInsets = false

	override fun onPreferenceUpdated(vararg key: String?) {
		HideNavbarInsets = Xprefs.getBoolean("HideNavbarInsets", false)
		if (key.isNotEmpty() && key[0] == "HideNavbarInsets") {
			SystemUtils.killSelf()
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val TaskbarActivityContextClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarActivityContext")
		TaskbarActivityContextClass
			.before("notifyUpdateLayoutParams")
			.run { param ->
				if (!HideNavbarInsets) return@run

				val layoutParams = XposedHelpers.getObjectField(param.thisObject, "mWindowLayoutParams") as WindowManager.LayoutParams
				transformLayoutParams(layoutParams)

				val rotationParams = XposedHelpers.getObjectField(layoutParams, "paramsForRotation") as Array<WindowManager.LayoutParams?>?
				if (rotationParams == null) return@run

				for (rotationLayoutParams in rotationParams) {
					transformLayoutParams(rotationLayoutParams)
				}
			}
	}

	private fun transformLayoutParams(layoutParams: WindowManager.LayoutParams?) {
		if (layoutParams == null) return

		val providedInsets = XposedHelpers.getObjectField(layoutParams, "providedInsets")
		if (providedInsets == null) return

		val providedInsetsLength = java.lang.reflect.Array.getLength(providedInsets)
		for (i in 0 until providedInsetsLength) {
			val insetsFrame = java.lang.reflect.Array.get(providedInsets, i)

			if (insetsFrame == null) continue
			if (!insetsFrame.toString().contains("type=navigationBars")) // no constants, maximum compatibility with Android versions
				continue

			XposedHelpers.callMethod(insetsFrame, "setInsetsSize", android.graphics.Insets.NONE)
		}
	}
}
