package sh.siava.pixelxpert.xposed.modpacks.allApps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.google.gson.Gson
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.CommonModPack
import sh.siava.pixelxpert.xposed.inspector.FrameworkInspector
import sh.siava.pixelxpert.xposed.inspector.LauncherInspector
import sh.siava.pixelxpert.xposed.inspector.SystemUIInspector
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * ModPack that listens for hook audit requests and executes a single-pass reflection
 * inspection of the hosting process's classloader.
 *
 * CRITICAL CONSTRAINT: Executes exactly once per audit request with zero retries.
 * If any class, method, or constructor is missing or fails to load, it is recorded
 * immediately and the audit moves forward.
 */
@CommonModPack
class HookAuditor(context: Context) : XposedModPack(context) {

	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val broadcastReceiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent?) {
				if (intent?.action != Constants.ACTION_REQUEST_HOOK_AUDIT) return

				// Execute off the main UI thread of the target process
				Thread {
					val targetPackage = if (XPLauncher.isSystemServer) {
						Constants.SYSTEM_FRAMEWORK_PACKAGE
					} else {
						PRParam.packageName
					}

					val report = when (targetPackage) {
						Constants.SYSTEM_UI_PACKAGE -> SystemUIInspector.inspect(PRParam.classLoader)
						Constants.SYSTEM_FRAMEWORK_PACKAGE -> {
							val fwLoader = ReflectedClass.getFrameworkClassloader() ?: PRParam.classLoader
							FrameworkInspector.inspect(fwLoader)
						}
						Constants.LAUNCHER_PACKAGE -> LauncherInspector.inspect(PRParam.classLoader)
						else -> null
					}

					if (report != null) {
						try {
							val json = Gson().toJson(report)
							val resultBroadcast = Intent(Constants.ACTION_HOOK_AUDIT_RESULT)
							resultBroadcast.putExtra("packageName", targetPackage)
							resultBroadcast.putExtra("auditJson", json)
							resultBroadcast.setPackage(BuildConfig.APPLICATION_ID)
							mContext.sendBroadcast(resultBroadcast)
						} catch (t: Throwable) {
							log("HookAuditor failed to serialize audit for $targetPackage: ${t.message}")
						}
					}
				}.start()
			}
		}

		mContext.registerReceiver(
			broadcastReceiver,
			IntentFilter(Constants.ACTION_REQUEST_HOOK_AUDIT),
			Context.RECEIVER_EXPORTED,
		)
	}
}
