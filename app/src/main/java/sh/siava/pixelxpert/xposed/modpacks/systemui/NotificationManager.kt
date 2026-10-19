package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Governs how long heads-up notifications stay up and whether ongoing
 * notifications can be dismissed.
 *
 * The auto-dismiss decay slider is written straight into the live
 * `HeadsUpManagerImpl` instance (`mMinimumDisplayTimeDefault` and
 * `mAutoDismissTime`) both when the instance is constructed and whenever the
 * preference changes, so the value survives recreation. The second switch
 * rewrites `StatusBarNotification.isNonDismissable` to also be true for
 * ongoing notifications, which stops them being swiped away.
 */
@SystemUIModPack
class NotificationManager(context: Context) : XposedModPack(context) {

	companion object {
		private var HeadupAutoDismissNotificationDecay = -1
	}

	private var HeadsUpManager: Any? = null
	private var DisableOngoingNotifDismiss = false

	override fun onPreferenceUpdated(vararg key: String?) {
		HeadupAutoDismissNotificationDecay = Xprefs.getSliderInt("HeadupAutoDismissNotificationDecay", -1)
		DisableOngoingNotifDismiss = Xprefs.getBoolean("DisableOngoingNotifDismiss", false)
		try {
			applyDurations()
		} catch (ignored: Throwable) {
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val HeadsUpManagerClass = ReflectedClass.of("com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl")

		HeadsUpManagerClass
			.afterConstruction()
			.run { param ->
				HeadsUpManager = param.thisObject
				applyDurations()
			}

		ReflectedClass.of(StatusBarNotification::class.java)
			.after("isNonDismissable")
			.run { param ->
				if (DisableOngoingNotifDismiss) {
					param.setResult(param.getResult<Boolean>() || (param.thisObject as StatusBarNotification).isOngoing)
				}
			}
	}

	private fun applyDurations() {
		if (HeadsUpManager != null && HeadupAutoDismissNotificationDecay > 0) {
			setObjectField(HeadsUpManager, "mMinimumDisplayTimeDefault", Math.round(HeadupAutoDismissNotificationDecay / 2.5f))
			setObjectField(HeadsUpManager, "mAutoDismissTime", HeadupAutoDismissNotificationDecay)
		}
	}
}
