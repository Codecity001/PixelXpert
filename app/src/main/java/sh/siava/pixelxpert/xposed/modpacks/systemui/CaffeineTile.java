package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.HapticFeedbackConstants;
import android.view.View;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.service.tileServices.CaffeineTileService;
import sh.siava.pixelxpert.xposed.XPLauncher;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger;

@SystemUIModPack
public class CaffeineTile extends XposedModPack {
	private static final String TAG = "CaffeineTile";

	public static final int TIMEOUT_INFINITE = Integer.MAX_VALUE;

	public static final int[] TIMEOUT_STEPS = new int[]{
			15000,    // 15 seconds
			30000,    // 30 seconds
			60000,    // 1 minute
			120000,   // 2 minutes
			300000,   // 5 minutes
			600000,   // 10 minutes
			1800000,  // 30 minutes
			TIMEOUT_INFINITE // Infinite
	};

	private Object mTile;
	private ContentObserver mTimeoutObserver;

	public CaffeineTile(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		ReflectedClass CustomTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile");
		ReflectedClass QSFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl");

		QSFactoryImplClass
				.after("createTile")
				.run(param -> {
					String arg = (String) param.args[0];
					if (arg != null && arg.contains(CaffeineTileService.class.getSimpleName())) {
						mTile = param.getResult();
						registerTimeoutObserver();
						updateTile();
					}
				});

		CustomTileClass
				.before("handleClick")
				.run(param -> {
					if (param.thisObject == mTile) {
						Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
						handleTileClick(arg);
						param.setResult(null); // Prevent invoking TileService over IPC
					}
				});

		CustomTileClass
				.after("handleUpdateState")
				.run(param -> {
					if (param.thisObject == mTile) {
						Object state = param.args[0];
						setObjectField(state, "secondaryLabel", formatTimeout(getCurrentTimeout()));
						setObjectField(state, "state", Tile.STATE_ACTIVE);
					}
				});

		CustomTileClass
				.before("getLongClickIntent")
				.run(param -> {
					if (param.thisObject == mTile) {
						Intent intent = new Intent("android.settings.SCREEN_TIMEOUT_SETTINGS");
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						if (intent.resolveActivity(mContext.getPackageManager()) == null) {
							intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						}
						param.setResult(intent);
					}
				});
	}

	private void registerTimeoutObserver() {
		if (mTimeoutObserver != null) return;

		mTimeoutObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
			@Override
			public void onChange(boolean selfChange, Uri uri) {
				updateTile();
			}
		};

		try {
			mContext.getContentResolver().registerContentObserver(
					Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT),
					false,
					mTimeoutObserver
			);
		} catch (Throwable t) {
			Logger.log("CaffeineTile: failed to register ContentObserver: " + t);
		}
	}

	private void handleTileClick(Object arg) {
		triggerHapticFeedback(arg);

		int current = getCurrentTimeout();
		int next = getNextTimeout(current);

		try {
			Settings.System.putInt(
					mContext.getContentResolver(),
					Settings.System.SCREEN_OFF_TIMEOUT,
					next
			);
		} catch (Throwable t) {
			Logger.log("CaffeineTile: failed to putInt SCREEN_OFF_TIMEOUT: " + t);
		}

		updateTile(next);
	}

	private void triggerHapticFeedback(Object arg) {
		boolean performed = false;
		try {
			View view = null;
			if (arg instanceof View) {
				view = (View) arg;
			} else if (arg != null) {
				try {
					Object v = callMethod(arg, "asView");
					if (v instanceof View) {
						view = (View) v;
					}
				} catch (Throwable ignored) {
				}
			}

			if (view != null) {
				performed = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
				if (!performed) {
					performed = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
				}
			}
		} catch (Throwable ignored) {
		}

		if (!performed) {
			try {
				SystemUtils.vibrate(VibrationEffect.EFFECT_CLICK, VibrationAttributes.USAGE_TOUCH);
			} catch (Throwable ignored) {
			}
		}
	}

	private void updateTile() {
		updateTile(getCurrentTimeout());
	}

	private void updateTile(int timeoutMs) {
		if (this.mTile == null) return;

		try {
			Tile tile = (Tile) getObjectField(this.mTile, "mTile");
			if (tile == null) return;

			tile.setIcon(Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_caffeine));
			tile.setState(Tile.STATE_ACTIVE);
			String formatted = formatTimeout(timeoutMs);
			tile.setSubtitle(formatted);

			String label = XPLauncher.moduleResources.getString(R.string.caffeine_tile_title);
			tile.setContentDescription(label + ": " + formatted);

			callMethod(this.mTile, "refreshState", new Object[]{null});
		} catch (Throwable t) {
			Logger.log("CaffeineTile: failed to updateTile: " + t);
		}
	}

	public static int getNextTimeout(int current) {
		for (int step : TIMEOUT_STEPS) {
			if (step > current) {
				return step;
			}
		}
		// Wrapped around from infinite back to first step
		return TIMEOUT_STEPS[0];
	}

	public static String formatTimeout(int timeoutMs) {
		if (timeoutMs <= 0 || timeoutMs == TIMEOUT_INFINITE) {
			return "\u221E"; // ∞
		}
		int seconds = timeoutMs / 1000;
		if (seconds < 60) {
			return seconds + "s";
		}
		int minutes = seconds / 60;
		int remainingSeconds = seconds % 60;
		if (remainingSeconds == 0) {
			return minutes + "m";
		}
		return minutes + "m " + remainingSeconds + "s";
	}

	private int getCurrentTimeout() {
		try {
			return Settings.System.getInt(
					mContext.getContentResolver(),
					Settings.System.SCREEN_OFF_TIMEOUT,
					60000
			);
		} catch (Throwable t) {
			return 60000;
		}
	}
}
