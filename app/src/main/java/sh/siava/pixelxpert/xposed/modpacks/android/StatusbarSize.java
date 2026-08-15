package sh.siava.pixelxpert.xposed.modpacks.android;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static de.robv.android.xposed.XposedHelpers.setIntField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.annotations.LauncherModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger;


//We are playing in system framework. should be extra cautious..... many try-catchs, still not enough!
@SuppressWarnings("RedundantThrows")
@FrameworkModPack
@LauncherModPack
@SystemUIModPack
public class StatusbarSize extends XposedModPack {
	private static final int BOUNDS_POSITION_TOP = 1;

	static int sizeFactor = 100; // % of normal
	static boolean noCutoutEnabled = false;
	private static boolean sNoCutoutHookInstalled = false;
	private static boolean sNoCutoutHookSuppressed = false;
	boolean edited = false; //if we touched it once during this instance, we'll have to continue setting it even if it's the original value
	private boolean mForceApplyHeight = false;

	public StatusbarSize(Context context) {
		super(context);
	}

	@SuppressLint({"DiscouragedApi", "InternalInsetResource"})
	@Override
	public void onPreferenceUpdated(String... Key) {
		if (Xprefs == null) return;

		noCutoutEnabled = !sNoCutoutHookSuppressed
				&& Xprefs.getBoolean("noCutoutEnabled", false);

		mForceApplyHeight = Xprefs.getBoolean("allScreenRotations", false) //Particularly used for rotation Status bar
				|| Xprefs.getBoolean("systemIconsMultiRow", false)
				|| Xprefs.getBoolean("notificationAreaMultiRow", false);

		sizeFactor = Xprefs.getSliderInt("statusbarHeightFactor", 100);
	}

	@SuppressLint({"DiscouragedApi", "InternalInsetResource"})
	private static int getBaseStatusBarHeight(Context context, boolean landscape) {
		Configuration configuration = new Configuration(context.getResources().getConfiguration());
		configuration.orientation = landscape
				? Configuration.ORIENTATION_LANDSCAPE
				: Configuration.ORIENTATION_PORTRAIT;
		Resources resources = context.createConfigurationContext(configuration).getResources();
		int id = resources.getIdentifier(
				landscape ? "status_bar_height_landscape" : "status_bar_height_portrait",
				"dimen",
				"android");
		if (id == 0) {
			id = resources.getIdentifier("status_bar_height", "dimen", "android");
		}
		return id == 0 ? 0 : resources.getDimensionPixelSize(id);
	}

	public static boolean isNoCutoutLayoutActive(Context context) {
		if (!noCutoutEnabled || sNoCutoutHookSuppressed || context == null) return false;

		try {
			return context.getDisplay() != null && context.getDisplay().getCutout() == null;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private boolean shouldApplyHeight(Context context) {
		return sizeFactor != 100 || edited || mForceApplyHeight || isNoCutoutLayoutActive(context);
	}

	private static boolean isLandscape(Context context) {
		return context.getResources().getConfiguration().orientation
				== Configuration.ORIENTATION_LANDSCAPE;
	}

	private static boolean isLandscapeForRotation(Context context, int targetRotation) {
		try {
			int currentRotation = context.getDisplay().getRotation();
			boolean changesOrientation = ((currentRotation - targetRotation + 4) % 2) != 0;
			return isLandscape(context) != changesOrientation;
		} catch (Throwable ignored) {
			return targetRotation == Surface.ROTATION_90 || targetRotation == Surface.ROTATION_270;
		}
	}

	private int getStatusBarHeight(Context context, boolean landscape) {
		int baseHeight = getBaseStatusBarHeight(context, landscape);
		return Math.round(baseHeight * sizeFactor / 100f);
	}

	private Context getControllerContext(Object statusBarWindowController) {
		try {
			Object context = getObjectField(statusBarWindowController, "mContext");
			if (context instanceof Context) return (Context) context;
		} catch (Throwable ignored) {
		}
		return mContext;
	}

	private void applyCachedStatusBarHeight(Object statusBarWindowController) {
		Context context = getControllerContext(statusBarWindowController);
		boolean landscape = isLandscape(context);
		if (!shouldApplyHeight(context)) return;

		int height = getStatusBarHeight(context, landscape);
		if (height > 0) {
			setIntField(statusBarWindowController, "mBarHeight", height);
		}
	}

	private void applyStatusBarLayoutParams(Object layoutParams, Context context, Integer rotation) {
		if (!shouldApplyHeight(context) || !(layoutParams instanceof WindowManager.LayoutParams lp)) return;

		if (lp.type == WindowManager.LayoutParams.TYPE_STATUS_BAR) {
			boolean landscape = rotation == null ? isLandscape(context) : isLandscapeForRotation(context, rotation);
			if (shouldApplyHeight(context)) {
				int height = getStatusBarHeight(context, landscape);
				applyStatusBarLayoutParams(lp, height);
			}
			applyStatusBarLayoutParams(getLayoutParamsArray(lp, "paramsForRotation"), context);
		}
	}

	private void applyStatusBarLayoutParams(WindowManager.LayoutParams[] paramsForRotation, Context context) {
		if (paramsForRotation == null) return;

		for (int rotation = 0; rotation < paramsForRotation.length; rotation++) {
			WindowManager.LayoutParams rotationParams = paramsForRotation[rotation];
			boolean landscape = isLandscapeForRotation(context, rotation);
			if (rotationParams == null || !shouldApplyHeight(context)) continue;

			int height = getStatusBarHeight(context, landscape);
			applyStatusBarLayoutParams(rotationParams, height);
		}
	}

	private void applyStatusBarLayoutParams(WindowManager.LayoutParams layoutParams, int height) {
		if (height <= 0) return;

		if (layoutParams.height != WindowManager.LayoutParams.MATCH_PARENT) {
			layoutParams.height = height;
		}
		applyInsetsFrameProviders(getObjectArray(layoutParams, "providedInsets"), height);
	}

	private WindowManager.LayoutParams[] getLayoutParamsArray(Object target, String fieldName) {
		try {
			Object value = getObjectField(target, fieldName);
			return value instanceof WindowManager.LayoutParams[] ? (WindowManager.LayoutParams[]) value : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private Object[] getObjectArray(Object target, String fieldName) {
		try {
			Object value = getObjectField(target, fieldName);
			return value instanceof Object[] ? (Object[]) value : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private void applyInsetsFrameProviders(Object[] providers, int height) {
		if (providers == null) return;

		Insets insets = Insets.of(0, height, 0, 0);
		for (Object provider : providers) {
			if (provider == null) continue;

			try {
				callMethod(provider, "setInsetsSize", insets);
			} catch (Throwable ignored) {
			}
		}
	}

	private void syncStatusBarLayoutParams(Object statusBarWindowController, boolean updateWindow) {
		try {
			Context context = getControllerContext(statusBarWindowController);
			if (!shouldApplyHeight(context)) return;

			applyCachedStatusBarHeight(statusBarWindowController);
			applyStatusBarLayoutParams(getObjectField(statusBarWindowController, "mLpChanged"), context, null);

			Object layoutParams = getObjectField(statusBarWindowController, "mLp");
			applyStatusBarLayoutParams(layoutParams, context, null);

			if (updateWindow && layoutParams instanceof WindowManager.LayoutParams) {
				Object windowManager = getObjectField(statusBarWindowController, "mWindowManager");
				Object statusBarWindowView = getObjectField(statusBarWindowController, "mStatusBarWindowView");
				if (windowManager != null && statusBarWindowView instanceof View) {
					callMethod(windowManager, "updateViewLayout", statusBarWindowView, layoutParams);
				}
			}
		} catch (Throwable ignored) {
		}
	}

	public static void installEarlyNoCutoutHook(ClassLoader classLoader, boolean enabled) {
		if (sNoCutoutHookSuppressed) return;

		noCutoutEnabled = enabled;
		if (sNoCutoutHookInstalled) return;

		try {
			ReflectedClass WmDisplayCutoutClass = ReflectedClass.of("com.android.server.wm.utils.WmDisplayCutout", classLoader);
			ReflectedClass DisplayCutoutClass = ReflectedClass.of("android.view.DisplayCutout", classLoader);

			Object NO_CUTOUT = getStaticObjectField(DisplayCutoutClass.getClazz(), "NO_CUTOUT");

			WmDisplayCutoutClass
					.before("getDisplayCutout")
					.run(param -> {
						if (noCutoutEnabled) {
							param.setResult(NO_CUTOUT);
						}
					});

			sNoCutoutHookInstalled = true;
		} catch (Throwable t) {
			Logger.log("StatusbarSize: failed to install early display cutout hook", t);
		}
	}

	public static void suppressNoCutoutHookForBoot() {
		sNoCutoutHookSuppressed = true;
		noCutoutEnabled = false;
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		try {
			try {
				installEarlyNoCutoutHook(PRParam.getClassLoader(), noCutoutEnabled);

				ReflectedClass WmDisplayCutoutClass = ReflectedClass.of("com.android.server.wm.utils.WmDisplayCutout");
				WmDisplayCutoutClass
						.after("getDisplayCutout")
						.run(param -> {
							if (noCutoutEnabled) return;
							if (sizeFactor >= 100 && !edited) return;
							boolean landscape = isLandscape(mContext);
							if (!shouldApplyHeight(mContext)) return;

							DisplayCutout displayCutout = (DisplayCutout) param.getResult();
							if (displayCutout == null) return;
							int targetHeight = getStatusBarHeight(mContext, landscape);
							if (targetHeight <= 0) return;

							Rect boundTop = ((Rect[]) getObjectField(
									getObjectField(
											displayCutout,
											"mBounds"),
									"mRects")
							)[BOUNDS_POSITION_TOP];
							boundTop.bottom = Math.min(boundTop.bottom, targetHeight);

							Rect mSafeInsets = (Rect) getObjectField(
									displayCutout,
									"mSafeInsets");
							mSafeInsets.top = Math.min(mSafeInsets.top, targetHeight);
						});
			} catch (Throwable ignored) {
			}

			ReflectedClass.ReflectionConsumer currentHeightConsumer = param -> {
				try {
					Context context = mContext;
					if (param.args.length > 0 && param.args[0] instanceof Context) {
						context = (Context) param.args[0];
					} else if (param.thisObject != null) {
						context = getControllerContext(param.thisObject);
					}
					boolean landscape = isLandscape(context);
					if (!shouldApplyHeight(context)) return;
					int height = getStatusBarHeight(context, landscape);
					if (height <= 0) return;
					edited = true;
					param.setResult(height);
				} catch (Throwable ignored) {
				}
			};
			ReflectedClass.ReflectionConsumer rotationHeightConsumer = param -> {
				try {
					if (param.args.length < 2
							|| !(param.args[0] instanceof Context)
							|| !(param.args[1] instanceof Integer)) return;
					Context context = (Context) param.args[0];
					boolean landscape = isLandscapeForRotation(context, (Integer) param.args[1]);
					if (!shouldApplyHeight(context)) return;
					int height = getStatusBarHeight(context, landscape);
					if (height <= 0) return;
					edited = true;
					param.setResult(height);
				} catch (Throwable ignored) {
				}
			};

			try {
				ReflectedClass SystemBarUtilsClass = ReflectedClass.of("com.android.internal.policy.SystemBarUtils");

				SystemBarUtilsClass.before("getStatusBarHeight").run(currentHeightConsumer);
				SystemBarUtilsClass.before("getStatusBarHeightForRotation").run(rotationHeightConsumer);
			} catch (Throwable ignored) {
			}

			try {
				ReflectedClass StatusBarWindowControllerImplClass =
						ReflectedClass.ofIfPossible("com.android.systemui.statusbar.window.StatusBarWindowControllerImpl");
				ReflectedClass.ReflectionConsumer cachedStatusBarHeightConsumer = param -> {
					try {
						applyCachedStatusBarHeight(param.thisObject);
					} catch (Throwable ignored) {
					}
				};

				StatusBarWindowControllerImplClass.afterConstruction().run(cachedStatusBarHeightConsumer);
				StatusBarWindowControllerImplClass.before("attach").run(cachedStatusBarHeightConsumer);
				StatusBarWindowControllerImplClass.before("applyHeight").run(cachedStatusBarHeightConsumer);
				StatusBarWindowControllerImplClass.before("refreshStatusBarHeight").run(cachedStatusBarHeightConsumer);
				StatusBarWindowControllerImplClass.before("getStatusBarHeight").run(currentHeightConsumer);
				StatusBarWindowControllerImplClass.after("attach").run(param -> syncStatusBarLayoutParams(param.thisObject, true));
				StatusBarWindowControllerImplClass.after("apply").run(param -> syncStatusBarLayoutParams(param.thisObject, true));
				StatusBarWindowControllerImplClass.after("applyHeight").run(param -> syncStatusBarLayoutParams(param.thisObject, false));
				StatusBarWindowControllerImplClass.after("refreshStatusBarHeight").run(param -> syncStatusBarLayoutParams(param.thisObject, true));
				ReflectedClass.ReflectionConsumer layoutParamsConsumer = param -> {
					Context context = getControllerContext(param.thisObject);
					Integer rotation = param.args.length > 0 && param.args[0] instanceof Integer
							? (Integer) param.args[0]
							: null;
					applyStatusBarLayoutParams(param.getResult(), context, rotation);
				};
				StatusBarWindowControllerImplClass.after("getBarLayoutParams").run(layoutParamsConsumer);
				StatusBarWindowControllerImplClass.after("getBarLayoutParamsForRotation").run(layoutParamsConsumer);
			} catch (Throwable ignored) {
			}
		} catch (Throwable ignored) {
		}
	}
}
