package sh.siava.pixelxpert.xposed.modpacks.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.getStaticObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import java.util.Arrays
import java.util.HashSet
import java.util.regex.Pattern
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer

/**
 * Decides whether a taskbar exists and how it behaves.
 *
 * Three-button navigation moved into the taskbar on 15QPR2, so the pack both
 * reorders those buttons (`ThreeButtonLayoutMod`) and, through `taskBarMode`,
 * forces the taskbar on or off on builds that gate it behind device properties
 * (`LauncherDisplayInfo.isTablet`, `DeviceProperties.isPhone`,
 * `TaskbarConfiguration.isTaskbarPresent`). With `TaskbarAsRecents` the
 * taskbar doubles as a recents surface: recent tasks are mirrored into its
 * hotseat, the overflow region stops swallowing touch, and predictions are
 * short-circuited. Corner radius and height overrides post-process the built
 * `DeviceProfile`. Preference flips either double-toggle dark mode (forcing a
 * resource rebuild for the nav buttons) or kill the process so the hooks
 * re-register - and `taskBarMode` is read twice from prefs, quirk preserved.
 */
@LauncherModPack
class TaskbarActivator(context: Context) : XposedModPack(context) {
	private var ThreeButtonLayoutMod = false
	private var ThreeButtonLeft: String? = null
	private var ThreeButtonCenter: String? = null
	private var ThreeButtonRight: String? = null
	private var TopTaskTrackerClass: ReflectedClass? = null
	private var mCurrentTopTask: Any? = null
	private var mDeviceProfile: Any? = null

	override fun onPreferenceUpdated(vararg key: String?) {

		ThreeButtonLayoutMod = Xprefs.getBoolean("ThreeButtonLayoutMod", false)
		ThreeButtonLeft = Xprefs.getString("ThreeButtonLeft", "back")!!.replace("recent", "recent_apps")
		ThreeButtonCenter = Xprefs.getString("ThreeButtonCenter", "home")!!.replace("recent", "recent_apps")
		ThreeButtonRight = Xprefs.getString("ThreeButtonRight", "recent")!!.replace("recent", "recent_apps")

		var noToggle = false
		try {
			//noinspection ResultOfMethodCallIgnored
			java.util.Set.of(ThreeButtonLeft, ThreeButtonCenter, ThreeButtonRight)
		} catch (ignored: Throwable) {
			noToggle = true
			ThreeButtonLayoutMod = false
		}

		val darkToggleKeys = Arrays.asList(
			"ThreeButtonLayoutMod",
			"ThreeButtonLeft",
			"ThreeButtonCenter",
			"ThreeButtonRight")

		if (key.isNotEmpty() && !noToggle && darkToggleKeys.contains(key[0])) {
			SystemUtils.doubleToggleDarkMode()
		}


		val restartKeys = Arrays.asList(
			"taskBarMode",
			"TaskbarAsRecents",
			"taskbarHeightOverride",
			"TaskbarRadiusOverride",
			"TaskbarHideAllAppsIcon",
			"EnableGoogleRecents")

		if (key.isNotEmpty() && restartKeys.contains(key[0])) {
			SystemUtils.killSelf()
		}

		taskbarMode = Integer.parseInt(Xprefs.getString("taskBarMode", TASKBAR_DEFAULT.toString()))

		TaskbarAsRecents = Xprefs.getBoolean("TaskbarAsRecents", false)

		TaskbarRadiusOverride = Xprefs.getSliderFloat("TaskbarRadiusOverride", 1f)

		taskbarHeightOverride = Xprefs.getSliderFloat("taskbarHeightOverride", 100f) / 100f

		taskbarMode = Integer.parseInt(Xprefs.getString("taskBarMode", "0"))

		TaskbarOnLauncher = Xprefs.getBoolean("TaskbarOnLauncher", false)

		GoogleRecents = Xprefs.getBoolean("EnableGoogleRecents", false)
	}


	@SuppressLint("DiscouragedApi")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val DeviceProfileBuilderClass = ReflectedClass.of("com.android.launcher3.DeviceProfile\$Builder")
		val TaskbarActivityContextClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarActivityContext")
		val DisplayControllerInfoClass = ReflectedClass.of("com.android.launcher3.display.LauncherDisplayInfo")
		val StateControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarLauncherStateController")
		val AbstractNavButtonLayoutterClass = ReflectedClass.of("com.android.launcher3.taskbar.navbutton.AbstractNavButtonLayoutter")
		val RecentAppsControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarRecentAppsController")
		val QuickSwitchStateClass = ReflectedClass.of("com.android.launcher3.uioverrides.states.QuickSwitchState")
		val TaskbarUiControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.FallbackTaskbarUIController")
		val TaskbarProfileClass = ReflectedClass.of("com.android.launcher3.deviceprofile.TaskbarProfile")
		val TaskbarOverlayDragLayerClass = ReflectedClass.of("com.android.launcher3.taskbar.overlay.TaskbarOverlayDragLayer")
		val KeyboardQuickSwitchControllerClass = ReflectedClass.of("com.android.launcher3.taskbar.KeyboardQuickSwitchController")
		val TaskbarViewClass = ReflectedClass.of("com.android.launcher3.taskbar.TaskbarView")
		TopTaskTrackerClass = ReflectedClass.of("com.android.quickstep.TopTaskTracker")
		val DevicePropertiesClass = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.DeviceProperties")
		val TaskbarConfigurationClass = ReflectedClass.ofIfPossible("com.android.launcher3.deviceprofile.TaskbarConfiguration")

		AbstractNavButtonLayoutterClass
			.afterConstruction()
			.run { param ->
				if (!ThreeButtonLayoutMod) return@run

				val navButtonContainer = getObjectField(param.thisObject, "navButtonContainer") as ViewGroup

				setObjectField(param.thisObject, "backButton", navButtonContainer.findViewById(SystemUtils.idOf(ThreeButtonLeft!!)))
				setObjectField(param.thisObject, "homeButton", navButtonContainer.findViewById(SystemUtils.idOf(ThreeButtonCenter!!)))
				setObjectField(param.thisObject, "recentsButton", navButtonContainer.findViewById(SystemUtils.idOf(ThreeButtonRight!!)))
			}

		DisplayControllerInfoClass
			.before("isTablet")
			.run { param ->
				if (taskbarMode == TASKBAR_DEFAULT) return@run

				param.setResult(taskbarMode == TASKBAR_ON)
			}

		TaskbarConfigurationClass
			.afterConstruction()
			.run { param ->
				if (taskbarMode == TASKBAR_ON) {
					setObjectField(param.thisObject, "isTaskbarPresent", true)
				}
			}

		DevicePropertiesClass
			.afterConstruction()
			.run { param ->
				if (taskbarMode == TASKBAR_ON) {
					setObjectField(param.thisObject, "isPhone", false)
				}
			}


		//workaround of taskbar recents overflow fails to capture touch events for no known reason
		TaskbarOverlayDragLayerClass
			.findFirstInstance { instance ->
				if (!TaskbarAsRecents) return@findFirstInstance

				ReflectedClass.of(getObjectField(instance, "mTaskbarInsetsComputer").javaClass)
					.before("onComputeInternalInsets")
					.run { param ->
						//doesn't seem to reach its end at all. may for desktop mode?
//						onComputeInternalInsets(instance, param.args[0])
						param.setResult(null)
					}
			}

		//workaround of taskbar recents overflow falsely showing "no recent items"
		KeyboardQuickSwitchControllerClass
			.before("processLoadedTasks")
			.run { param ->
				if (TaskbarAsRecents)
					param.args[0] = false
			}

		//temp workaround of launcher crash bug on split tasks
		TaskbarViewClass
			.before("updateRecents")
			.run { param ->
				@Suppress("UNCHECKED_CAST")
				val recents = param.args[1] as List<Any>
				param.args[1] = recents.stream().filter { t -> !t.javaClass.name.contains("Split") }.toList()
			}

		KeyboardQuickSwitchControllerClass
			.before("openQuickSwitchView")
			.run { param ->
				@Suppress("UNCHECKED_CAST")
				val exclusionList = param.args[1] as HashSet<Any>
				if (notInHomeScreen()) {
					exclusionList.add(callMethod(mCurrentTopTask, "getTaskId"))
				}
			}

		StateControllerClass
			.before("isInLauncher")
			.run { param ->
				if (TaskbarOnLauncher) {
					param.setResult(false)
				}
			}

		QuickSwitchStateClass
			.before("isTaskbarStashed")
			.run { param ->
				if (TaskbarOnLauncher) {
					param.setResult(false)
				}
			}

		TaskbarUiControllerClass
			.before("isIn3pHomeOrRecents")
			.run { param ->
				if (TaskbarOnLauncher) {
					param.setResult(false)
				}
			}

		val cornerRadiusConsumer = ReflectionConsumer { param ->
			if (taskbarMode == TASKBAR_ON && TaskbarRadiusOverride != 1f) {
				param.setResult(
					Math.round(param.getResult<Int>() * TaskbarRadiusOverride))
			}
		}

		TaskbarActivityContextClass.after("getLeftCornerRadius").run(cornerRadiusConsumer)
		TaskbarActivityContextClass.after("getRightCornerRadius").run(cornerRadiusConsumer)

		DeviceProfileBuilderClass
			.after("build")
			.run { param ->
				if (taskbarMode == TASKBAR_DEFAULT) return@run

				val taskbarEnabled = taskbarMode == TASKBAR_ON

				if (taskbarEnabled) {
					mDeviceProfile = param.getResult<Any?>()
				}

			}

		TaskbarProfileClass
			.after("getHeight")
			.run { param ->
				if (taskbarMode == TASKBAR_ON && taskbarHeightOverride != 1f) {
					param.setResult(Math.round(param.getResult<Int>() * taskbarHeightOverride))
				}
			}

		RecentAppsControllerClass
			.afterConstruction()
			.run { param ->
				if (GoogleRecents || (taskbarMode == TASKBAR_ON && TaskbarAsRecents)) { //on 16+ we use the builtin recent tasks
					//noinspection OptionalGetWithoutIsPresent
					RecentAppsControllerClass.findMethods(Pattern.compile("setCanShowRecentApps")).stream().findFirst().get()
						.invoke(param.thisObject, true)
				}
			}

		//noinspection OptionalGetWithoutIsPresent
		val reloadRecentTasksIfNeeded: Method = RecentAppsControllerClass
			.findMethods(Pattern.compile(".*reloadRecentTasksIfNeeded.*")).stream().findFirst().get()

		RecentAppsControllerClass.before("isReplacingPredictions").run { param ->
			if (TaskbarAsRecents && taskbarMode == TASKBAR_ON)
				param.setResult(true)
		}
		RecentAppsControllerClass
			.before("onRecentsOrHotseatChanged")
			.run { param ->
				if (taskbarMode == TASKBAR_ON && TaskbarAsRecents) {
					val allRecentTasks = getObjectField(param.thisObject, "allRecentTasks") as List<*>

					if (allRecentTasks.size < 2) //there's nothing to show as recent
					{
						return@run
					}

					val shownHotseatItems = getObjectField(param.thisObject, "shownHotseatItems") as MutableList<*>
					if (!shownHotseatItems.isEmpty()) {
						shownHotseatItems.clear()
					}

					mCurrentTopTask = getCurrentTopTask()

					var newShownTasks = allRecentTasks.subList(Math.max(0, allRecentTasks.size - getNumShownHotseatIcons() - 1), Math.max(allRecentTasks.size, 0))

					if (notInHomeScreen()) //hiding running task from taskbar
					{
						newShownTasks = newShownTasks.subList(0, Math.max(0, newShownTasks.size - 1))
					}

					val oldShownTasks = getObjectField(param.thisObject, "shownTasks") as List<*>

					if (newShownTasks == oldShownTasks) {
						param.setResult(false)
						return@run
					}

					setObjectField(param.thisObject, "shownTasks", newShownTasks)

					try { //16qpr3
						callMethod(param.thisObject, "fetchIcons", false)
					} catch (ignored: Throwable) { //16qpr2
						callMethod(param.thisObject, "fetchIcons")
					}

					setObjectField(param.thisObject, "needsRecentsTasksReload", true)

					reloadRecentTasksIfNeeded.invoke(param.thisObject, param.thisObject)

					param.setResult(true)
				}
			}
	}

	fun getNumShownHotseatIcons(): Int {
		val hotSeatProfile = getObjectField(mDeviceProfile, "mHotseatProfile")
		return getIntField(hotSeatProfile, "numShownIcons")
	}

	fun getCurrentTopTask(): Any? {
		val INSTANCE = getStaticObjectField(TopTaskTrackerClass!!.clazz, "INSTANCE")
		val topTaskTracker = callMethod(INSTANCE, "get", mContext)
		return callMethod(topTaskTracker, "getCachedTopTask", true, mContext.display.displayId)
	}

	fun notInHomeScreen(): Boolean = !(callMethod(mCurrentTopTask, "isHomeTask") as Boolean)

	//noinspection unused
	fun onComputeInternalInsets(thisObject: Any?, internalInsetsInfo: Any?) {
		val mOverlayController = getObjectField(getObjectField(thisObject, "mContainer"), "mOverlayController")
		val mOverlayContext = getObjectField(mOverlayController, "mOverlayContext")
		if (mOverlayContext == null) return
		val mDragController = getObjectField(mOverlayContext, "mDragController")
		val mTaskbarContext = getObjectField(mOverlayController, "mTaskbarContext")
		val mControllers = getObjectField(mTaskbarContext, "mControllers")
		val taskbarDragController = getObjectField(mControllers, "taskbarDragController")
		if (!getBooleanField(mDragController, "mIsSystemDragInProgress") && !getBooleanField(taskbarDragController, "mIsSystemDragInProgress")) {
			return
		}
		callMethod(getIntField(internalInsetsInfo, "touchableRegion"), "setEmpty")
		callMethod(internalInsetsInfo, "setTouchableInsets", 3) //from android.view.ViewTreeObserver
	}

	companion object {
		const val TASKBAR_DEFAULT = 0
		const val TASKBAR_ON = 1
		//noinspection unused
		const val TASKBAR_OFF = 2

		private var taskbarMode = 0
		private var TaskbarAsRecents = false
		private var TaskbarOnLauncher = false
		private var GoogleRecents = false
		private var taskbarHeightOverride = 1f
		private var TaskbarRadiusOverride = 1f
	}
}
