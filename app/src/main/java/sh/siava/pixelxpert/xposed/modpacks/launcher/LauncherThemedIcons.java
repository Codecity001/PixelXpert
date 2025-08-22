package sh.siava.pixelxpert.xposed.modpacks.launcher;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findFieldIfExists;
import static de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.GoogleMonochromeIconFactory;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@LauncherModPack
public class LauncherThemedIcons extends XposedModPack {
	private static boolean ForceThemedLauncherIcons = false;
	private int mIconBitmapSize;
	private Object LAS;

	public LauncherThemedIcons(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		ForceThemedLauncherIcons = Xprefs.getBoolean("ForceThemedLauncherIcons", false);

		if (Key.length > 0) {
			if (Key[0].equals("ForceThemedLauncherIcons")) {
				reloadIcons();
			}
		}
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass BaseIconFactoryClass = ReflectedClass.of("com.android.launcher3.icons.BaseIconFactory");
		ReflectedClass LauncherAppStateClass = ReflectedClass.of("com.android.launcher3.LauncherAppState");

		if(findFieldIfExists(BaseIconFactoryClass.getClazz(), "mIconBitmapSize") == null)
		{
			//it's 16qpr2 with built-in generator - no need to patch
			Xprefs.edit().putBoolean("DisableThemedIconsPref", true).apply();
			return;
		}

		Xprefs.edit().putBoolean("DisableThemedIconsPref", false).apply();

		LauncherAppStateClass
				.afterConstruction()
				.run(param -> LAS = param.thisObject);

		BaseIconFactoryClass
				.afterConstruction()
				.run(param -> mIconBitmapSize = getIntField(param.thisObject, "mIconBitmapSize"));

		ReflectedClass.of(AdaptiveIconDrawable.class)
				.after("getMonochrome")
				.run(param -> {
					try {
						if(param.getResult() == null && ForceThemedLauncherIcons)
						{
							for (StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
								if (stackTraceElement.getMethodName().equals("getIcon")) {
									return;
								}
							}

							GoogleMonochromeIconFactory mono = (GoogleMonochromeIconFactory) getAdditionalInstanceField(param.thisObject, "mMonoFactoryPX");
							if(mono == null)
							{
								mono = new GoogleMonochromeIconFactory((AdaptiveIconDrawable) param.thisObject, mIconBitmapSize);
								setAdditionalInstanceField(param.thisObject, "mMonoFactoryPX", mono);
							}
							param.setResult(mono);
						}
					}
					catch (Throwable ignored){}
				});
	}

	private void reloadIcons() {
		Object iconCache = getObjectField(LAS, "iconCache");

		new Handler(Looper.getMainLooper()).post(() -> {
			callMethod(getObjectField(iconCache, "cache"), "clear");
			callMethod(getObjectField(iconCache, "iconDb"), "clear");
			callMethod(getObjectField(LAS, "model"), "forceReload");
		});
	}
}