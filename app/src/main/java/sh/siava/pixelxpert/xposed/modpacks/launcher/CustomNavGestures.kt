package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import android.view.MotionEvent
import android.widget.Toast
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.findFieldIfExists
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger
import java.lang.reflect.Field
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

@LauncherModPack
class CustomNavGestures(context: Context) : XposedModPack(context) {

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
        private var fcLongSwipeEnabled = false
        private var leftSwipeUpAction = NO_ACTION
        private var rightSwipeUpAction = NO_ACTION
        private var twoFingerSwipeUpAction = NO_ACTION

        private fun readAction(prefs: SharedPreferences, prefName: String): Int {
            return try {
                prefs.getString(prefName, "")?.trim()?.toInt() ?: NO_ACTION
            } catch (ignored: Exception) {
                NO_ACTION
            }
        }
    }

    private var fcHandled = false
    private var leftSwipeUpPercentage = 0.25f
    private var rightSwipeUpPercentage = 0.25f
    private var displayW = -1
    private var displayH = -1
    private var swipeType = SWIPE_NONE

    private var isLandscape = false
    private var mSwipeUpThreshold = 0f
    private var mLongThreshold = 0f
    private var mSystemUIProxy: Any? = null
    private var currentFocusedTask: Any? = null
    private var mTasksIsList = false
    private var mTasksFieldName: String? = null

    override fun onPreferenceUpdated(vararg key: String) {
        fcLongSwipeEnabled = Xprefs.getBoolean("FCLongSwipeEnabled", false)
        leftSwipeUpAction = readAction(Xprefs, "leftSwipeUpAction")
        rightSwipeUpAction = readAction(Xprefs, "rightSwipeUpAction")
        twoFingerSwipeUpAction = readAction(Xprefs, "twoFingerSwipeUpAction")
        leftSwipeUpPercentage = Xprefs.getSliderFloat("leftSwipeUpPercentage", 25f) / 100f
        rightSwipeUpPercentage = Xprefs.getSliderFloat("rightSwipeUpPercentage", 25f) / 100f
        swipeUpPercentage = Xprefs.getSliderFloat("swipeUpPercentage", 25f) / 100f
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val otherActivityInputConsumerClass = ReflectedClass.of("com.android.quickstep.inputconsumers.OtherActivityInputConsumer")
            var launcherInputConsumerClass = ReflectedClass.ofIfPossible("com.android.quickstep.inputconsumers.LauncherInputConsumer")
            if (launcherInputConsumerClass.clazz == null) {
                launcherInputConsumerClass = ReflectedClass.ofIfPossible("com.android.quickstep.inputconsumers.OverviewInputConsumer")
            }
            val systemUiProxyClass = ReflectedClass.of("com.android.quickstep.SystemUiProxy")

            val displayBounds = SystemUtils.WindowManager()?.maximumWindowMetrics?.bounds
            if (displayBounds != null) {
                displayW = min(displayBounds.width(), displayBounds.height())
                displayH = max(displayBounds.width(), displayBounds.height())
            }

            systemUiProxyClass
                .afterConstruction()
                .run { hookParam -> mSystemUIProxy = hookParam.thisObject }

            systemUiProxyClass
                .after("setInitializationParams")
                .run { hookParam -> mSystemUIProxy = hookParam.thisObject }

            otherActivityInputConsumerClass
                .before("onMotionEvent")
                .run { hookParam -> onMotionEvent(hookParam, isOverViewListener = false) }

            launcherInputConsumerClass
                .before("onMotionEvent")
                .run { hookParam -> onMotionEvent(hookParam, isOverViewListener = true) }
        } catch (ignored: Throwable) {
        }
    }

    private fun onMotionEvent(param: HookHelper.RunParam, isOverViewListener: Boolean) {
        val event = param.args.getOrNull(0) as? MotionEvent ?: return
        val thisObj = param.thisObject ?: return

        var passedWindowMoveSlop = isOverViewListener
        if (!passedWindowMoveSlop) {
            val passedSlopField = findFieldIfExists(thisObj.javaClass, "mPassedWindowMoveSlop")
            if (passedSlopField != null) {
                try {
                    passedWindowMoveSlop = passedSlopField.getBoolean(thisObj)
                } catch (ignored: Throwable) {
                }
            }
        }

        val action = event.actionMasked
        val pointers = event.pointerCount

        if (action == MotionEvent.ACTION_DOWN) {
            saveFocusedTask()

            fcHandled = false
            swipeType = SWIPE_NONE

            if (isOverViewListener) {
                val startingInBoundsField = findFieldIfExists(thisObj.javaClass, "mStartingInActivityBounds")
                if (startingInBoundsField != null) {
                    try {
                        if (startingInBoundsField.getBoolean(thisObj)) {
                            return
                        }
                    } catch (ignored: Throwable) {
                    }
                }
            }

            mSwipeUpThreshold = event.y * (1f - swipeUpPercentage)
            mLongThreshold = event.y / 10f

            isLandscape = event.y < displayW
            val currentW = if (isLandscape) displayH else displayW

            if (pointers == 1) {
                if (leftSwipeUpAction != NO_ACTION && event.x < currentW * leftSwipeUpPercentage) {
                    swipeType = SWIPE_LEFT
                } else if (rightSwipeUpAction != NO_ACTION && event.x > currentW * (1f - rightSwipeUpPercentage)) {
                    swipeType = SWIPE_RIGHT
                }
            }
        }

        if (twoFingerSwipeUpAction != NO_ACTION && pointers == 2 && swipeType != SWIPE_TWO_FINGER) {
            swipeType = SWIPE_TWO_FINGER
        }

        if (passedWindowMoveSlop && swipeType != SWIPE_NONE) {
            param.result = null
        }

        if (pointers == 1) {
            val fcAllowed = swipeType == SWIPE_NONE

            if (fcAllowed && fcLongSwipeEnabled && event.y < mLongThreshold && !fcHandled && !isOverViewListener) {
                setObjectField(thisObj, "mActivePointerId", MotionEvent.INVALID_POINTER_ID)
                setObjectField(thisObj, "mPassedWindowMoveSlop", false)
                fcHandled = true
                runAction(ACTION_KILL_APP)
            }
        }

        if (action == MotionEvent.ACTION_UP && swipeType != SWIPE_NONE) {
            if (!isOverViewListener) {
                try {
                    callMethod(thisObj, "forceCancelGesture", event)
                } catch (ignored: Throwable) {
                }
            }

            if (event.y < mSwipeUpThreshold) {
                when (swipeType) {
                    SWIPE_LEFT -> runAction(leftSwipeUpAction)
                    SWIPE_RIGHT -> runAction(rightSwipeUpAction)
                    SWIPE_TWO_FINGER -> runAction(twoFingerSwipeUpAction)
                }
            }
            swipeType = SWIPE_NONE
            currentFocusedTask = null
        }
    }

    private fun isKillAppNeeded(): Boolean {
        return fcLongSwipeEnabled
                || leftSwipeUpAction == ACTION_KILL_APP
                || rightSwipeUpAction == ACTION_KILL_APP
                || twoFingerSwipeUpAction == ACTION_KILL_APP
    }

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
                } catch (t1: Throwable) {
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

            val recentTaskList = callMethod(
                proxy,
                "getRecentTasks",
                1,
                callMethod(Process.myUserHandle(), "getIdentifier")
            ) as? ArrayList<*> ?: return

            if (recentTaskList.isEmpty()) return

            val firstItem = recentTaskList[0] ?: return

            if (mTasksFieldName == null) {
                for (f in firstItem.javaClass.declaredFields) {
                    if (f.type.name.contains("RecentTaskInfo")) {
                        mTasksFieldName = f.name
                    }
                }
            }
            if (mTasksFieldName == null) {
                for (f in firstItem.javaClass.declaredFields) {
                    if (f.type.name.contains("List")) {
                        if (!f.isAccessible) {
                            f.isAccessible = true
                        }
                        @Suppress("UNCHECKED_CAST")
                        val list = f.get(firstItem) as? List<Any>
                        if (!list.isNullOrEmpty() && findFieldIfExists(list[0].javaClass, "isFocused") != null) {
                            mTasksFieldName = f.name
                            mTasksIsList = true
                        }
                    }
                }
            }

            val fieldName = mTasksFieldName ?: return

            val focusedTask = recentTaskList.firstOrNull { recentTask ->
                if (recentTask == null) return@firstOrNull false
                val targetObj = if (mTasksIsList) {
                    (getObjectField(recentTask, fieldName) as? List<*>)?.getOrNull(0)
                } else {
                    (getObjectField(recentTask, fieldName) as? Array<*>)?.getOrNull(0)
                }
                targetObj?.let { getObjectField(it, "isFocused") as? Boolean } == true
            }

            currentFocusedTask = focusedTask?.let {
                if (mTasksIsList) {
                    (getObjectField(it, fieldName) as? List<*>)?.getOrNull(0)
                } else {
                    (getObjectField(it, fieldName) as? Array<*>)?.getOrNull(0)
                }
            }
        } catch (ignored: Throwable) {
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
        thread {
            try {
                SystemUtils.threadSleep(200) // waiting for recents window to vanish
                mContext.sendBroadcast(Constants.getAppProfileSwitchIntent())
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun goToSleep() {
        val broadcast = Intent(Constants.ACTION_SLEEP)
        mContext.sendBroadcast(broadcast)
    }

    private fun killForeground() {
        if (currentFocusedTask == null) {
            saveFocusedTask()
        }
        val task = currentFocusedTask ?: return

        try {
            Toast.makeText(mContext, "App Killed", Toast.LENGTH_SHORT).show()

            val realActivity = getObjectField(task, "realActivity") as? ComponentName
            val userId = getObjectField(task, "userId")
            if (realActivity != null) {
                callMethod(
                    mContext.getSystemService(Context.ACTIVITY_SERVICE),
                    "forceStopPackageAsUser",
                    realActivity.packageName,
                    userId
                )
            }
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

            val screenshotRequestBuilderClass = ReflectedClass.of("com.android.internal.util.ScreenshotRequest\$Builder")
            val screenshotRequestBuilder = screenshotRequestBuilderClass
                .clazz
                .getConstructor(java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                .newInstance(TAKE_SCREENSHOT_FULLSCREEN, 1)

            callMethod(proxy, "takeScreenshot", callMethod(screenshotRequestBuilder, "build"))
        } catch (t: Throwable) {
            Logger.log("CustomNavGestures: takeScreenshot failed", t)
        }
    }

    private fun goHome() {
        val broadcast = Intent(Constants.ACTION_HOME)
        mContext.sendBroadcast(broadcast)
    }
}
