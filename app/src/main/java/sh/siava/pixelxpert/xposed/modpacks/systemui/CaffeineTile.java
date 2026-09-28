package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.CountDownTimer;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
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

	private Object mTile;

	private PowerManager.WakeLock mWakeLock;
	private int mSecondsRemaining;
	private int mDuration = -1; // -1 means off/uninitialized. Index into DURATIONS
	private static final int[] DURATIONS = new int[]{
			5 * 60,   // 5 min
			10 * 60,  // 10 min
			30 * 60,  // 30 min
			-1,       // infinity
	};
	private static final int INFINITE_DURATION_INDEX = DURATIONS.length - 1;
	private CountDownTimer mCountdownTimer = null;
	public long mLastClickTime = -1;
	private BroadcastReceiver mScreenOffReceiver = null;

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
						Object result = param.getResult();
						if (result != null) {
							mTile = result;
							initCaffeine();
							updateTile();
						}
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

		ReflectedClass QSTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl");
		QSTileImplClass
				.before("handleLongClick")
				.run(param -> {
					if (param.thisObject == mTile) {
						Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
						handleTileLongClick(arg);
						param.setResult(null);
					}
				});

		CustomTileClass
				.after("handleUpdateState")
				.run(param -> {
					if (param.thisObject == mTile) {
						Object state = param.args[0];

						boolean isHeld = mWakeLock != null && mWakeLock.isHeld();
						try {
							setObjectField(state, "value", isHeld);
						} catch (Throwable ignored) {
						}
						setObjectField(state, "state", isHeld ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);

						if (isHeld) {
							setObjectField(state, "secondaryLabel", formatValueWithRemainingTime());
						} else {
							setObjectField(state, "secondaryLabel", null);
						}
					}
				});

		CustomTileClass
				.before("getLongClickIntent")
				.run(param -> {
					if (param.thisObject == mTile) {
						// Return a safe dummy intent to prevent crashes if accessibility invokes it
						Intent intent = new Intent("android.settings.SCREEN_TIMEOUT_SETTINGS");
						intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						if (intent.resolveActivity(mContext.getPackageManager()) == null) {
							intent = new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						}
						param.setResult(intent);
					}
				});

		CustomTileClass
				.before("handleDestroy")
				.run(param -> {
					if (param.thisObject == mTile) {
						destroyCaffeine();
					}
				});
	}

	private void initCaffeine() {
		if (mWakeLock == null) {
			PowerManager pm = mContext.getSystemService(PowerManager.class);
			if (pm != null) {
				mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "PixelXpert:CaffeineTile");
			}
		}
		if (mScreenOffReceiver == null) {
			mScreenOffReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
						stopCountDown();
						if (mWakeLock != null && mWakeLock.isHeld()) {
							mWakeLock.release();
						}
						mDuration = -1;
						updateTile();
					}
				}
			};
			IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
			mContext.registerReceiver(mScreenOffReceiver, filter, null, null);
		}
	}

	private void destroyCaffeine() {
		stopCountDown();
		if (mScreenOffReceiver != null) {
			try {
				mContext.unregisterReceiver(mScreenOffReceiver);
			} catch (Exception ignored) {
			}
			mScreenOffReceiver = null;
		}
		if (mWakeLock != null && mWakeLock.isHeld()) {
			mWakeLock.release();
		}
	}

	private void handleTileClick(Object arg) {
		triggerHapticFeedback(arg);

		if (mWakeLock == null) return;

		if (mWakeLock.isHeld() && (mLastClickTime != -1) &&
				(SystemClock.elapsedRealtime() - mLastClickTime < 5000)) {
			// cycle duration
			mDuration++;
			if (mDuration >= DURATIONS.length) {
				// all durations cycled, turn it off
				mDuration = -1;
				stopCountDown();
				if (mWakeLock.isHeld()) {
					mWakeLock.release();
				}
			} else {
				// change duration
				startCountDown(DURATIONS[mDuration]);
				if (!mWakeLock.isHeld()) {
					mWakeLock.acquire();
				}
			}
		} else {
			// toggle
			if (mWakeLock.isHeld()) {
				mWakeLock.release();
				stopCountDown();
			} else {
				mWakeLock.acquire();
				mDuration = 0;
				startCountDown(DURATIONS[mDuration]);
			}
		}
		mLastClickTime = SystemClock.elapsedRealtime();
		updateTile();
	}

	private void handleTileLongClick(Object arg) {
		triggerHapticFeedback(arg);

		if (mWakeLock == null) return;

		if (mWakeLock.isHeld()) {
			if (mDuration == INFINITE_DURATION_INDEX) {
				return;
			}
		} else {
			mWakeLock.acquire();
		}
		mDuration = INFINITE_DURATION_INDEX;
		startCountDown(DURATIONS[INFINITE_DURATION_INDEX]);
		updateTile();
	}

	private void startCountDown(long duration) {
		stopCountDown();
		mSecondsRemaining = (int) duration;
		if (duration == -1) {
			// infinity timing, no need to start timer
			return;
		}
		mCountdownTimer = new CountDownTimer(duration * 1000, 1000) {
			@Override
			public void onTick(long millisUntilFinished) {
				mSecondsRemaining = (int) (millisUntilFinished / 1000);
				updateTile();
			}

			@Override
			public void onFinish() {
				if (mWakeLock != null && mWakeLock.isHeld()) {
					mWakeLock.release();
				}
				mDuration = -1;
				updateTile();
			}
		}.start();
	}

	private void stopCountDown() {
		if (mCountdownTimer != null) {
			mCountdownTimer.cancel();
			mCountdownTimer = null;
		}
	}

	private String formatValueWithRemainingTime() {
		if (mSecondsRemaining == -1) {
			return "\u221E"; // infinity
		}
		return String.format("%02d:%02d", mSecondsRemaining / 60 % 60, mSecondsRemaining % 60);
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
		if (this.mTile == null) return;

		try {
			Tile tile = (Tile) getObjectField(this.mTile, "mTile");
			if (tile == null) return;

			boolean isHeld = mWakeLock != null && mWakeLock.isHeld();
			tile.setIcon(Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_caffeine));
			tile.setState(isHeld ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);

			String formatted = formatValueWithRemainingTime();
			if (isHeld) {
				tile.setSubtitle(formatted);
			} else {
				tile.setSubtitle(null);
			}

			String label = XPLauncher.moduleResources.getString(R.string.caffeine_tile_title);
			if (isHeld) {
				tile.setContentDescription(label + ": " + formatted);
			} else {
				tile.setContentDescription(label + ": Off");
			}

			callMethod(this.mTile, "refreshState", new Object[]{null});
		} catch (Throwable t) {
			Logger.log("CaffeineTile: failed to updateTile: " + t);
		}
	}
}
