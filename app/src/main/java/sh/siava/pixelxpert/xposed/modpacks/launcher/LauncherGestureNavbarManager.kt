package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.Context
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import java.util.Arrays

/**
 * Reshapes and recolors the taskbar's gesture pill.
 *
 * After `TaskbarActivityContext.init` the pack rewrites the taskbar
 * controller's `mStashedHandleWidth`/`mStashedHandleHeight` from the
 * `GesPillWidthModPos` and `GesPillHeightFactor` sliders - the original height
 * is stashed on the controller itself as an additional instance field so every
 * later init scales from the stock value instead of the previous result. A
 * hook on `StashedHandleView.updateHandleColor` swaps the pill's light/dark
 * colors for the monet accent (`system_accent1_200`/`system_accent1_600`) when
 * `navPillColorAccent` is on, remembering the stock colors once so toggling
 * back off restores them.
 *
 * Because the size is baked into the controller at init and the color swap
 * needs a redraw, changing any of the four related preferences while hooked
 * runs [SystemUtils.doubleToggleDarkMode] to force the launcher through a
 * config change rather than leaving a stale pill on screen.
 */
@LauncherModPack
class LauncherGestureNavbarManager(context: Context) : XposedModPack(context) {
	private var mColorReplaced = false
	private var mStashedHandleLightColor = 0
	private var mStashedHandleDarkColor = 0
	private var mIsHooked = false

	override fun onPreferenceUpdated(vararg key: String?) {
		navPillColorAccent = Xprefs.getBoolean("navPillColorAccent", false)

		widthFactor = Xprefs.getSliderInt("GesPillWidthModPos", 50) * 0.02f
		GesPillHeightFactor = Xprefs.getSliderInt("GesPillHeightFactor", 100)

		if (Xprefs.getBoolean("HideNavbar", false)) {
			widthFactor = 0f
		}

		if (mIsHooked && key.isNotEmpty() && Arrays.asList(
				"GesPillWidthModPos",
				"GesPillHeightFactor",
				"HideNavbar",
				"navPillColorAccent",
			).contains(key[0])
		) {
			SystemUtils.doubleToggleDarkMode()
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val StashedHandleViewClass = ReflectedClass.ofIfPossible("com.android.launcher3.taskbar.StashedHandleView")
		val TaskbarActivityContextClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarActivityContext")

		mIsHooked = true

// 		val StashedHandleViewControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.StashedHandleViewController") //almost not usable anymore due to intense R8

		TaskbarActivityContextClass
			.after("init")
			.run { param ->
				val mControllers = getObjectField(param.thisObject, "mControllers")
				val stashedHandleViewController = getObjectField(mControllers, "stashedHandleViewController")

				setAdditionalInstanceField(
					stashedHandleViewController,
					"OriginalStashedHandleHeight",
					getObjectField(stashedHandleViewController, "mStashedHandleHeight"),
				)

				setObjectField(
					stashedHandleViewController,
					"mStashedHandleWidth",
					Math.round(widthFactor * getIntField(stashedHandleViewController, "mStashedHandleWidth")),
				)

				setObjectField(
					stashedHandleViewController,
					"mStashedHandleHeight",
					Math.round(
						(getAdditionalInstanceField(stashedHandleViewController, "OriginalStashedHandleHeight") as Int) *
							GesPillHeightFactor /
							100f,
					),
				)
			}

		StashedHandleViewClass
			.afterConstruction()
			.run { param ->
				mStashedHandleLightColor = getObjectField(param.thisObject, "mStashedHandleLightColor") as Int
				mStashedHandleDarkColor = getObjectField(param.thisObject, "mStashedHandleDarkColor") as Int
			}

		StashedHandleViewClass
			.before("updateHandleColor")
			.run { param ->
				if (navPillColorAccent || mColorReplaced) {
					setObjectField(
						param.thisObject,
						"mStashedHandleLightColor",
						if (navPillColorAccent) {
							mContext.resources.getColor(
								android.R.color.system_accent1_200,
								mContext.theme,
							)
						} else {
							mStashedHandleLightColor
						},
					)
					setObjectField(
						param.thisObject,
						"mStashedHandleDarkColor",
						if (navPillColorAccent) {
							mContext.resources.getColor(
								android.R.color.system_accent1_600,
								mContext.theme,
							)
						} else {
							mStashedHandleDarkColor
						},
					)
					mColorReplaced = true
				}
			}
	}

	companion object {
		private var navPillColorAccent = false
		private var widthFactor = 1f
		private var GesPillHeightFactor = 100
	}
}
