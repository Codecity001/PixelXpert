package sh.siava.pixelxpert.xposed.modpacks.android

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.view.Display
import android.view.WindowManager
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getIntField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.PackageManager
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.concurrent.thread

@FrameworkModPack
class PhoneWindowManager(context: Context) : XposedModPack(context) {

    private var windowMan: Any? = null
    private var userHandleList: List<UserHandle> = emptyList()
    private var currentPackage: String = ""
    private var currentUser: Int = -1

    companion object {
        private var broadcastRegistered = false
    }

    override fun onPreferenceUpdated(vararg key: String) {}

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    Constants.ACTION_HOME -> {
                        windowMan?.let { callMethod(it, "launchHomeFromHotKey", Display.DEFAULT_DISPLAY) }
                    }
                    Constants.ACTION_BACK -> {
                        windowMan?.let { callMethod(it, "backKeyPress") }
                    }
                    Constants.ACTION_SLEEP -> {
                        SystemUtils.sleep()
                    }
                    Constants.ACTION_SWITCH_APP_PROFILE -> {
                        switchAppProfile()
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun switchAppProfile() {
        if (currentUser < 0 || currentPackage.isEmpty() || userHandleList.isEmpty()) return

        var startIndex = 0
        for (i in userHandleList.indices) {
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

            if (i < userHandleList.size && isPackageAvailableForUser(currentPackage, userHandleList[i])) {
                switchAppToProfile(currentPackage, userHandleList[i])
                break
            }
        }
    }

    private fun switchAppToProfile(packageName: String, userHandle: UserHandle) {
        try {
            val taskManager = windowMan?.let { getObjectField(it, "mActivityTaskManagerInternal") } ?: return
            val appThread = callMethod(mContext, "getIApplicationThread")
            val launchIntent = PackageManager().getLaunchIntentForPackage(packageName)
            val handle = getObjectField(userHandle, "mHandle")

            callMethod(
                taskManager,
                "startActivityAsUser",
                appThread,
                packageName,
                null,
                launchIntent,
                null,
                0,
                null,
                handle
            )
        } catch (ignored: Throwable) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun isPackageAvailableForUser(packageName: String, userHandle: UserHandle): Boolean {
        return try {
            val packages = callMethod(
                mContext.packageManager,
                "getInstalledPackagesAsUser",
                PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                getObjectField(userHandle, "mHandle")
            ) as? List<PackageInfo> ?: return false

            packages.any { it.packageName == packageName && it.applicationInfo != null && it.applicationInfo.enabled }
        } catch (e: Throwable) {
            false
        }
    }

    @SuppressLint("WrongConstant")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val userManager = SystemUtils.UserManager()
            if (userManager != null) {
                @Suppress("UNCHECKED_CAST")
                val profiles = callMethod(userManager, "getProfiles", true) as? List<UserHandle>
                if (profiles != null) {
                    userHandleList = profiles
                }
            }

            if (!broadcastRegistered) {
                broadcastRegistered = true
                val intentFilter = IntentFilter().apply {
                    addAction(Constants.ACTION_HOME)
                    addAction(Constants.ACTION_BACK)
                    addAction(Constants.ACTION_SLEEP)
                    addAction(Constants.ACTION_SWITCH_APP_PROFILE)
                }
                mContext.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
            }

            val phoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager")

            phoneWindowManagerClass
                .before("onDefaultDisplayFocusChangedLw")
                .run { hookParam ->
                    val arg0 = hookParam.args.getOrNull(0) ?: return@run

                    thread {
                        try {
                            if (callMethod(arg0, "getBaseType") == WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW) {
                                val newPackageName = callMethod(arg0, "getOwningPackage") as? String ?: ""
                                val task = callMethod(arg0, "getTask")
                                val newUserID = getObjectField(task, "mUserId") as? Int ?: -1
                                if (newPackageName != currentPackage || newUserID != currentUser) {
                                    currentPackage = newPackageName
                                    currentUser = newUserID

                                    var availableOnOtherUsers = false
                                    for (userHandle in userHandleList) {
                                        val thisUserID = getObjectField(userHandle, "mHandle") as? Int ?: -1
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
                        } catch (ignored: Throwable) {
                        }
                    }
                }

            phoneWindowManagerClass
                .after("enableScreen")
                .run { hookParam ->
                    windowMan = hookParam.thisObject
                }
        } catch (ignored: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendAppProfileSwitchAvailable(isAvailable: Boolean) {
        thread {
            val broadcast = Intent().apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                action = Constants.ACTION_PROFILE_SWITCH_AVAILABLE
                `package` = Constants.SYSTEM_UI_PACKAGE
                putExtra("available", isAvailable)
            }
            mContext.sendBroadcast(broadcast)
        }
    }
}
