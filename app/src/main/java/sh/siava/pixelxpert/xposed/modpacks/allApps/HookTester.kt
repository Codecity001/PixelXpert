package sh.siava.pixelxpert.xposed.modpacks.allApps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.Constants.ACTION_CHECK_XPOSED_ENABLED
import sh.siava.pixelxpert.Constants.ACTION_XPOSED_CONFIRMED
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.CommonModPack
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger.log
import kotlin.concurrent.thread

@CommonModPack
class HookTester(context: Context) : XposedModPack(context) {

    override fun onPreferenceUpdated(vararg key: String) {}

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                thread {
                    val broadcast = Intent(ACTION_XPOSED_CONFIRMED).apply {
                        putExtra(
                            "packageName",
                            if (XPLauncher.isSystemServer) Constants.SYSTEM_FRAMEWORK_PACKAGE else param.packageName
                        )
                        setPackage(BuildConfig.APPLICATION_ID)
                    }

                    mContext.sendBroadcast(broadcast)
                    log("PixelXpert hook test successful for " + broadcast.getStringExtra("packageName"))
                }
            }
        }

        mContext.registerReceiver(
            broadcastReceiver,
            IntentFilter(ACTION_CHECK_XPOSED_ENABLED),
            Context.RECEIVER_EXPORTED
        )
    }
}
