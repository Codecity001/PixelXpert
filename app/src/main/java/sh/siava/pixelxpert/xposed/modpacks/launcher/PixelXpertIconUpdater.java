package sh.siava.pixelxpert.xposed.modpacks.launcher;

import android.content.Context;
import android.os.UserHandle;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@LauncherModPack
public class PixelXpertIconUpdater extends XposedModPack {
	private Object LauncherModel;

	public PixelXpertIconUpdater(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass LauncherModelClass = ReflectedClass.of("com.android.launcher3.LauncherModel");
		ReflectedClass BaseActivityClass = ReflectedClass.of("com.android.launcher3.BaseActivity");

		BaseActivityClass
				.after("onResume")
				.run(param -> {
					try {
						XposedHelpers.callMethod(LauncherModel, "onAppIconChanged", BuildConfig.APPLICATION_ID, UserHandle.getUserHandleForUid(0));
					}catch (Throwable ignored){}
				});

		LauncherModelClass
				.afterConstruction()
				.run(param -> LauncherModel = param.thisObject);
	}
}