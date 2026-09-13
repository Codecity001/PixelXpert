package sh.siava.pixelxpert.service.tileServices;

import android.graphics.drawable.Icon;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import sh.siava.pixelxpert.R;

public class CaffeineTileService extends TileService {

	private static final int TIMEOUT_INFINITE = Integer.MAX_VALUE;

	private static final int[] TIMEOUT_STEPS = new int[]{
			15000,    // 15 seconds
			30000,    // 30 seconds
			60000,    // 1 minute
			120000,   // 2 minutes
			300000,   // 5 minutes
			600000,   // 10 minutes
			1800000,  // 30 minutes
			TIMEOUT_INFINITE // Infinite
	};

	@Override
	public void onTileAdded() {
		super.onTileAdded();
		updateTileState();
	}

	@Override
	public void onStartListening() {
		super.onStartListening();
		updateTileState();
	}

	@Override
	public void onStopListening() {
		super.onStopListening();
	}

	// When Xposed hooks are active (normal operation), CaffeineTile.java intercepts
	// handleClick in SystemUI's process. This serves as a fallback — since PixelXpert
	// is mounted as a system app via Magisk, Settings.System writes work here too.
	@Override
	public void onClick() {
		super.onClick();
		triggerHapticFeedback();
		try {
			int current = Settings.System.getInt(
					getContentResolver(),
					Settings.System.SCREEN_OFF_TIMEOUT,
					60000
			);
			int next = getNextTimeout(current);
			Settings.System.putInt(
					getContentResolver(),
					Settings.System.SCREEN_OFF_TIMEOUT,
					next
			);
		} catch (Throwable ignored) {
		}
		updateTileState();
	}

	private void triggerHapticFeedback() {
		try {
			Vibrator vibrator = getSystemService(Vibrator.class);
			if (vibrator != null && vibrator.hasVibrator()) {
				vibrator.vibrate(
						VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
						VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
				);
			}
		} catch (Throwable ignored) {
		}
	}

	@Override
	public void onTileRemoved() {
		super.onTileRemoved();
	}

	private void updateTileState() {
		Tile tile = getQsTile();
		if (tile == null) return;

		try {
			int current = Settings.System.getInt(
					getContentResolver(),
					Settings.System.SCREEN_OFF_TIMEOUT,
					60000
			);
			tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_caffeine));
			tile.setState(Tile.STATE_ACTIVE);
			tile.setSubtitle(formatTimeout(current));
			tile.setLabel(getString(R.string.caffeine_tile_title));
			tile.updateTile();
		} catch (Throwable ignored) {
		}
	}

	private static int getNextTimeout(int current) {
		for (int step : TIMEOUT_STEPS) {
			if (step > current) {
				return step;
			}
		}
		return TIMEOUT_STEPS[0];
	}

	private static String formatTimeout(int timeoutMs) {
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
}
