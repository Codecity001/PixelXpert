package sh.siava.pixelxpert.xposed.modpacks.allApps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.CommonModPack

/**
 * Answers the Hooks page's "are you in there?" ping.
 *
 * The page broadcasts [Constants.ACTION_CHECK_XPOSED_ENABLED] and waits five
 * seconds; every process this pack is running in replies with
 * [Constants.ACTION_XPOSED_CONFIRMED] naming itself, and the page turns each
 * reply into a "hooked" row. Nothing else in the module sends that reply, so
 * without this pack every row reports no response no matter how healthy the
 * hooks are.
 *
 * The reply is addressed with `setPackage` rather than left implicit, so it
 * reaches the module app and nobody else. It runs off a broadcast thread
 * because [XposedModPack.onPackageLoaded] returns immediately and the reply
 * is the only thing this pack exists to do.
 *
 * `@CommonModPack` gives `targetPackage = ""`, which
 * [sh.siava.pixelxpert.xposed.XPLauncher.loadModPacks] reads as "every
 * package" - so the pack registers in each of the five scoped processes, plus
 * system server, which is what makes the framework row answer as `android`.
 */
@CommonModPack
class HookTester(context: Context) : XposedModPack(context) {

	private var broadcastReceiver: BroadcastReceiver? = null

	/** Nothing this pack reads; declared because [XposedModPack] requires it. */
	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent?) {
				Thread {
					val broadcast = Intent(Constants.ACTION_XPOSED_CONFIRMED)

					broadcast.putExtra(
						"packageName",
						if (XPLauncher.isSystemServer) {
							Constants.SYSTEM_FRAMEWORK_PACKAGE
						} else {
							PRParam.packageName
						},
					)

					broadcast.setPackage(BuildConfig.APPLICATION_ID)

					mContext.sendBroadcast(broadcast)

					log(
						"PixelXpert hook test successful for " +
							broadcast.getStringExtra("packageName"),
					)
				}.start()
			}
		}

		mContext.registerReceiver(
			receiver,
			IntentFilter(Constants.ACTION_CHECK_XPOSED_ENABLED),
			Context.RECEIVER_EXPORTED,
		)
		broadcastReceiver = receiver
	}

	override fun onUnloaded() {
		broadcastReceiver?.let { runCatching { mContext.unregisterReceiver(it) } }
		broadcastReceiver = null
	}
}
