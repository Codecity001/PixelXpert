package sh.siava.pixelxpert.modpacks.launcher;

import android.content.Context;
import android.os.UserHandle;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.modpacks.Constants;
import sh.siava.pixelxpert.modpacks.XposedModPack;
import sh.siava.pixelxpert.modpacks.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
public class PixelXpertIconUpdater extends XposedModPack {
	private static final String listenPackage = Constants.LAUNCHER_PACKAGE;
	private Object LauncherModel;

	public PixelXpertIconUpdater(Context context) {
		super(context);
	}

	@Override
	public void updatePrefs(String... Key) {}

	@Override
	public boolean listensTo(String packageName) {
		return listenPackage.equals(packageName);
	}

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass LauncherModelClass = ReflectedClass.of("com.android.launcher3.LauncherModel");
		ReflectedClass BaseDraggingActivityClass = ReflectedClass.of("com.android.launcher3.BaseDraggingActivity");

		BaseDraggingActivityClass
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