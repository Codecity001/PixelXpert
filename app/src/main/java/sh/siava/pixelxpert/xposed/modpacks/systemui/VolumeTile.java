package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static android.media.AudioManager.STREAM_MUSIC;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.AudioManager;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.registerVolumeChangeListener;

import android.content.Context;
import android.content.Intent;
import android.service.quicksettings.Tile;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.AlertSlider;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;
import sh.siava.pixelxpert.service.tileServices.VolumeTileService;

/** @noinspection DataFlowIssue*/
@SystemUIModPack
public class VolumeTile extends XposedModPack {
	private Object mTile;
	private boolean mNextTileIsVolume;

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
					if(arg.contains(VolumeTileService.class.getSimpleName()))
					{
						mNextTileIsVolume = true;
					}
				});

		CustomTileClass
				.beforeConstruction()
				.run(param -> {
					if(mNextTileIsVolume)
					{
						mTile = param.thisObject;
						mNextTileIsVolume = false;
					}
				});

		CustomTileClass
				.afterConstruction()
				.run(param -> {
					if(param.thisObject == mTile)
					{
						registerVolumeChangeListener(newVal -> updateTile());
						updateTile();
					}
				});

		CustomTileClass
				.after("newTileState")
				.run(param -> {
					if(param.thisObject == mTile)
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

						param.setResult(null); //otherwise it will also call click
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

	private void updateTile() {
		Tile mTile = (Tile) getObjectField(this.mTile, "mTile");
		mTile.setState(SystemUtils.AudioManager().isStreamMute(STREAM_MUSIC) ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);

		callMethod(this.mTile, "refreshState", new Object[]{null});
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
}