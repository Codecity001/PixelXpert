package sh.siava.pixelxpert.util

import android.content.Context
import android.content.Intent
import sh.siava.pixelxpert.Constants

object AppUtils {

	/**
	 * Reaches the KernelSU manager's root activity: first a broadcast, in
	 * case the manager is already running and can show the prompt itself,
	 * then a launch intent for the case where it is not.
	 */
	@JvmStatic
	fun runKSURootActivity(context: Context, launchApp: Boolean) {
		try {
			val broadcastIntent = Intent(Constants.PX_ROOT_EXTRA)
			if (launchApp) {
				broadcastIntent.putExtra("launchApp", 1)
			}

			broadcastIntent.setPackage(Constants.KSU_PACKAGE)
			context.sendBroadcast(broadcastIntent)

			val launchIntent = context.packageManager.getLaunchIntentForPackage(Constants.KSU_PACKAGE)
			//noinspection DataFlowIssue
			launchIntent!!.putExtra(Constants.PX_ROOT_EXTRA, 1)
			launchIntent.flags = Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
			if (launchApp) {
				launchIntent.putExtra("launchApp", 1)
			}

			context.startActivity(launchIntent)
		} catch (ignored: Throwable) {
		}
	}
}
