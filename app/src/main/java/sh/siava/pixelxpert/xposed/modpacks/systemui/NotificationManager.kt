package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.math.roundToInt

@SystemUIModPack
class NotificationManager(context: Context) : XposedModPack(context) {

    private var headsUpManager: Any? = null
    private var disableOngoingNotifDismiss = false

    companion object {
        private var headupAutoDismissNotificationDecay = -1
    }

    override fun onPreferenceUpdated(vararg key: String) {
        headupAutoDismissNotificationDecay = Xprefs.getSliderInt("HeadupAutoDismissNotificationDecay", -1)
        disableOngoingNotifDismiss = Xprefs.getBoolean("DisableOngoingNotifDismiss", false)
        try {
            applyDurations()
        } catch (_: Throwable) {}
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        val headsUpManagerClass = ReflectedClass.of("com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl")

        headsUpManagerClass
            .afterConstruction()
            .run { runParam ->
                headsUpManager = runParam.thisObject
                applyDurations()
            }

        ReflectedClass.of(StatusBarNotification::class.java)
            .after("isNonDismissable")
            .run { runParam ->
                if (disableOngoingNotifDismiss) {
                    val sbn = runParam.thisObject as? StatusBarNotification
                    val current = runParam.result as? Boolean ?: false
                    runParam.result = current || (sbn?.isOngoing == true)
                }
            }
    }

    private fun applyDurations() {
        val manager = headsUpManager
        if (manager != null && headupAutoDismissNotificationDecay > 0) {
            setObjectField(manager, "mMinimumDisplayTimeDefault", (headupAutoDismissNotificationDecay / 2.5f).roundToInt())
            setObjectField(manager, "mAutoDismissTime", headupAutoDismissNotificationDecay)
        }
    }
}
