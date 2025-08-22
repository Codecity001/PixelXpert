package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.service.notification.StatusBarNotification;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class NotificationManager extends XposedModPack {
	private Object HeadsUpManager = null;

	private static int HeadupAutoDismissNotificationDecay = -1;
	private boolean DisableOngoingNotifDismiss = false;

	public NotificationManager(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		HeadupAutoDismissNotificationDecay = Xprefs.getSliderInt( "HeadupAutoDismissNotificationDecay", -1);
		DisableOngoingNotifDismiss = Xprefs.getBoolean("DisableOngoingNotifDismiss", false);
		try {
			applyDurations();
		} catch (Throwable ignored) {}
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass HeadsUpManagerClass = ReflectedClass.of("com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl");

		HeadsUpManagerClass
				.afterConstruction()
				.run(param -> {
					HeadsUpManager = param.thisObject;
					applyDurations();
				});

		ReflectedClass.of(StatusBarNotification.class)
				.after("isNonDismissable")
				.run(param -> {
					if(DisableOngoingNotifDismiss) {
						param.setResult((boolean) param.getResult() || ((StatusBarNotification) param.thisObject).isOngoing());
					}
				});
	}

	private void applyDurations() {
		if(HeadsUpManager != null && HeadupAutoDismissNotificationDecay > 0)
		{
			setObjectField(HeadsUpManager, "mMinimumDisplayTimeDefault", Math.round(HeadupAutoDismissNotificationDecay / 2.5f));
			setObjectField(HeadsUpManager, "mAutoDismissTime", HeadupAutoDismissNotificationDecay);
		}
	}
}