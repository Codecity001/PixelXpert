package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getStaticObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import java.lang.reflect.Method
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.roundToInt

@LauncherModPack
class TaskbarActivator(context: Context) : XposedModPack(context) {

    companion object {
        const val TASKBAR_DEFAULT = 0
        const val TASKBAR_ON = 1
        const val TASKBAR_OFF = 2

        private var taskbarMode = 0
        private var taskbarAsRecents = false
        private var taskbarOnLauncher = false
        private var googleRecents = false
        private var taskbarHeightOverride = 1f
        private var taskbarRadiusOverride = 1f
    }

    private var threeButtonLayoutMod = false
    private var threeButtonLeft: String = "back"
    private var threeButtonCenter: String = "home"
    private var threeButtonRight: String = "recent_apps"
    private var topTaskTrackerClass: ReflectedClass? = null
    private var mCurrentTopTask: Any? = null
    private var mDeviceProfile: Any? = null

    override fun onPreferenceUpdated(vararg key: String) {
        threeButtonLayoutMod = Xprefs.getBoolean("ThreeButtonLayoutMod", false)
        threeButtonLeft = (Xprefs.getString("ThreeButtonLeft", "back") ?: "back").replace("recent", "recent_apps")
        threeButtonCenter = (Xprefs.getString("ThreeButtonCenter", "home") ?: "home").replace("recent", "recent_apps")
        threeButtonRight = (Xprefs.getString("ThreeButtonRight", "recent") ?: "recent").replace("recent", "recent_apps")

        var noToggle = false
        try {
            setOf(threeButtonLeft, threeButtonCenter, threeButtonRight)
        } catch (ignored: Throwable) {
            noToggle = true
            threeButtonLayoutMod = false
        }

        val darkToggleKeys = listOf(
            "ThreeButtonLayoutMod",
            "ThreeButtonLeft",
            "ThreeButtonCenter",
            "ThreeButtonRight"
        )

        if (key.isNotEmpty() && !noToggle && darkToggleKeys.contains(key[0])) {
            SystemUtils.doubleToggleDarkMode()
        }

        val restartKeys = listOf(
            "taskBarMode",
            "TaskbarAsRecents",
            "taskbarHeightOverride",
            "TaskbarRadiusOverride",
            "TaskbarHideAllAppsIcon",
            "EnableGoogleRecents"
        )

        if (key.isNotEmpty() && restartKeys.contains(key[0])) {
            SystemUtils.killSelf()
        }

        taskbarMode = Xprefs.getString("taskBarMode", "$TASKBAR_DEFAULT")?.toIntOrNull() ?: TASKBAR_DEFAULT
        taskbarAsRecents = Xprefs.getBoolean("TaskbarAsRecents", false)
        taskbarRadiusOverride = Xprefs.getSliderFloat("TaskbarRadiusOverride", 1f)
        taskbarHeightOverride = Xprefs.getSliderFloat("taskbarHeightOverride", 100f) / 100f
        taskbarOnLauncher = Xprefs.getBoolean("TaskbarOnLauncher", false)
        googleRecents = Xprefs.getBoolean("EnableGoogleRecents", false)
    }

    @SuppressLint("DiscouragedApi")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val deviceProfileBuilderClass = ReflectedClass.of("com.android.launcher3.DeviceProfile\$Builder")
            val taskbarActivityContextClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarActivityContext")
            val displayControllerInfoClass = ReflectedClass.of("com.android.launcher3.display.LauncherDisplayInfo")
            val stateControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarLauncherStateController")
            val abstractNavButtonLayoutterClass = ReflectedClass.of("com.android.launcher3.taskbar.navbutton.AbstractNavButtonLayoutter")
            val recentAppsControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarRecentAppsController")
            val quickSwitchStateClass = ReflectedClass.of("com.android.launcher3.uioverrides.states.QuickSwitchState")
            val taskbarUiControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.FallbackTaskbarUIController")
            val taskbarProfileClass = ReflectedClass.of("com.android.launcher3.deviceprofile.TaskbarProfile")
            val taskbarOverlayDragLayerClass = ReflectedClass.of("com.android.launcher3.taskbar.overlay.TaskbarOverlayDragLayer")
            val keyboardQuickSwitchControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.KeyboardQuickSwitchController")
            val taskbarViewClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarView")
            topTaskTrackerClass = ReflectedClass.of("com.android.quickstep.TopTaskTracker")
            val devicePropertiesClass = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.DeviceProperties")
            val taskbarConfigurationClass = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.TaskbarConfiguration")

            // 3 button nav order
            abstractNavButtonLayoutterClass
                .afterConstruction()
                .run { hookParam ->
                    if (!threeButtonLayoutMod) return@run

                    val thisObj = hookParam.thisObject ?: return@run
                    val navButtonContainer = getObjectField(thisObj, "navButtonContainer") as? ViewGroup ?: return@run

                    setObjectField(thisObj, "backButton", navButtonContainer.findViewById(idOf(threeButtonLeft)))
                    setObjectField(thisObj, "homeButton", navButtonContainer.findViewById(idOf(threeButtonCenter)))
                    setObjectField(thisObj, "recentsButton", navButtonContainer.findViewById(idOf(threeButtonRight)))
                }

            // enable taskbar
            displayControllerInfoClass
                .before("isTablet")
                .run { hookParam ->
                    if (taskbarMode == TASKBAR_DEFAULT) return@run
                    hookParam.result = (taskbarMode == TASKBAR_ON)
                }

            taskbarConfigurationClass
                .afterConstruction()
                .run { hookParam ->
                    if (taskbarMode == TASKBAR_ON) {
                        hookParam.thisObject?.let { setObjectField(it, "isTaskbarPresent", true) }
                    }
                }

            devicePropertiesClass
                .afterConstruction()
                .run { hookParam ->
                    if (taskbarMode == TASKBAR_ON) {
                        hookParam.thisObject?.let { setObjectField(it, "isPhone", false) }
                    }
                }

            // workaround for taskbar recents overflow
            taskbarOverlayDragLayerClass
                .findFirstInstance { instance ->
                    if (!taskbarAsRecents) return@findFirstInstance

                    val insetsComputer = getObjectField(instance, "mTaskbarInsetsComputer") ?: return@findFirstInstance
                    ReflectedClass.of(insetsComputer.javaClass)
                        .before("onComputeInternalInsets")
                        .run { hookParam ->
                            hookParam.result = null
                        }
                }

            keyboardQuickSwitchControllerClass
                .before("processLoadedTasks")
                .run { hookParam ->
                    if (taskbarAsRecents) {
                        hookParam.args[0] = false
                    }
                }

            // workaround for split tasks
            taskbarViewClass
                .before("updateRecents")
                .run { hookParam ->
                    @Suppress("UNCHECKED_CAST")
                    val recents = hookParam.args.getOrNull(1) as? List<Any> ?: return@run
                    hookParam.args[1] = recents.filter { !it.javaClass.name.contains("Split") }
                }

            keyboardQuickSwitchControllerClass
                .before("openQuickSwitchView")
                .run { hookParam ->
                    @Suppress("UNCHECKED_CAST")
                    val exclusionList = hookParam.args.getOrNull(1) as? HashSet<Any> ?: return@run
                    if (notInHomeScreen()) {
                        mCurrentTopTask?.let { topTask ->
                            callMethod(topTask, "getTaskId")?.let { exclusionList.add(it) }
                        }
                    }
                }

            // show on home screen
            stateControllerClass
                .before("isInLauncher")
                .run { hookParam ->
                    if (taskbarOnLauncher) {
                        hookParam.result = false
                    }
                }

            quickSwitchStateClass
                .before("isTaskbarStashed")
                .run { hookParam ->
                    if (taskbarOnLauncher) {
                        hookParam.result = false
                    }
                }

            taskbarUiControllerClass
                .before("isIn3pHomeOrRecents")
                .run { hookParam ->
                    if (taskbarOnLauncher) {
                        hookParam.result = false
                    }
                }

            // taskbar corner radius
            val cornerRadiusConsumer: (sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.RunParam) -> Unit = { hookParam ->
                if (taskbarMode == TASKBAR_ON && taskbarRadiusOverride != 1f) {
                    val origRadius = hookParam.result as? Int ?: 0
                    hookParam.result = (origRadius * taskbarRadiusOverride).roundToInt()
                }
            }

            taskbarActivityContextClass.after("getLeftCornerRadius").run(cornerRadiusConsumer)
            taskbarActivityContextClass.after("getRightCornerRadius").run(cornerRadiusConsumer)

            // recentbar
            deviceProfileBuilderClass
                .after("build")
                .run { hookParam ->
                    if (taskbarMode == TASKBAR_DEFAULT) return@run
                    if (taskbarMode == TASKBAR_ON) {
                        mDeviceProfile = hookParam.result
                    }
                }

            taskbarProfileClass
                .after("getHeight")
                .run { hookParam ->
                    if (taskbarMode == TASKBAR_ON && taskbarHeightOverride != 1f) {
                        val origH = hookParam.result as? Int ?: 0
                        hookParam.result = (origH * taskbarHeightOverride).roundToInt()
                    }
                }

            recentAppsControllerClass
                .afterConstruction()
                .run { hookParam ->
                    if (googleRecents || (taskbarMode == TASKBAR_ON && taskbarAsRecents)) {
                        val method = recentAppsControllerClass.findMethods(Pattern.compile("setCanShowRecentApps")).firstOrNull()
                        method?.invoke(hookParam.thisObject, true)
                    }
                }

            val reloadRecentTasksIfNeeded: Method? = recentAppsControllerClass
                .findMethods(Pattern.compile(".*reloadRecentTasksIfNeeded.*")).firstOrNull()

            recentAppsControllerClass.before("isReplacingPredictions").run { hookParam ->
                if (taskbarAsRecents && taskbarMode == TASKBAR_ON) {
                    hookParam.result = true
                }
            }

            recentAppsControllerClass
                .before("onRecentsOrHotseatChanged")
                .run { hookParam ->
                    val thisObj = hookParam.thisObject ?: return@run
                    if (taskbarMode == TASKBAR_ON && taskbarAsRecents) {
                        val allRecentTasks = getObjectField(thisObj, "allRecentTasks") as? List<*> ?: return@run

                        if (allRecentTasks.size < 2) return@run

                        val shownHotseatItems = getObjectField(thisObj, "shownHotseatItems") as? MutableList<*>
                        shownHotseatItems?.clear()

                        mCurrentTopTask = getCurrentTopTask()

                        val startIdx = max(0, allRecentTasks.size - getNumShownHotseatIcons() - 1)
                        var newShownTasks = allRecentTasks.subList(startIdx, max(allRecentTasks.size, 0))

                        if (notInHomeScreen()) {
                            newShownTasks = newShownTasks.subList(0, max(0, newShownTasks.size - 1))
                        }

                        val oldShownTasks = getObjectField(thisObj, "shownTasks") as? List<*>

                        if (newShownTasks == oldShownTasks) {
                            hookParam.result = false
                            return@run
                        }

                        setObjectField(thisObj, "shownTasks", newShownTasks)

                        try {
                            callMethod(thisObj, "fetchIcons", false)
                        } catch (ignored: Throwable) {
                            callMethod(thisObj, "fetchIcons")
                        }

                        setObjectField(thisObj, "needsRecentsTasksReload", true)
                        reloadRecentTasksIfNeeded?.invoke(thisObj, thisObj)

                        hookParam.result = true
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    fun getNumShownHotseatIcons(): Int {
        val dp = mDeviceProfile ?: return 0
        val hotSeatProfile = getObjectField(dp, "mHotseatProfile") ?: return 0
        return getIntField(hotSeatProfile, "numShownIcons")
    }

    fun getCurrentTopTask(): Any? {
        val topTracker = topTaskTrackerClass?.clazz ?: return null
        val instance = getStaticObjectField(topTracker, "INSTANCE") ?: return null
        val topTaskTracker = callMethod(instance, "get", mContext) ?: return null
        return callMethod(topTaskTracker, "getCachedTopTask", true, mContext.display?.displayId ?: 0)
    }

    fun notInHomeScreen(): Boolean {
        val task = mCurrentTopTask ?: return false
        return callMethod(task, "isHomeTask") != true
    }

    fun onComputeInternalInsets(thisObject: Any, internalInsetsInfo: Any) {
        val container = getObjectField(thisObject, "mContainer") ?: return
        val overlayController = getObjectField(container, "mOverlayController") ?: return
        val overlayContext = getObjectField(overlayController, "mOverlayContext") ?: return
        val dragController = getObjectField(overlayContext, "mDragController") ?: return
        val taskbarContext = getObjectField(overlayController, "mTaskbarContext") ?: return
        val controllers = getObjectField(taskbarContext, "mControllers") ?: return
        val taskbarDragController = getObjectField(controllers, "taskbarDragController") ?: return

        if (!getBooleanField(dragController, "mIsSystemDragInProgress") && !getBooleanField(taskbarDragController, "mIsSystemDragInProgress")) {
            return
        }
        val touchableRegion = getIntField(internalInsetsInfo, "touchableRegion")
        callMethod(touchableRegion, "setEmpty")
        callMethod(internalInsetsInfo, "setTouchableInsets", 3)
    }
}
