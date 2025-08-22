package sh.siava.pixelxpert.xposed.modpacks.android;

import static android.content.Context.RECEIVER_EXPORTED;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.PackageManager;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.view.Display;
import android.view.WindowManager;

import java.util.List;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.Constants;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class PhoneWindowManager extends XposedModPack {
	private Object windowMan = null;
	private static boolean broadcastRegistered = false;
	private List<UserHandle> userHandleList;
	private String currentPackage = "";
	private int currentUser = -1;

	public PhoneWindowManager(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			try {
				String action = intent.getAction();
				//noinspection DataFlowIssue
				switch (action) {
					case Constants.ACTION_HOME:
						callMethod(windowMan, "launchHomeFromHotKey", Display.DEFAULT_DISPLAY);
						break;
					case Constants.ACTION_BACK:
						callMethod(windowMan, "backKeyPress");
						break;
					case Constants.ACTION_SLEEP:
						SystemUtils.sleep();
						break;
					case Constants.ACTION_SWITCH_APP_PROFILE:
						switchAppProfile();
						break;
				}
			} catch (Throwable ignored) {
			}
		}
	};

	private void switchAppProfile() {
		if (currentUser < 0 || currentPackage.isEmpty()) return;

		int startIndex = 0;
		for (int i = 0; i < userHandleList.size(); i++) {
			int userID = getIntField(userHandleList.get(i), "mHandle");
			if (userID == currentUser) {
				startIndex = i;
				break;
			}
		}

		boolean looped = false;
		for (int i = startIndex; i < userHandleList.size(); ) {
			i++;
			if (i > userHandleList.size() - 1 && !looped) {
				i = 0;
				looped = true;
			}

			if (isPackageAvailableForUser(currentPackage, userHandleList.get(i))) {
				switchAppToProfile(currentPackage, userHandleList.get(i));
				break;
			}
		}
	}

	private void switchAppToProfile(String packageName, UserHandle userHandle) {
		try {
			callMethod(getObjectField(windowMan, "mActivityTaskManagerInternal"),
					"startActivityAsUser",
					callMethod(mContext, "getIApplicationThread"),
					packageName,
					null,
					PackageManager().getLaunchIntentForPackage(packageName),
					null,
					0,
					null,
					getObjectField(userHandle, "mHandle"));
		} catch (Throwable ignored) {
		}
	}

	private boolean isPackageAvailableForUser(String packageName, UserHandle userHandle) {
		//noinspection unchecked
		return ((List<PackageInfo>) callMethod(mContext.getPackageManager(), "getInstalledPackagesAsUser", PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA), getObjectField(userHandle, "mHandle"))).stream().anyMatch(packageInfo -> packageInfo.packageName.equals(packageName) && packageInfo.applicationInfo.enabled);
	}

	@SuppressLint("WrongConstant")
	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		//noinspection unchecked
		userHandleList = (List<UserHandle>) callMethod(SystemUtils.UserManager(), "getProfiles", true);

//		Collections.addAll(screenshotChords, KEYCODE_POWER, KEYCODE_VOLUME_DOWN);

		if (!broadcastRegistered) {
			broadcastRegistered = true;

			IntentFilter intentFilter = new IntentFilter();
			intentFilter.addAction(Constants.ACTION_HOME);
			intentFilter.addAction(Constants.ACTION_BACK);
			intentFilter.addAction(Constants.ACTION_SLEEP);
			intentFilter.addAction(Constants.ACTION_SWITCH_APP_PROFILE);
			mContext.registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED); //for Android 14, receiver flag is mandatory
		}

		try {
			ReflectedClass PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager");


			PhoneWindowManagerClass
					.before("onDefaultDisplayFocusChangedLw")
					.run(param -> {
						if (param.args[0] == null) return;

						new Thread(() -> {
							if (callMethod(param.args[0], "getBaseType").equals(WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW)) {
								String newPackageName = (String) callMethod(param.args[0], "getOwningPackage");
								int newUserID = (int) getObjectField(callMethod(param.args[0], "getTask"), "mUserId");
								if (!newPackageName.equals(currentPackage) || newUserID != currentUser) {
									currentPackage = newPackageName;
									currentUser = newUserID;

									boolean availableOnOtherUsers = false;
									for (UserHandle userHandle : userHandleList) {
										int thisUserID = (int) getObjectField(userHandle, "mHandle");
										if (thisUserID != currentUser) {
											if (isPackageAvailableForUser(currentPackage, userHandle)) {
												availableOnOtherUsers = true;
												break;
											}
										}
									}
									sendAppProfileSwitchAvailable(availableOnOtherUsers);
								}
							}
						}).start();
					});

			PhoneWindowManagerClass
					.after("enableScreen")
					.run(param -> windowMan = param.thisObject);
		} catch (Throwable ignored) {
		}
	}

	@SuppressLint("MissingPermission")
	private void sendAppProfileSwitchAvailable(boolean isAvailable) {
		new Thread(() -> {
			Intent broadcast = new Intent();
			broadcast.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
			broadcast.setAction(Constants.ACTION_PROFILE_SWITCH_AVAILABLE);
			broadcast.putExtra("available", isAvailable);
			mContext.sendBroadcast(broadcast);
		}).start();
	}
}