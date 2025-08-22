package sh.siava.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.DisplayCutout;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;


//We are playing in system framework. should be extra cautious..... many try-catchs, still not enough!
@SuppressWarnings("RedundantThrows")
@FrameworkModPack
@SystemUIModPack
public class StatusbarSize extends XposedModPack {
	private static final int BOUNDS_POSITION_TOP = 1;

	static int sizeFactor = 100; // % of normal
	static boolean noCutoutEnabled = true;
	int currentHeight = 0;
	boolean edited = false; //if we touched it once during this instance, we'll have to continue setting it even if it's the original value
	private boolean mForceApplyHeight = false;

	public StatusbarSize(Context context) {
		super(context);
	}

	@SuppressLint({"DiscouragedApi", "InternalInsetResource"})
	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		noCutoutEnabled = Xprefs.getBoolean("noCutoutEnabled", false);

		mForceApplyHeight = Xprefs.getBoolean("allScreenRotations", false) //Particularly used for rotation Status bar
				|| noCutoutEnabled
				|| Xprefs.getBoolean("systemIconsMultiRow", false)
				|| Xprefs.getBoolean("notificationAreaMultiRow", false);

		sizeFactor = Xprefs.getSliderInt("statusbarHeightFactor", 100);
		if (sizeFactor != 100 || edited || mForceApplyHeight) {
			Configuration conf = new Configuration();
			conf.updateFrom(mContext.getResources().getConfiguration());

			conf.orientation = Configuration.ORIENTATION_PORTRAIT;
			Context portraitContext = mContext.createConfigurationContext(conf);

			currentHeight = Math.round(
					portraitContext.getResources().getDimensionPixelSize(
							portraitContext.getResources().getIdentifier(
									"status_bar_height",
									"dimen",
									"android")
					)
							* sizeFactor
							/ 100f);
		}
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		try {
			try {
				ReflectedClass WmDisplayCutoutClass = ReflectedClass.of("com.android.server.wm.utils.WmDisplayCutout");
				ReflectedClass DisplayCutoutClass = ReflectedClass.of("android.view.DisplayCutout");

				Object NO_CUTOUT = getStaticObjectField(DisplayCutoutClass.getClazz(), "NO_CUTOUT");

				WmDisplayCutoutClass
						.before("getDisplayCutout")
						.run(param -> {
							if (noCutoutEnabled) {
								param.setResult(NO_CUTOUT);
							}
						});

				WmDisplayCutoutClass
						.after("getDisplayCutout")
						.run(param -> {
							if (sizeFactor >= 100 && !edited) return;

							DisplayCutout displayCutout = (DisplayCutout) param.getResult();

							Rect boundTop = ((Rect[]) getObjectField(
									getObjectField(
											displayCutout,
											"mBounds"),
									"mRects")
							)[BOUNDS_POSITION_TOP];
							boundTop.bottom = Math.min(boundTop.bottom, currentHeight);

							Rect mSafeInsets = (Rect) getObjectField(
									displayCutout,
									"mSafeInsets");
							mSafeInsets.top = Math.min(mSafeInsets.top, currentHeight);
						});
			} catch (Throwable ignored) {
			}

			ReflectedClass.ReflectionConsumer resizedResultConsumer = param -> {
				try {
					if (sizeFactor == 100 && !edited && !mForceApplyHeight) return;
					edited = true;
					param.setResult(currentHeight);
				} catch (Throwable ignored) {
				}
			};

			try {
				ReflectedClass SystemBarUtilsClass = ReflectedClass.of("com.android.internal.policy.SystemBarUtils");

				SystemBarUtilsClass.before("getStatusBarHeight").run(resizedResultConsumer);
				SystemBarUtilsClass.before("getStatusBarHeightForRotation").run(resizedResultConsumer);
			} catch (Throwable ignored) {
			}
		} catch (Throwable ignored) {
		}
	}
}
