package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.util.AppUtils
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack

/**
 * Lets the module app ask SystemUI - the process that is already alive in the
 * background on every screen - to bring up the KernelSU root prompt on its
 * behalf.
 *
 * The pack registers an exported receiver for
 * [Constants.ACTION_KSU_ACQUIRE_ROOT]; when that broadcast arrives,
 * [AppUtils.runKSURootActivity] runs from inside SystemUI so the request is
 * fired from a system process rather than from the app's own task.
 */
@SystemUIModPack
class KSURootReceiver(context: Context) : XposedModPack(context) {

	/** Nothing this pack reads; declared because [XposedModPack] requires it. */
	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val broadcastReceiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent?) {
				AppUtils.runKSURootActivity(mContext, false)
			}
		}

		val filter = IntentFilter()
		filter.addAction(Constants.ACTION_KSU_ACQUIRE_ROOT)

		mContext.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
	}
}
