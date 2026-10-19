package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Implements the optional one-finger QS gestures on the status bar, plus the
 * "long press status bar = app switch" shortcut.
 *
 * Every touch on `PhoneStatusBarView` is fed to a gesture detector while the
 * pulldown preference is on; a fling that both starts inside the configured
 * edge portion of the bar and travels fast enough expands QS. On builds before
 * 17QPR1 that goes through `NotificationPanelViewController.expandToQs`, on
 * newer builds through `ShadeInteractor.expandQuickSettingsShade`, so the pack
 * records whichever shade controller it sees constructed. The matching pullup
 * gesture collapses QS again, and a status bar long press broadcasts
 * [Constants.getAppProfileSwitchIntent] when the app-switch preference asks
 * for it. Because SystemUI moved these classes between releases, every target
 * is looked up by name and simply hooks nothing when the class is absent.
 */
@SystemUIModPack
class StatusbarGestures(context: Context) : XposedModPack(context) {

	companion object {
		private const val PULLDOWN_SIDE_RIGHT = 1
		private const val PULLDOWN_SIDE_LEFT = 2
		private const val STATUSBAR_MODE_SHADE = 0
		private const val STATUSBAR_MODE_KEYGUARD = 1
		/**
		 * @noinspection unused
		 */
		private const val STATUSBAR_MODE_SHADE_LOCKED = 2

		private var pullDownSide = PULLDOWN_SIDE_RIGHT
		private var oneFingerPulldownEnabled = false
		private var statusbarPortion = 0.25f // now set to 25% of the screen. it can be anything between 0 to 100%

		@SuppressLint("StaticFieldLeak")
		private var instance: StatusbarGestures? = null

		@JvmStatic
		fun collapseQSPanel() {
			if (instance != null) {
				instance!!.collapseQS()
			}
		}
	}

	private var oneFingerPullupEnabled = false
	private var NotificationPanelViewController: Any? = null
	private lateinit var mGestureDetector: GestureDetector
	private var StatusbarLongpressAppSwitch = false
	private var mDownEvent: MotionEvent? = null
	private var mShadeInteractor: Any? = null

	init {
		instance = this
	}

	override fun onPreferenceUpdated(vararg key: String?) {
		oneFingerPulldownEnabled = Xprefs.getBoolean("QSPullodwnEnabled", false)
		oneFingerPullupEnabled = oneFingerPulldownEnabled && Xprefs.getBoolean("oneFingerPullupEnabled", false)
		statusbarPortion = Xprefs.getSliderInt("QSPulldownPercent", 25) / 100f
		pullDownSide = Integer.parseInt(Xprefs.getString("QSPulldownSide", "1"))

		StatusbarLongpressAppSwitch = Xprefs.getBoolean("StatusbarLongpressAppSwitch", false)
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val NotificationPanelViewControllerClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.NotificationPanelViewController") //Pre 17QPR1
		val PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")

		//17QPR1
		val ShadeInteractorSceneContainerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl")
		val ShadeInteractorImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.domain.interactor.ShadeInteractorImpl")
		val ShadeSurfaceImplClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ShadeSurfaceImpl")

		ShadeSurfaceImplClass
			.before("onStatusBarLongPress")
			.run { param -> onStatusBarLongPress(param) }

		ShadeInteractorSceneContainerImplClass
			.afterConstruction()
			.run { param -> mShadeInteractor = param.thisObject }
		ShadeInteractorImplClass
			.afterConstruction()
			.run { param -> mShadeInteractor = param.thisObject }

		mGestureDetector = GestureDetector(mContext, getPullDownLPListener())

		PhoneStatusBarViewClass
			.after("dispatchTouchEvent")
			.run { param ->
				if (!oneFingerPulldownEnabled) return@run

				val event =
					if (param.args[0] is MotionEvent)
						param.args[0] as MotionEvent
					else
						param.args[1] as MotionEvent

				val clone = MotionEvent.obtain(event)
				if (clone.getActionMasked() == MotionEvent.ACTION_CANCEL) {
					clone.setAction(MotionEvent.ACTION_UP)
				}
				mGestureDetector.onTouchEvent(clone)
				clone.recycle()
			}

		val pullUpDetector = GestureDetector(mContext, getPullUpListener())

		val lastPullupTouchTime = longArrayOf(0)

		NotificationPanelViewControllerClass //Pre 17QPR1
			.before("onStatusBarLongPress")
			.run { param -> onStatusBarLongPress(param) }

		NotificationPanelViewControllerClass //Pre 17QPR1
			.afterConstruction()
			.run { param ->
				NotificationPanelViewController = param.thisObject
				val mTouchHandler = getObjectField(param.thisObject, "mTouchHandler")
				ReflectedClass.of(mTouchHandler.javaClass)
					.before("onTouchEvent")
					.run { param2 ->
						val motionEvent = param2.args[0] as MotionEvent? ?: return@run

						if (oneFingerPullupEnabled && !isKeyguard(NotificationPanelViewController)) {
							when (motionEvent.actionMasked) {
								MotionEvent.ACTION_OUTSIDE -> return@run

								// Only a live touch stream can be re-based into the pull-up
								// gesture; terminal actions just end gesture tracking.
								MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
									if (SystemClock.uptimeMillis() - lastPullupTouchTime[0] > 1000) {
										motionEvent.setAction(MotionEvent.ACTION_DOWN)
										lastPullupTouchTime[0] = SystemClock.uptimeMillis()
										mDownEvent?.recycle()
										mDownEvent = MotionEvent.obtain(motionEvent)
										return@run
									}

								MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
									lastPullupTouchTime[0] = 0
							}
							pullUpDetector.onTouchEvent(motionEvent)
						}
					}
			}
	}

	private fun onStatusBarLongPress(param: HookHelper.RunParam) {
		if (StatusbarLongpressAppSwitch) {
			sendAppSwitchBroadcast()
			param.setResult(null)
		}
	}

	//speedfactor & heightfactor are based on display height
	private fun isValidFling(e1: MotionEvent?, e2: MotionEvent, velocityY: Float, speedFactor: Float, heightFactor: Float): Boolean {
		//noinspection DataFlowIssue
		val displayBounds: Rect = SystemUtils.WindowManager()!!.getCurrentWindowMetrics().getBounds()
		try {
			return (e2.getY() - e1!!.getY()) / heightFactor > displayBounds.height() && //enough travel in right direction
				isTouchInRegion(e1, displayBounds.width().toFloat()) && //start point in hot zone
				velocityY / speedFactor > displayBounds.height() //enough speed in right direction
		} catch (ignored: Throwable) {
			return false
		}
	}

	private fun isTouchInRegion(motionEvent: MotionEvent?, width: Float): Boolean {
		val x = motionEvent!!.getX()
		val region = width * statusbarPortion

		return if (pullDownSide == PULLDOWN_SIDE_RIGHT)
			width - region < x
		else
			x < region
	}

	private fun sendAppSwitchBroadcast() {
		Thread { mContext.sendBroadcast(Constants.getAppProfileSwitchIntent()) }.start()
	}

	private fun getPullDownLPListener(): GestureDetector.OnGestureListener {
		return object : GestureListener() {
			override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
				if (isStatusbarClosed() &&
					isValidFling(e1, e2, velocityY, 0.15f, 0.01f)
				) {
					try {
						if (NotificationPanelViewController != null && hasMethod(NotificationPanelViewController!!.javaClass, "expandToQs")) { //Pre 17QPR1
							callMethod(NotificationPanelViewController, "expandToQs")
						} else if (mShadeInteractor != null) {
							callMethod(mShadeInteractor, "expandQuickSettingsShade", "asdf", null)
						}
					} catch (t: Throwable) {
					}
					return true
				}
				return false
			}
		}
	}

	private fun isKeyguard(controller: Any?): Boolean {
		try {
			return STATUSBAR_MODE_KEYGUARD == (getObjectField(controller, "mBarState") as Int)
		} catch (t: Throwable) {
			return false // Fallback for CP3A/CP41 where mBarState is gone
		}
	}

	private fun hasMethod(clazz: Class<*>, methodName: String): Boolean {
		try {
			for (m in clazz.getMethods()) {
				if (m.getName() == methodName) return true
			}
		} catch (t: Throwable) {
		}
		return false
	}

	private fun isStatusbarClosed(): Boolean {
		var isShade = false
		if (NotificationPanelViewController != null) {
			try {
				isShade = STATUSBAR_MODE_SHADE == (getObjectField(NotificationPanelViewController, "mBarState") as Int)
			} catch (t: Throwable) {
				isShade = true // Fallback for CP3A/CP41
			}
		}
		return (isShade || mShadeInteractor != null)
	}

	private fun getPullUpListener(): GestureDetector.OnGestureListener {
		return object : GestureListener() {
			override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
				if (isValidFling(mDownEvent, e2, velocityY, -0.15f, -0.06f)) {
					collapseQS()
					return true
				}
				return false
			}
		}
	}

	private fun collapseQS() { //for now only used on pre 17QPR1
		try {
			callMethod(NotificationPanelViewController, "collapse", true, 1f)
		} catch (ignored: Throwable) {
			callMethod(NotificationPanelViewController, "collapse", 1f, true)
		}
	}

	private open class GestureListener : GestureDetector.OnGestureListener {
		override fun onDown(e: MotionEvent): Boolean {
			return false
		}

		override fun onShowPress(e: MotionEvent) {
		}

		override fun onSingleTapUp(e: MotionEvent): Boolean {
			return false
		}

		override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
			return false
		}

		override fun onLongPress(e: MotionEvent) {
		}

		override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
			return false
		}
	}
}
