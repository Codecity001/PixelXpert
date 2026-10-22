package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.view.MotionEvent.ACTION_DOWN
import android.graphics.Point
import android.view.MotionEvent
import de.robv.android.xposed.XposedHelpers.getFloatField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Per-side control of the gesture navbar's back gesture.
 *
 * Two preferences decide whether the left and right screen edges may start a
 * back gesture at all, and two more set how tall each active edge region is
 * as a fraction of the screen height. The hooks refuse the gesture before the
 * system accepts it - `mAllowGesture` is cleared and the motion handler is
 * cut short - when the touch lands outside the configured region, on both the
 * current `BackPanelController` path and the older Android 13
 * `NavigationBarEdgePanel` path.
 */
@SystemUIModPack
class GestureNavbarManager(context: Context) : XposedModPack(context) {

	companion object {
		private var backGestureHeightFractionLeft = 1f // % of screen height. can be anything between 0 to 1
		private var backGestureHeightFractionRight = 1f // % of screen height. can be anything between 0 to 1
		private var leftEnabled = true
		private var rightEnabled = true
	}

	var initialBackX = 0f

	var EdgeBackGestureHandler: Any? = null

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {

		leftEnabled = Xprefs.getBoolean("BackFromLeft", true)
		rightEnabled = Xprefs.getBoolean("BackFromRight", true)
		backGestureHeightFractionLeft = Xprefs.getSliderInt("BackLeftHeight", 100) / 100f
		backGestureHeightFractionRight = Xprefs.getSliderInt("BackRightHeight", 100) / 100f
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val EdgeBackGestureHandlerClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler")
		val NavigationBarEdgePanelClass = ReflectedClass.ofIfPossible("com.android.systemui.navigationbar.gestural.NavigationBarEdgePanel")
		val BackPanelControllerClass = ReflectedClass.of("com.android.systemui.navigationbar.gestural.BackPanelController")

		//A16 QPR2 - The class doesn't have a visible constructor anymore, thus replacement method
		EdgeBackGestureHandlerClass
			.before("updateIsEnabled")
			.run { param -> EdgeBackGestureHandler = param.thisObject }

		//The navbar builds each display's gesture handler through this method, on the
		//same edge handler the gesture will be routed through.
		EdgeBackGestureHandlerClass
			.before("createDisplayBackGestureHandler")
			.run { param -> EdgeBackGestureHandler = param.thisObject }

		BackPanelControllerClass
			.before("onMotionEvent")
			.run { param ->
				val ev = param.args[0] as MotionEvent

				if (ev.actionMasked == ACTION_DOWN) //down action is enough. once gesture is refused it won't accept further actions
				{
					if (notWithinInsets(
							ev.x,
							ev.y,
							getObjectField(EdgeBackGestureHandler, "mDisplaySize") as Point,
							getFloatField(EdgeBackGestureHandler, "mBottomGestureHeight"),
						)
					) {
						setObjectField(EdgeBackGestureHandler, "mAllowGesture", false) //act like the gesture was not good enough
						param.setResult(null) //and stop the current method too
					}
				}
			}

		//Android 13
		NavigationBarEdgePanelClass
			.before("onMotionEvent")
			.run { param ->
				val event = param.args[0] as MotionEvent
				if (event.action == ACTION_DOWN) {
					initialBackX = event.x
				}
				if (notWithinInsets(initialBackX, event.y, getObjectField(param.thisObject, "mDisplaySize") as Point, 0f)) {
					param.setResult(null)
				}
			}
	}

	private fun notWithinInsets(x: Float, y: Float, mDisplaySize: Point, mBottomGestureHeight: Float): Boolean {
		val isLeftSide = x < (mDisplaySize.x / 3f)
		if ((isLeftSide && !leftEnabled) ||
			(!isLeftSide && !rightEnabled)
		) {
			return true
		}

		val mEdgeHeight = if (isLeftSide) {
			Math.round(mDisplaySize.y * backGestureHeightFractionLeft)
		} else {
			Math.round(mDisplaySize.y * backGestureHeightFractionRight)
		}

		return mEdgeHeight != 0 &&
			y < (mDisplaySize.y -
				mBottomGestureHeight -
				mEdgeHeight)
	}
}
