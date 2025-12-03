//Dynamic icon mostly made by Gemini agent

package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static android.media.AudioManager.STREAM_MUSIC;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.AudioManager;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.registerVolumeChangeListener;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioPlaybackConfiguration;
import android.service.quicksettings.Tile;

import androidx.core.content.res.ResourcesCompat;

import java.util.List;
import java.util.Objects;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.service.tileServices.VolumeTileService;
import sh.siava.pixelxpert.xposed.ResourceManager;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.AlertSlider;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;

/** @noinspection DataFlowIssue*/
@SystemUIModPack
public class VolumeTile extends XposedModPack {
	private static final float ICON_PADDING_TOP_PERCENT = 0.125f;
	private static final float ICON_PADDING_BOTTOM_PERCENT = 0.125f;

	private static final String TAG = "VolumeTileXposed";
	private Object mTile;
	private boolean mLastDeviceBT = false;
	private boolean mNextTileIsVolume = false;

	public VolumeTile(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass CustomTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile");
		ReflectedClass QSFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl");
		ReflectedClass QSTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl");

		QSFactoryImplClass
				.before("createTile")
				.run(param -> {
					String arg = (String) param.args[0];
					if(arg.contains(VolumeTileService.class.getSimpleName())) {
						mNextTileIsVolume = true;
					}
				});

		QSFactoryImplClass
				.after("createTile")
				.run(param -> {
					String arg = (String) param.args[0];
					if(arg.contains(VolumeTileService.class.getSimpleName()))
					{
						mNextTileIsVolume = false;
						mTile = param.getResult();
						AudioManager().registerAudioDeviceCallback(new AudioDeviceCallback() {
							@Override
							public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
								for (AudioDeviceInfo addedDevice : addedDevices) {
									int type = addedDevice.getType();
									if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
										mLastDeviceBT = true;
										updateTile();
										return;
									}
								}
							}

							@Override
							public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
								for (AudioDeviceInfo removedDevice : removedDevices) {
									int type = removedDevice.getType();
									if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
										mLastDeviceBT = false;
										updateTile();
										return;
									}
								}
							}
						}, null);

						registerVolumeChangeListener(this::updateTile);
						updateTile(); // Initial update
					}
				});

		CustomTileClass
				.after("newTileState")
				.run(param -> {
					if(mNextTileIsVolume)
					{
						Object state = param.getResult();
						setObjectField(state, "handlesSecondaryClick", true);
					}
				});

		CustomTileClass
				.after("handleClick")
				.run(param -> {
					if(param.thisObject == mTile)
					{
						handleVolumeLongClick();
					}
				});

		QSTileImplClass
				.before("handleSecondaryClick")
				.run(param -> {
					if(param.thisObject == mTile)
					{
						SystemUtils.toggleMute();
						updateTile();
						param.setResult(null);
					}
				});

		CustomTileClass
				.before("getLongClickIntent")
				.run(param -> {
					if(param.thisObject == mTile)
					{
						if(handleVolumeLongClick())
							param.setResult(new Intent());
					}
				});
	}

	private void updateTile(int value) {
		Tile tile = (Tile) getObjectField(this.mTile, "mTile");
		setIcon(tile, value); // Call to non-static setIcon
		tile.setState(value == AudioManager().getStreamMinVolume(STREAM_MUSIC) ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
		callMethod(this.mTile, "refreshState", new Object[]{null});
	}

	private void setIcon(Tile tile, int currentVolume) {
		int minVolume = AudioManager().getStreamMinVolume(STREAM_MUSIC);
		int maxVolume = AudioManager().getStreamMaxVolume(STREAM_MUSIC);

		int muteResId;
		int filledResId;
		int outlineResId;

		updateBluetoothStatus();

		if (mLastDeviceBT) {
			muteResId = R.drawable.ic_volume_bt_mute;
			filledResId = R.drawable.ic_volume_bt;
			outlineResId = R.drawable.ic_volume_bt_outline;
		} else {
			muteResId = R.drawable.ic_volume_mute;
			filledResId = R.drawable.ic_volume;
			outlineResId = R.drawable.ic_volume_outline;
		}

		if (currentVolume <= minVolume) {
			tile.setIcon(Icon.createWithResource(BuildConfig.APPLICATION_ID, muteResId));
		} else {
			Icon customIcon = createPercentageIcon(mContext, currentVolume, minVolume, maxVolume,
					filledResId, outlineResId, ICON_PADDING_TOP_PERCENT, ICON_PADDING_BOTTOM_PERCENT);
			tile.setIcon(Objects.requireNonNullElseGet(customIcon, () -> Icon.createWithResource(BuildConfig.APPLICATION_ID, filledResId)));
		}
	}

	private void updateBluetoothStatus() {
		List<AudioPlaybackConfiguration> activeConfigurations = AudioManager().getActivePlaybackConfigurations();

		boolean deviceInfoFound = false;
		boolean bluetoothFound = false;

		for (AudioPlaybackConfiguration config : activeConfigurations) {
			//noinspection deprecation
			AudioDeviceInfo deviceInfo = config.getAudioDeviceInfo();
			if (deviceInfo != null) {
				deviceInfoFound = true;
				int deviceType = deviceInfo.getType();
				if (deviceInfo.isSink() &&
						(deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)) {
					bluetoothFound = true;
					break;
				}
			}
		}

		if (deviceInfoFound) {
			this.mLastDeviceBT = bluetoothFound;
		}
	}

	private void updateTile() {
		updateTile(SystemUtils.AudioManager().getStreamVolume(STREAM_MUSIC));
	}

	private boolean handleVolumeLongClick() throws Throwable {
		showAlertSlider();
		return true;
	}

	private void showAlertSlider() throws Throwable {
		AlertSlider.SliderEventCallback volumeSliderCallback = new AlertSlider.SliderEventCallback() {
			@Override
			public void onStartTrackingTouch(Object slider) {}

			@Override
			public void onStopTrackingTouch(Object slider) {}

			@Override
			public void onValueChange(Object slider, float value, boolean fromUser) {
				if(fromUser)
					changeVolume(Math.round(value));
			}

			@Override
			public void onCreate(AlertSlider alertSlider) {
				registerVolumeChangeListener(alertSlider);
			}

			@Override
			public void onDismiss(AlertSlider alertSlider) {
				SystemUtils.unregisterVolumeChangeListener(alertSlider);
			}
		};

		AlertSlider alertSlider = new AlertSlider(mContext,
				AudioManager().getStreamVolume(STREAM_MUSIC),
				AudioManager().getStreamMinVolume(STREAM_MUSIC),
				AudioManager().getStreamMaxVolume(STREAM_MUSIC),
				1,
				volumeSliderCallback) {
			@Override
			public void onChanged(int newVal) {
				setSliderCurrentValue(newVal);
			}
		};
		alertSlider.show();
	}

	private void changeVolume(int currentValue) {
		AudioManager().setStreamVolume(
				STREAM_MUSIC,
				currentValue,
				0 /* don't show UI */);
	}

	public static Icon createPercentageIcon(Context context, int currentValue, int minValue, int maxValue, int filledResId, int outlineResId, float paddingTopPercent, float paddingBottomPercent) {
		try {
			Drawable filledDrawable = ResourcesCompat.getDrawable(ResourceManager.modRes, filledResId, context.getTheme());
			Drawable outlineDrawable = ResourcesCompat.getDrawable(ResourceManager.modRes, outlineResId, context.getTheme());

			int width = filledDrawable.getIntrinsicWidth();
			int height = filledDrawable.getIntrinsicHeight();

			int paddingTopPx = (int) (height * paddingTopPercent);
			int paddingBottomPx = (int) (height * paddingBottomPercent);

			Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(bitmap);

			int contentActualHeight = height - paddingTopPx - paddingBottomPx;

			float percentage = 0f;
			if (maxValue > minValue) {
				percentage = (float) (currentValue - minValue) / (float) (maxValue - minValue);
			}
			percentage = Math.max(0f, Math.min(1f, percentage));

			int filledContentPixelHeight = (int) (contentActualHeight * percentage);
			int outlineContentPixelHeight = contentActualHeight - filledContentPixelHeight;

			int splitYAbsolute = paddingTopPx + outlineContentPixelHeight;

			canvas.save();
			canvas.clipRect(0, splitYAbsolute, width, height - paddingBottomPx);
			filledDrawable.setBounds(0, 0, width, height);
			filledDrawable.draw(canvas);
			canvas.restore();

			canvas.save();
			canvas.clipRect(0, paddingTopPx, width, splitYAbsolute);
			outlineDrawable.setBounds(0, 0, width, height);
			outlineDrawable.draw(canvas);
			canvas.restore();

			return Icon.createWithBitmap(bitmap);
		} catch (Throwable ignored) {
			return Icon.createWithResource(BuildConfig.APPLICATION_ID, filledResId);
		}
	}
}
