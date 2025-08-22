package sh.siava.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.os.Binder;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.Constants;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class SystemScreenRecord extends XposedModPack {
	private static boolean InsecureScreenRecord = false;

	public SystemScreenRecord(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		InsecureScreenRecord = Xprefs.getBoolean("InsecureScreenRecord", false);
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		try {
			ReflectedClass DisplayManagerServiceClass = ReflectedClass.of("com.android.server.display.DisplayManagerService");

			DisplayManagerServiceClass
					.before("canProjectSecureVideo")
					.run(param -> {
						try {
							if (InsecureScreenRecord && (boolean) callMethod(param.thisObject, "validatePackageName",
									Binder.getCallingUid(),
									Constants.SYSTEM_UI_PACKAGE))
								param.setResult(true);
						} catch (Throwable ignored) {
						}
					});
		} catch (Throwable ignored) {
		}
	}
}