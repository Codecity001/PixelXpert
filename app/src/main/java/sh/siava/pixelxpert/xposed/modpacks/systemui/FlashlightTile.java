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
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.ResourceManager;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.AlertSlider;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedMethod;

@SystemUIModPack
public class FlashlightTile extends XposedModPack {
	private boolean leveledFlashTile = false;
	private boolean AnimateFlashlight = false;
	private Object mTile;
	private LevelDrawable mLevelDrawable;
	private final SystemUtils.ChangeListener mFlashLevelListener = new SystemUtils.ChangeListener() {
		@Override
		public void onChanged(int newVal) {
			mLevelDrawable.updateState(newVal);
			callMethod(mTile, "refreshState");

		}
	};

	public FlashlightTile(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		leveledFlashTile = Xprefs.getBoolean("leveledFlashTile", false);
		AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false);

		if(leveledFlashTile)
		{
			if(mLevelDrawable == null) {
				mLevelDrawable = new LevelDrawable(mContext);
				mLevelDrawable.init(0, SystemUtils.getMaxFlashLevel(), R.drawable.qs_flashlight_on, R.drawable.qs_fashlight_on_outline, .1f, .1f, .3f);

				mLevelDrawable.updateState(getFlashlightLevel(
						Xprefs.getInt("flashPCT", 50)
								/ 100f));
			}

			registerFlashlightLevelListener(mFlashLevelListener);
		}
	}

	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass FlashlightTileClass = ReflectedClass.of("com.android.systemui.qs.tiles.FlashlightTile");
		ReflectedClass QSTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl");
		ReflectedClass DrawableIconClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl$DrawableIcon");

		FlashlightTileClass
				.before("newTileState") //constructor is optimized. this is a good substitute
				.run(param -> {
					if(mTile == null)
						mTile = param.thisObject;
				});

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
						boolean isEnabled = (boolean) getObjectField(state, "value");

						if(isEnabled)
						{
							Object icon = DrawableIconClass.getClazz().getConstructors()[0].newInstance(mLevelDrawable);
							setObjectField(state, "icon", icon);
						}

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
		StatusbarGestures.collapseQSPanel();
		AlertSlider.SliderEventCallback flashlightSliderCallback = new AlertSlider.SliderEventCallback() {
			@Override
			public void onStartTrackingTouch(Object slider) {}

			@Override
			public void onStopTrackingTouch(Object slider) {
				float value = (float) callMethod(slider, "getValue");

				new Thread(() -> Xprefs.edit()
						.putInt("flashPCT",
								Math.round((value * 100f) / getMaxFlashLevel())
						).apply()).start();

				if(!isFlashOn())
				{
					SystemUtils.setFlash(true, AnimateFlashlight);
				}
			}

			@Override
			public void onValueChange(Object slider, float value, boolean fromUser) {
				new Thread(() -> {
					if(isFlashOn()) {
						SystemUtils.setFlash(true, Math.round(value), false);
					}
				}).start();
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
	public static class LevelDrawable extends Drawable {
		private final Context mContext;
		private int mCurrentLevel;
		private int mMinLevel;
		private int mMaxLevel;
		private int mFilledResId;
		private int mOutlineResId;
		private float mPaddingTopPercent;
		private float mPaddingBottomPercent;
		private float mOutlineDeadZoneTopPercent; // Dead zone for the outline icon

		private Drawable mLoadedFilledDrawable;
		private Drawable mLoadedOutlineDrawable;
		public LevelDrawable(Context context) {
			this.mContext = context;
		}

		// Consolidated init/update method
		public void init(int minLevel, int maxLevel,
						 int filledResId, int outlineResId,
						 float paddingTopPercent, float paddingBottomPercent,
						 float outlineDeadZoneTopPercent) {

			this.mMinLevel = minLevel;
			this.mMaxLevel = maxLevel;
			this.mPaddingTopPercent = paddingTopPercent;
			this.mPaddingBottomPercent = paddingBottomPercent;
			this.mOutlineDeadZoneTopPercent = outlineDeadZoneTopPercent;

			if (this.mLoadedFilledDrawable == null || this.mFilledResId != filledResId) {
				this.mLoadedFilledDrawable = ResourcesCompat.getDrawable(ResourceManager.modRes, filledResId, mContext.getTheme());
			}
			this.mFilledResId = filledResId;

			if (this.mLoadedOutlineDrawable == null || this.mOutlineResId != outlineResId) {
				this.mLoadedOutlineDrawable = ResourcesCompat.getDrawable(ResourceManager.modRes, outlineResId, mContext.getTheme());
			}
			this.mOutlineResId = outlineResId;

			invalidateSelf();
		}

		public void updateState(int currentLevel) {
			if (this.mCurrentLevel != currentLevel) {
				this.mCurrentLevel = currentLevel;
				invalidateSelf();
			}
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			Rect bounds = getBounds();
			int width = bounds.width();
			int height = bounds.height();

			if (width <= 0 || height <= 0) {
				return;
			}

			int overallPaddingTopPx = (int) (height * mPaddingTopPercent);
			int overallPaddingBottomPx = (int) (height * mPaddingBottomPercent);
			int contentActualHeight = height - overallPaddingTopPx - overallPaddingBottomPx;

			int outlineDeadZonePx = (int) (contentActualHeight * mOutlineDeadZoneTopPercent);
			if (outlineDeadZonePx < 0) outlineDeadZonePx = 0;

			int effectiveProgressHeight = contentActualHeight - outlineDeadZonePx;

			float levelPercentage = 0f;
			if (mMaxLevel > mMinLevel) { // Avoid division by zero
				levelPercentage = (float) (mCurrentLevel - mMinLevel) / (float) (mMaxLevel - mMinLevel);
			}
			levelPercentage = Math.max(0f, Math.min(1f, levelPercentage)); // Clamp

			int filledPartOfEffectiveProgressHeight = (int) (effectiveProgressHeight * levelPercentage);
			int outlinePartOfEffectiveProgressHeight = effectiveProgressHeight - filledPartOfEffectiveProgressHeight;

			int splitYAbsolute = overallPaddingTopPx + outlineDeadZonePx + outlinePartOfEffectiveProgressHeight;

			canvas.save();
			canvas.clipRect(0, splitYAbsolute, width, height - overallPaddingBottomPx);
			mLoadedFilledDrawable.setBounds(bounds);
			mLoadedFilledDrawable.draw(canvas);
			canvas.restore();

			canvas.save();
			canvas.clipRect(0, overallPaddingTopPx, width, splitYAbsolute);
			mLoadedOutlineDrawable.setBounds(bounds);
			mLoadedOutlineDrawable.draw(canvas);
			canvas.restore();
		}

		@Override
		public void setAlpha(int alpha) {
			mLoadedFilledDrawable.setAlpha(alpha);
			mLoadedOutlineDrawable.setAlpha(alpha);
			invalidateSelf();
		}

		@Override
		public void setColorFilter(ColorFilter colorFilter) {
			mLoadedFilledDrawable.setColorFilter(colorFilter);
			mLoadedOutlineDrawable.setColorFilter(colorFilter);
			invalidateSelf();
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}

		@Override
		public int getIntrinsicWidth() {
			return mLoadedFilledDrawable.getIntrinsicWidth();
		}

		@Override
		public int getIntrinsicHeight() {
			return mLoadedFilledDrawable.getIntrinsicHeight();
		}
	}
}