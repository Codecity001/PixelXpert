package sh.siava.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class ScreenRotation extends XposedModPack {
	private static final int USER_ROTATION_LOCKED = 1;

	private static boolean allScreenRotations = false;

	public ScreenRotation(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		allScreenRotations = Xprefs.getBoolean("allScreenRotations", false);
	}
	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		try {
			ReflectedClass DisplayRotationClass = ReflectedClass.of("com.android.server.wm.DisplayRotation");

			DisplayRotationClass
					.before("rotationForOrientation")
					.run(param -> {
						try {
							if (!allScreenRotations) return;

							final int lastRotation = (int) param.args[1];

							if (getIntField(param.thisObject, "mUserRotationMode") == USER_ROTATION_LOCKED) {
								param.setResult(lastRotation);
								return;
							}

							Object mOrientationListener = getObjectField(param.thisObject, "mOrientationListener");
							int sensorRotation = mOrientationListener != null
									? (int) callMethod(mOrientationListener, "getProposedRotation") // may be -1
									: -1;
							if (sensorRotation < 0) {
								sensorRotation = lastRotation;
							}
							param.setResult(sensorRotation);
						} catch (Throwable ignored) {
						}
					});
		} catch (Exception ignored) {
		}
	}
}
