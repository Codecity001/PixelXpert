package sh.siava.pixelxpert.xposed.modpacks.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.view.Display
import android.view.WindowManager
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField

/**
 * System-server side of the module's four global hot-key actions: home, back,
 * sleep and app-profile switching. One exported receiver is registered the
 * first time the pack loads; it answers [Constants.ACTION_HOME] and
 * [Constants.ACTION_BACK] by driving the window manager's own hot-key entry
 * points, [Constants.ACTION_SLEEP] through [SystemUtils.sleep], and
 * [Constants.ACTION_SWITCH_APP_PROFILE] by walking the users the device is
 * provisioned for until the focused package shows up somewhere else.
 *
 * The other half watches focus: `onDefaultDisplayFocusChangedLw` reports which
 * app gained the display, and each change of package or user re-checks whether
 * that app is installed and enabled for any other profile, publishing the
 * answer as [Constants.ACTION_PROFILE_SWITCH_AVAILABLE] so the UI knows when a
 * profile switch is worth offering. The window manager handle itself is only
 * captured once `enableScreen` runs, which is why every action funnels through
 * the `windowMan` field rather than looking the service up per call.
 */
@FrameworkModPack
class PhoneWindowManager(context: Context) : XposedModPack(context) {

	companion object {
		private var broadcastRegistered = false
	}

	private var windowMan: Any? = null
	private lateinit var userHandleList: List<UserHandle>
	private var currentPackage = ""
	private var currentUser = -1

	override fun onPreferenceUpdated(vararg key: String?) {
	}

	private val broadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			try {
				val action = intent.action
				when (action) {
					Constants.ACTION_HOME -> callMethod(windowMan, "launchHomeFromHotKey", Display.DEFAULT_DISPLAY)
					Constants.ACTION_BACK -> callMethod(windowMan, "backKeyPress")
					Constants.ACTION_SLEEP -> SystemUtils.sleep()
					Constants.ACTION_SWITCH_APP_PROFILE -> switchAppProfile()
				}
			} catch (ignored: Throwable) {
			}
		}
	}

	private fun switchAppProfile() {
		if (currentUser < 0 || currentPackage.isEmpty()) return

		var startIndex = 0
		for (i in 0 until userHandleList.size) {
			val userID = getIntField(userHandleList[i], "mHandle")
			if (userID == currentUser) {
				startIndex = i
				break
			}
		}

		var looped = false
		var i = startIndex
		while (i < userHandleList.size) {
			i++
			if (i > userHandleList.size - 1 && !looped) {
				i = 0
				looped = true
			}

			if (isPackageAvailableForUser(currentPackage, userHandleList[i])) {
				switchAppToProfile(currentPackage, userHandleList[i])
				break
			}
		}
	}

	private fun switchAppToProfile(packageName: String, userHandle: UserHandle) {
		try {
			callMethod(
				getObjectField(windowMan, "mActivityTaskManagerInternal"),
				"startActivityAsUser",
				callMethod(mContext, "getIApplicationThread"),
				packageName,
				null,
				SystemUtils.PackageManager()!!.getLaunchIntentForPackage(packageName),
				null,
				0,
				null,
				getObjectField(userHandle, "mHandle"),
			)
		} catch (ignored: Throwable) {
		}
	}

	@Suppress("UNCHECKED_CAST")
	private fun isPackageAvailableForUser(packageName: String, userHandle: UserHandle): Boolean {
		//noinspection unchecked
		return (
			callMethod(
				mContext.packageManager,
				"getInstalledPackagesAsUser",
				PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
				getObjectField(userHandle, "mHandle"),
			) as List<PackageInfo>
			).stream().anyMatch { packageInfo ->
			packageInfo.packageName.equals(packageName) && packageInfo.applicationInfo!!.enabled
		}
	}

	@SuppressLint("WrongConstant")
	@Suppress("UNCHECKED_CAST")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		//noinspection unchecked
		userHandleList = callMethod(SystemUtils.UserManager(), "getProfiles", true) as List<UserHandle>

		if (!broadcastRegistered) {
			broadcastRegistered = true

			val intentFilter = IntentFilter()
			intentFilter.addAction(Constants.ACTION_HOME)
			intentFilter.addAction(Constants.ACTION_BACK)
			intentFilter.addAction(Constants.ACTION_SLEEP)
			intentFilter.addAction(Constants.ACTION_SWITCH_APP_PROFILE)
			// for Android 14, receiver flag is mandatory
			mContext.registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED)
		}

		try {
			val PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")

			PhoneWindowManagerClass
				.before("onDefaultDisplayFocusChangedLw")
				.run { param ->
					if (param.args[0] == null) return@run

					Thread {
						if (callMethod(param.args[0], "getBaseType") == WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW) {
							val newPackageName = callMethod(param.args[0], "getOwningPackage") as String
							val newUserID = getObjectField(callMethod(param.args[0], "getTask"), "mUserId") as Int
							if (!newPackageName.equals(currentPackage) || newUserID != currentUser) {
								currentPackage = newPackageName
								currentUser = newUserID

								var availableOnOtherUsers = false
								for (userHandle in userHandleList) {
									val thisUserID = getObjectField(userHandle, "mHandle") as Int
									if (thisUserID != currentUser) {
										if (isPackageAvailableForUser(currentPackage, userHandle)) {
											availableOnOtherUsers = true
											break
										}
									}
								}
								sendAppProfileSwitchAvailable(availableOnOtherUsers)
							}
						}
					}.start()
				}

			PhoneWindowManagerClass
				.after("enableScreen")
				.run { param -> windowMan = param.thisObject }
		} catch (ignored: Throwable) {
		}
	}

	@SuppressLint("MissingPermission")
	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		windowMan?.let { outState["windowManager"] = it }
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		windowMan = savedState["windowManager"]
	}

	override fun onUnloaded() {
		if (broadcastRegistered) {
			runCatching { mContext.unregisterReceiver(broadcastReceiver) }
			broadcastRegistered = false
		}
	}

	private fun sendAppProfileSwitchAvailable(isAvailable: Boolean) {
		Thread {
			val broadcast = Intent()
			broadcast.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
			broadcast.setAction(Constants.ACTION_PROFILE_SWITCH_AVAILABLE)
			broadcast.setPackage(Constants.SYSTEM_UI_PACKAGE)
			broadcast.putExtra("available", isAvailable)
			mContext.sendBroadcast(broadcast)
		}.start()
	}
}
