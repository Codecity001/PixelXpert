package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Field
import java.util.Optional
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.findFieldIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools

/**
 * Turns edge swipes in the launcher into configurable actions.
 *
 * The pack intercepts `onMotionEvent` of quickstep's input consumers before the
 * launcher sees it, classifies the gesture (left-edge swipe up, right-edge
 * swipe up, two-finger swipe up, or the "long swipe" that kills the app) from
 * the touch geometry, and - when the gesture belongs to the module - both
 * short-circuits the launcher's own handling and runs the configured action
 * on lift. The current focused task is snapshotted from `SystemUiProxy` on
 * every `ACTION_DOWN` so "kill app" and "hide running task" logic know what is
 * in front, which is also why a dead systemui binder triggers [SystemUtils.killSelf].
 *
 * Every action lands on a broadcast to the module app (`Constants.ACTION_HOME`,
 * `Constants.ACTION_SLEEP`, `Constants.getAppProfileSwitchIntent`) or on a
 * reflective call into the captured `SystemUiProxy`.
 */
//noinspection ConstantValue
@LauncherModPack
class CustomNavGestures(context: Context) : XposedModPack(context) {
	private var FCHandled = false
	private var leftSwipeUpPercentage = 0.25f
	private var rightSwipeUpPercentage = 0.25f
	private var displayW = -1
	private var displayH = -1
	private var swipeType = SWIPE_NONE

	//noinspection FieldCanBeLocal
	private var isLandscape = false
	private var mSwipeUpThreshold = 0f
	private var mLongThreshold = 0f
	private var mInitialY = 0f
	private var mGestureStartedInNavBar = false
	private var mInterceptedGesture = false
	private var mSystemUIProxy: Any? = null
	private var currentFocusedTask: Any? = null
	private var mTasksIsList = false

	// in case the code was obfuscated
	var mTasksFieldName: String? = null

	private var mPassedSlopFieldResolved = false
	private var mPassedSlopField: Field? = null
	private var mStartingInBoundsFieldResolved = false
	private var mStartingInBoundsField: Field? = null

	private val mTouchSlop by lazy { ViewConfiguration.get(mContext).scaledTouchSlop.toFloat() }
	private val mBottomGestureZonePx by lazy { ResourceTools.dpToPx(mContext, 48).toFloat() }

	override fun onPreferenceUpdated(vararg key: String?) {
		FCLongSwipeEnabled = Xprefs.getBoolean("FCLongSwipeEnabled", false)
		leftSwipeUpAction = readAction(Xprefs, "leftSwipeUpAction")
		rightSwipeUpAction = readAction(Xprefs, "rightSwipeUpAction")
		twoFingerSwipeUpAction = readAction(Xprefs, "twoFingerSwipeUpAction")
		leftSwipeUpPercentage = Xprefs.getSliderFloat("leftSwipeUpPercentage", 25f) / 100f
		rightSwipeUpPercentage = Xprefs.getSliderFloat("rightSwipeUpPercentage", 25f) / 100f
		swipeUpPercentage = Xprefs.getSliderFloat("swipeUpPercentage", 25f) / 100f
	}

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		mSystemUIProxy?.let { outState["systemUiProxy"] = it }
		mTasksFieldName?.let { outState["tasksFieldName"] = it }
		outState["tasksIsList"] = mTasksIsList
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		mSystemUIProxy = savedState["systemUiProxy"]
		mTasksFieldName = savedState["tasksFieldName"] as? String
		mTasksIsList = savedState["tasksIsList"] as? Boolean ?: false
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val OtherActivityInputConsumerClass = ReflectedClass.of("com.android.quickstep.inputconsumers.OtherActivityInputConsumer") //When apps are open
		var LauncherInputConsumerClass = ReflectedClass.ofIfPossible("com.android.quickstep.inputconsumers.LauncherInputConsumer") //When on Home screen and Recents
		if (LauncherInputConsumerClass.clazz == null) {
			LauncherInputConsumerClass = ReflectedClass.ofIfPossible("com.android.quickstep.inputconsumers.OverviewInputConsumer")
		}
		val SystemUiProxyClass = ReflectedClass.of("com.android.quickstep.SystemUiProxy")

		//noinspection DataFlowIssue
		val displayBounds = SystemUtils.WindowManager()!!.maximumWindowMetrics.bounds
		displayW = Math.min(displayBounds.width(), displayBounds.height())
		displayH = Math.max(displayBounds.width(), displayBounds.height())

		SystemUiProxyClass
			.afterConstruction()
			.run { param -> mSystemUIProxy = param.thisObject }

		SystemUiProxyClass
			.after("setInitializationParams")
			.run { param -> mSystemUIProxy = param.thisObject }

		OtherActivityInputConsumerClass
			.before("onMotionEvent")
			.run { param -> onMotionEvent(param, false) }

		LauncherInputConsumerClass
			.before("onMotionEvent")
			.run { param -> onMotionEvent(param, true) }
	}

	private fun onMotionEvent(param: HookHelper.RunParam, isOverViewListener: Boolean) {
		if (!isAnyGestureEnabled()) return

		val thisObj = param.thisObject ?: return
		val e = param.args[0] as MotionEvent

		val action = e.actionMasked
		val pointers = e.pointerCount

		if (action == MotionEvent.ACTION_CANCEL) {
			resetGestureState()
			return
		}

		if (action == MotionEvent.ACTION_DOWN) //Let's get ready
		{
			resetGestureState()
			mInitialY = e.y

			if (isOverViewListener) {
				if (!mStartingInBoundsFieldResolved) {
					mStartingInBoundsField = findFieldIfExists(thisObj.javaClass, "mStartingInActivityBounds")
					mStartingInBoundsFieldResolved = true
				}
				val startingInBoundsField = mStartingInBoundsField
				if (startingInBoundsField != null) {
					try {
						if (startingInBoundsField.getBoolean(thisObj)) {
							return
						}
					} catch (_: Throwable) {
						if (!isInBottomNavZone(e.y)) return
					}
				} else if (!isInBottomNavZone(e.y)) {
					return
				}
			}

			mGestureStartedInNavBar = true
			saveFocusedTask()

			mSwipeUpThreshold = e.y * (1f - swipeUpPercentage)
			mLongThreshold = e.y / 10f

			isLandscape = e.y < displayW //launcher rotation can be always 0. So....
			val currentW = if (isLandscape) displayH else displayW

			if (pointers == 1) {
				if (leftSwipeUpAction != NO_ACTION &&
					e.x < currentW * leftSwipeUpPercentage
				) {
					swipeType = SWIPE_LEFT
				} else if (rightSwipeUpAction != NO_ACTION &&
					e.x > currentW * (1f - rightSwipeUpPercentage)
				) {
					swipeType = SWIPE_RIGHT
				}
			}
		}

		if (!mGestureStartedInNavBar) return

		var mPassedWindowMoveSlop = isOverViewListener
		if (!mPassedWindowMoveSlop) {
			if (!mPassedSlopFieldResolved) {
				mPassedSlopField = findFieldIfExists(thisObj.javaClass, "mPassedWindowMoveSlop")
				mPassedSlopFieldResolved = true
			}
			val passedSlopField = mPassedSlopField
			mPassedWindowMoveSlop = if (passedSlopField != null) {
				try {
					passedSlopField.getBoolean(thisObj)
				} catch (_: Throwable) {
					(mInitialY - e.y) > mTouchSlop
				}
			} else {
				(mInitialY - e.y) > mTouchSlop
			}
		}

		if (twoFingerSwipeUpAction != NO_ACTION &&
			pointers == 2 &&
			swipeType != SWIPE_TWO_FINGER
		) { //must be outside down. usually down is one finger
			swipeType = SWIPE_TWO_FINGER
		}

		if (mPassedWindowMoveSlop &&
			swipeType != SWIPE_NONE
		) { //shouldn't reach the main code anymore
			mInterceptedGesture = true
			if (action != MotionEvent.ACTION_UP || isOverViewListener) {
				param.setResult(null)
			}
		}

		if (pointers == 1) {
			val FCAllowed = swipeType == SWIPE_NONE

			if (FCAllowed &&
				FCLongSwipeEnabled &&
				e.y < mLongThreshold &&
				!FCHandled &&
				!isOverViewListener
			) { //swiped up too much
				try {
					setObjectField(thisObj, "mActivePointerId", MotionEvent.INVALID_POINTER_ID)
					setObjectField(thisObj, "mPassedWindowMoveSlop", false)
				} catch (_: Throwable) {}
				try {
					callMethod(thisObj, "forceCancelGesture", e)
				} catch (_: Throwable) {}
				FCHandled = true
				runAction(ACTION_KILL_APP)
			}
		}

		if (action == MotionEvent.ACTION_UP) {
			if (swipeType != SWIPE_NONE) {
				if (!isOverViewListener && mInterceptedGesture) {
					try {
						callMethod(thisObj, "forceCancelGesture", e)
						param.setResult(null)
					} catch (_: Throwable) {
						e.action = MotionEvent.ACTION_CANCEL
					}
				}

				if (e.y < mSwipeUpThreshold) {
					when (swipeType) {
						SWIPE_LEFT -> runAction(leftSwipeUpAction)
						SWIPE_RIGHT -> runAction(rightSwipeUpAction)
						SWIPE_TWO_FINGER -> runAction(twoFingerSwipeUpAction)
					}
				}
			}
			resetGestureState()
		}
	}

	private fun isInBottomNavZone(y: Float): Boolean {
		val currentH = if (y <= displayW) displayW else displayH
		return y >= currentH - mBottomGestureZonePx
	}

	private fun resetGestureState() {
		FCHandled = false
		swipeType = SWIPE_NONE
		mGestureStartedInNavBar = false
		mInterceptedGesture = false
		currentFocusedTask = null
	}

	private fun isAnyGestureEnabled(): Boolean =
		FCLongSwipeEnabled ||
			leftSwipeUpAction != NO_ACTION ||
			rightSwipeUpAction != NO_ACTION ||
			twoFingerSwipeUpAction != NO_ACTION

	private fun isKillAppNeeded(): Boolean =
		FCLongSwipeEnabled ||
			leftSwipeUpAction == ACTION_KILL_APP ||
			rightSwipeUpAction == ACTION_KILL_APP ||
			twoFingerSwipeUpAction == ACTION_KILL_APP

	private fun getSystemUiProxy(): Any? {
		if (mSystemUIProxy != null) {
			return mSystemUIProxy
		}
		try {
			val proxyClass = ReflectedClass.of("com.android.quickstep.SystemUiProxy")
			val instance = getStaticObjectField(proxyClass.clazz, "INSTANCE")
			if (instance != null) {
				try {
					mSystemUIProxy = callMethod(instance, "get", mContext)
				} catch (_: Throwable) {
					val appContext = mContext.applicationContext
					if (appContext != null) {
						mSystemUIProxy = callMethod(instance, "get", appContext)
					}
				}
			}
		} catch (t: Throwable) {
			Logger.log("CustomNavGestures: getSystemUiProxy failed", t)
		}
		return mSystemUIProxy
	}

	private fun saveFocusedTask() {
		if (!isKillAppNeeded()) return

		try {
			val proxy = getSystemUiProxy() ?: return

			var recentTasks: Any? = null
			try {
				recentTasks = getObjectField(proxy, "recentTasks")
			} catch (_: Throwable) {
				try {
					recentTasks = getObjectField(proxy, "mRecentTasks")
				} catch (_: Throwable) {}
			}

			if (recentTasks == null) return

			@Suppress("UNCHECKED_CAST")
			val recentTaskList = callMethod(
				proxy,
				"getRecentTasks",
				1,
				callMethod(Process.myUserHandle(), "getIdentifier"),
			) as? ArrayList<*>

			if (recentTaskList.isNullOrEmpty()) return

			if (mTasksFieldName == null) {
				for (f in recentTaskList[0]!!.javaClass.declaredFields) {
					if (f.type.name.contains("RecentTaskInfo")) {
						mTasksFieldName = f.name
					}
				}
			}
			if (mTasksFieldName == null) {
				for (f in recentTaskList[0]!!.javaClass.declaredFields) {
					if (f.type.name.contains("List")) {
						if (!f.isAccessible) {
							f.isAccessible = true
						}
						@Suppress("UNCHECKED_CAST")
						val list = f.get(recentTaskList[0]) as List<*>?
						if (list != null && list.isNotEmpty() && findFieldIfExists(list[0]!!.javaClass, "isFocused") != null) {
							mTasksFieldName = f.name
							mTasksIsList = true
						}
					}
				}
			}

			val tasksFieldName = mTasksFieldName ?: return

			val focusedTask: Optional<*> = recentTaskList.stream().filter { recentTask ->
				try {
					val task = if (mTasksIsList) {
						(getObjectField(recentTask, tasksFieldName) as List<*>)[0]
					} else {
						(getObjectField(recentTask, tasksFieldName) as Array<*>)[0]
					}
					getObjectField(task, "isFocused") as? Boolean == true
				} catch (_: Throwable) {
					false
				}
			}.findFirst()

			currentFocusedTask = focusedTask.map { o ->
				if (mTasksIsList) {
					(getObjectField(o, tasksFieldName) as List<*>)[0]
				} else {
					(getObjectField(o, tasksFieldName) as Array<*>)[0]
				}
			}.orElse(null)
		} catch (_: Throwable) {
		}
	}

	private fun runAction(action: Int) {
		when (action) {
			ACTION_BACK -> goBack()
			ACTION_SCREENSHOT -> takeScreenshot()
			ACTION_NOTIFICATION -> toggleNotification()
			ACTION_KILL_APP -> killForeground()
			ACTION_ONE_HANDED -> startOneHandedMode()
			ACTION_SLEEP -> goToSleep()
			ACTION_SWITCH_APP_PROFILE -> switchAppProfile()
		}
	}

	private fun switchAppProfile() {
		startTrackedThread("px-switch-app-profile") {
			try {
				SystemUtils.threadSleep(200) // waiting for recents window to vanish

				mContext.sendBroadcast(Constants.getAppProfileSwitchIntent())
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun goToSleep() {
		val broadcast = Intent()
		broadcast.action = Constants.ACTION_SLEEP
		mContext.sendBroadcast(broadcast)
	}

	private fun killForeground() {
		if (currentFocusedTask == null) {
			saveFocusedTask()
		}
		if (currentFocusedTask == null) return

		try {
			Toast.makeText(mContext, "App Killed", Toast.LENGTH_SHORT).show()

			callMethod(
				mContext.getSystemService(Context.ACTIVITY_SERVICE),
				"forceStopPackageAsUser",
				(getObjectField(currentFocusedTask, "realActivity") as ComponentName).packageName,
				getObjectField(currentFocusedTask, "userId"),
			)

			goHome()
		} catch (ignored: Throwable) {
		}
	}

	private fun goBack() {
		val proxy = getSystemUiProxy()
		if (proxy != null) {
			callMethod(proxy, "onBackPressed")
		}
	}

	private fun startOneHandedMode() {
		val proxy = getSystemUiProxy()
		if (proxy != null) {
			callMethod(proxy, "startOneHandedMode")
		}
	}

	private fun toggleNotification() {
		val proxy = getSystemUiProxy()
		if (proxy != null) {
			callMethod(proxy, "toggleNotificationPanel")
		}
	}

	private fun takeScreenshot() {
		try {
			val proxy = getSystemUiProxy() ?: return

			val ScreenshotRequestBuilderClass = ReflectedClass.of("com.android.internal.util.ScreenshotRequest\$Builder")

			val screenshotRequestBuilder = ScreenshotRequestBuilderClass
				.clazz!!
				.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
				.newInstance(TAKE_SCREENSHOT_FULLSCREEN, 1)

			callMethod(
				proxy,
				"takeScreenshot",
				callMethod(
					screenshotRequestBuilder,
					"build",
				),
			)
		} catch (t: Throwable) {
			Logger.log("CustomNavGestures: takeScreenshot failed", t)
		}
	}

	private fun goHome() {
		val broadcast = Intent()
		broadcast.action = Constants.ACTION_HOME
		mContext.sendBroadcast(broadcast)
	}

	companion object {
		private const val NO_ACTION = -1
		private const val ACTION_SCREENSHOT = 1
		private const val ACTION_BACK = 2
		private const val ACTION_KILL_APP = 3
		private const val ACTION_NOTIFICATION = 4
		private const val ACTION_ONE_HANDED = 5
		private const val ACTION_SLEEP = 7
		private const val ACTION_SWITCH_APP_PROFILE = 8
		private const val TAKE_SCREENSHOT_FULLSCREEN = 1

		private const val SWIPE_NONE = 0
		private const val SWIPE_LEFT = 1
		private const val SWIPE_RIGHT = 2
		private const val SWIPE_TWO_FINGER = 3

		private var swipeUpPercentage = 0.2f
		private var FCLongSwipeEnabled = false
		private var leftSwipeUpAction = NO_ACTION
		private var rightSwipeUpAction = NO_ACTION
		private var twoFingerSwipeUpAction = NO_ACTION

		@JvmStatic
		private fun readAction(xprefs: SharedPreferences, prefName: String): Int =
			try {
				Integer.parseInt(xprefs.getString(prefName, "")!!.trim())
			} catch (ignored: Exception) {
				NO_ACTION
			}
	}
}
