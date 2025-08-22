package sh.siava.pixelxpert.xposed.modpacks.ksu;

import static android.content.Context.RECEIVER_EXPORTED;
import static de.robv.android.xposed.XposedHelpers.callMethod;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import com.topjohnwu.superuser.Shell;

import org.objenesis.ObjenesisHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.xposed.Constants;
import sh.siava.pixelxpert.xposed.annotations.KSUModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.KSUNextModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

/**
 * @noinspection RedundantThrows
 */
@KSUModPack
@KSUNextModPack
public class KSUInjector extends XposedModPack {
	private ReflectedClass NativesClass;
	private ReflectedClass ProfileClass;

	public KSUInjector(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		String packageName = lpParam.packageName; // Can be KSU or KSU Next
		ReflectedClass MainActivityClass = ReflectedClass.of(packageName + ".ui.MainActivity");
		NativesClass = ReflectedClass.of(packageName + ".Natives");
		ProfileClass = ReflectedClass.of(packageName + ".Natives$Profile");

		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				grantRootToPX(intent);
			}
		};

		//In case ksu is running already, it won't understand the onCreate intent we send. broadcast it is then
		mContext.registerReceiver(broadcastReceiver, new IntentFilter(Constants.PX_ROOT_EXTRA), RECEIVER_EXPORTED);

		MainActivityClass
				.after("onCreate")
				.run(param -> {
					Intent launchIntent = ((Activity) param.thisObject).getIntent();
					if (launchIntent.hasExtra(Constants.PX_ROOT_EXTRA)) {
						grantRootToPX(launchIntent);
					}
				});
	}

	private void grantRootToPX(Intent launchIntent) {
		new Thread(() -> {
			try {
				Object nativeObject = ObjenesisHelper.newInstance(NativesClass.getClazz());
				int[] rootUIDs = (int[]) callMethod(nativeObject, "getAllowList");

				PackageManager packageManager = mContext.getPackageManager();
				int ownUID = packageManager.getPackageUid(BuildConfig.APPLICATION_ID, PackageManager.GET_ACTIVITIES);

				boolean haveRoot = Arrays.stream(rootUIDs).anyMatch(uid -> uid == ownUID);

				if (!haveRoot) {
					Object ownRootProfile = ProfileClass.getClazz().getConstructor(String.class, int.class, boolean.class, boolean.class, String.class, int.class, int.class, List.class, List.class, String.class, int.class, boolean.class, boolean.class, String.class)
							.newInstance(BuildConfig.APPLICATION_ID, ownUID, true, true, null, 0, 0, new ArrayList<>(), new ArrayList<>(), "u:r:su:s0", 0, true, true, "");

					callMethod(nativeObject, "setAppProfile", ownRootProfile);

					restartPX(launchIntent.hasExtra("launchApp"));
				}
				Thread.sleep(2000);
				SystemUtils.killSelf();
			} catch (Throwable ignored) {
			}
		}).start();
	}

	private void restartPX(boolean launch) throws InterruptedException {
		Shell.cmd("killall " + BuildConfig.APPLICATION_ID).exec();

		if (launch) {
			Thread.sleep(1000);
			//noinspection DataFlowIssue
			mContext.startActivity(
					mContext
							.getPackageManager()
							.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
							.putExtra("FromKSU", 1));
		}
	}
}