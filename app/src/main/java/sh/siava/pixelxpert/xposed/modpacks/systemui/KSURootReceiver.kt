package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.utils.AppUtils
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack

@SystemUIModPack
class KSURootReceiver(context: Context) : XposedModPack(context) {

    override fun onPreferenceUpdated(vararg key: String) {}

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                AppUtils.runKSURootActivity(mContext, false)
            }
        }

        val filter = IntentFilter(Constants.ACTION_KSU_ACQUIRE_ROOT)
        mContext.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
    }
}
