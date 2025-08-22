package sh.siava.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class HotSpotController extends XposedModPack {
	private static long hotSpotTimeoutMillis = 0;
	private static boolean hotSpotHideSSID = false;
	private static int hotSpotMaxClients = 0;
	private static boolean hotspotDisableApproval = false;

	public HotSpotController(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {

		int clients = Xprefs.getSliderInt("hotSpotMaxClients", 0);

		hotSpotTimeoutMillis = (long) (Xprefs.getSliderFloat("hotSpotTimeoutSecs", 0) * 1000L);
		hotSpotHideSSID = Xprefs.getBoolean("hotSpotHideSSID", false);
		hotspotDisableApproval = Xprefs.getBoolean("hotspotDisableApproval", false);
		hotSpotMaxClients = clients;
	}
	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		try {
			ReflectedClass SoftApConfiguration = ReflectedClass.of("android.net.wifi.SoftApConfiguration");


			SoftApConfiguration
					.afterConstruction()
					.run(param -> {
						setObjectField(param.thisObject, "mHiddenSsid", hotSpotHideSSID);

						if (hotspotDisableApproval) {
							setObjectField(param.thisObject, "mClientControlByUser", false);
						}

						if (hotSpotTimeoutMillis > 0) {
							setObjectField(param.thisObject, "mShutdownTimeoutMillis", hotSpotTimeoutMillis);
						}

						if (hotSpotMaxClients > 0) {
							setObjectField(param.thisObject, "mMaxNumberOfClients", hotSpotMaxClients);
						}
					});
		} catch (Throwable ignored) {
		}
	}
}
