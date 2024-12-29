package sh.siava.pixelxpert.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.modpacks.XPrefs.Xprefs;

import android.content.Context;
import android.service.notification.StatusBarNotification;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.modpacks.Constants;
import sh.siava.pixelxpert.modpacks.XPLauncher;
import sh.siava.pixelxpert.modpacks.XposedModPack;
import sh.siava.pixelxpert.modpacks.utils.toolkit.ReflectedClass;
import sh.siava.pixelxpert.modpacks.utils.toolkit.ReflectedClass.ReflectionConsumer;

@SuppressWarnings("RedundantThrows")
public class NotificationManager extends XposedModPack {
	private static final String listenPackage = Constants.SYSTEM_UI_PACKAGE;

	private Object HeadsUpManager = null;

	private static int HeadupAutoDismissNotificationDecay = -1;
	private boolean DisableOngoingNotifDismiss = false;

	public NotificationManager(Context context) {
		super(context);
	}

	@Override
	public void updatePrefs(String... Key) {
		HeadupAutoDismissNotificationDecay = Xprefs.getSliderInt( "HeadupAutoDismissNotificationDecay", -1);
		DisableOngoingNotifDismiss = Xprefs.getBoolean("DisableOngoingNotifDismiss", false);
		try {
			applyDurations();
		} catch (Throwable ignored) {}
	}

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectionConsumer headsupFinder = param -> {
			HeadsUpManager = param.thisObject;

			applyDurations();
		};

		ReflectedClass HeadsUpManagerClass = ReflectedClass.of("com.android.systemui.statusbar.policy.HeadsUpManager");
		HeadsUpManagerClass.afterConstruction().run(headsupFinder); //interface in 14QPR2, class in older

		try //A14 QPR2
		{
			ReflectedClass BaseHeadsUpManagerClass = ReflectedClass.of("com.android.systemui.statusbar.policy.BaseHeadsUpManager");
			BaseHeadsUpManagerClass.afterConstruction().run(headsupFinder);
		}
		catch (Throwable ignored){}

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
			setObjectField(HeadsUpManager, "mMinimumDisplayTime", Math.round(HeadupAutoDismissNotificationDecay/2.5f));

			try //A14 QPR2B3
			{
				setObjectField(HeadsUpManager, "mAutoDismissTime", HeadupAutoDismissNotificationDecay);
			}
			catch (Throwable ignored) //Older
			{
				setObjectField(HeadsUpManager, "mAutoDismissNotificationDecay", HeadupAutoDismissNotificationDecay);
			}
		}
	}

	@Override
	public boolean listensTo(String packageName) {
		return listenPackage.equals(packageName) && !XPLauncher.isChildProcess;
	}
}