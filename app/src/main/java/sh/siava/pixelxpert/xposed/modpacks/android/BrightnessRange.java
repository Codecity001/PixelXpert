package sh.siava.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import java.util.List;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
@FrameworkModPack
public class BrightnessRange extends XposedModPack {
	private static float minimumBrightnessLevel = 0f;
	private static float maximumBrightnessLevel = 1f;

	public BrightnessRange(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		try {
			List<Float> BrightnessRange = Xprefs.getSliderValues("BrightnessRange", 100f);
			if (BrightnessRange.size() == 2) {
				minimumBrightnessLevel = BrightnessRange.get(0) / 100;
				maximumBrightnessLevel = BrightnessRange.get(1) / 100;
			}
		} catch (Throwable ignored) {
		}
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		try { //framework
			ReflectedClass DisplayPowerControllerClass = ReflectedClass.of("com.android.server.display.DisplayPowerController");

			DisplayPowerControllerClass
					.before("clampScreenBrightness")
					.run(param -> {
						if (minimumBrightnessLevel == 0f && maximumBrightnessLevel == 1f) return;

						param.args[0] = Math.min(
								Math.max(
										(float) param.args[0],
										minimumBrightnessLevel),
								maximumBrightnessLevel);
					});

		} catch (Throwable ignored) {
		}

		try { //SystemUI
			ReflectedClass BrightnessInfoClass = ReflectedClass.of("android.hardware.display.BrightnessInfo");

			BrightnessInfoClass
					.afterConstruction()
					.run(param -> {
						if (minimumBrightnessLevel > 0f) {
							setObjectField(param.thisObject, "brightnessMinimum", minimumBrightnessLevel);
						}
						if (maximumBrightnessLevel < 1f) {
							setObjectField(param.thisObject, "brightnessMaximum", maximumBrightnessLevel);
						}
					});
		} catch (Throwable ignored) {
		}
	}
}