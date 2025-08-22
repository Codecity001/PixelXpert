package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.getFlashlightLevel;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.getMaxFlashLevel;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.isFlashOn;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.registerFlashlightLevelListener;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.unregisterFlashlightLevelListener;

import android.content.Context;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.ResourceManager;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.AlertSlider;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedMethod;

@SystemUIModPack
public class FlashlightTile extends XposedModPack {
	private boolean leveledFlashTile = false;
	private boolean AnimateFlashlight = false;
	private Object mTile;

	public FlashlightTile(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		leveledFlashTile = Xprefs.getBoolean("leveledFlashTile", false);
		AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false);
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass FlashlightTileClass = ReflectedClass.of("com.android.systemui.qs.tiles.FlashlightTile");
		ReflectedClass QSTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl");

		FlashlightTileClass
				.beforeConstruction()
				.run(param -> mTile = param.thisObject);


		FlashlightTileClass
				.before("handleClick")
				.run(param -> {

					Object state = getObjectField(param.thisObject, "mState");
					boolean handlesSecondary = (boolean) getObjectField(state, "handlesSecondaryClick");

					if(!handlesSecondary || !leveledFlashTile)
						return;

					if(handleFlashLongClick())
					{
						param.setResult(null);
					}
				});

		FlashlightTileClass
				.after("handleUpdateState")
				.run(param -> {
					Object state = param.args[0];
					setObjectField(state, "handlesSecondaryClick", leveledFlashTile);

					if(leveledFlashTile) {
						String subTitle = ResourceManager.modRes.getString(R.string.sliding_tile_subtitle);
						setObjectField(state, "secondaryLabel", subTitle);
					}
				});

		QSTileImplClass
				.before("handleSecondaryClick")
				.run(param -> {
					if(leveledFlashTile && param.thisObject == mTile)
					{
						ReflectedMethod
								.ofName(FlashlightTileClass, "handleClick")
								.invokeOriginal(mTile, param.args[0]);

						param.setResult(null); //otherwise it will also call click
					}
				});

		FlashlightTileClass
				.before("handleLongClick")
				.run(param -> {
					if(leveledFlashTile && handleFlashLongClick())
						param.setResult(null);
				});

		FlashlightTileClass
				.after("newTileState")
				.run(param ->
						setObjectField(param.getResult(), "handlesLongClick", true));
	}

	private boolean handleFlashLongClick() throws Throwable {
		showAlertSlider();

		return true;
	}

	private void showAlertSlider() throws Throwable {
		AlertSlider.SliderEventCallback flashlightSliderCallback = new AlertSlider.SliderEventCallback() {
			@Override
			public void onStartTrackingTouch(Object slider) {}

			@Override
			public void onStopTrackingTouch(Object slider) {
				float value = (float) callMethod(slider, "getValue");

				Xprefs.edit()
						.putInt("flashPCT",
								Math.round((value * 100f) / getMaxFlashLevel())
						).apply();

				if(!isFlashOn())
				{
					SystemUtils.setFlash(true, AnimateFlashlight);
				}
			}

			@Override
			public void onValueChange(Object slider, float value, boolean fromUser) {
				if(isFlashOn()) {
					SystemUtils.setFlash(true, Math.round(value), false);
				}
			}

			@Override
			public void onCreate(AlertSlider alertSlider) {
				registerFlashlightLevelListener(alertSlider);
			}

			@Override
			public void onDismiss(AlertSlider alertSlider) {
				unregisterFlashlightLevelListener(alertSlider);
			}
		};

		AlertSlider alertSlider = new AlertSlider(mContext,
				getFlashlightLevel(
						Xprefs.getInt("flashPCT", 50)
								/ 100f),
				1,
				getMaxFlashLevel(),
				1,
				flashlightSliderCallback) {
			@Override
			public void onChanged(int newVal) {
				setSliderCurrentValue(newVal);
			}
		};

		alertSlider.show();
	}
}