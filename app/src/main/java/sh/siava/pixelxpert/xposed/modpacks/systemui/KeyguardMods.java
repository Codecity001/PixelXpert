package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static android.graphics.Color.TRANSPARENT;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.modpacks.systemui.BatteryDataProvider.isCharging;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf;
import static sh.siava.pixelxpert.xposed.utils.toolkit.ColorUtils.getColorAttrDefaultColor;

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraManager;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintSet;

import java.lang.ref.WeakReference;
import java.util.regex.Pattern;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.ResourceManager;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.utils.StringFormatter;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.ReflectionTools;

@SuppressWarnings("RedundantThrows")
@SystemUIModPack
public class KeyguardMods extends XposedModPack {
	//region keyguard charging data
	public static final String EXTRA_MAX_CHARGING_CURRENT = "max_charging_current";
	public static final String EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage";
	public static final String EXTRA_TEMPERATURE = "temperature";

	private static final Object WALLPAPER_DIM_AMOUNT_DIMMED = 0.6F; //DefaultDeviceEffectsApplier
	private static WeakReference<KeyguardMods> instance = null;

	private float max_charging_current = 0;
	private float max_charging_voltage = 0;
	private float temperature = 0;

	private static boolean ShowChargingInfo = false;
	//endregion

	private static boolean customCarrierTextEnabled = false;
	private static String customCarrierText = "";
	private static Object carrierTextController;

	final StringFormatter carrierStringFormatter = new StringFormatter();
	final StringFormatter clockStringFormatter = new StringFormatter();
	private TextView mComposeKGMiddleCustomTextView;
	private static String KGMiddleCustomText = "";
	private ViewGroup mKeyguardRootView;
	private Object mColorExtractor;
	private boolean mDozing = false;
	private boolean mSupportsDarkText = false;

	private static boolean DisableUnlockHintAnimation = false;

	//region keyguardDimmer
	public static float KeyGuardDimAmount = -1f;
	private static boolean TemperatureUnitF = false;
	//endregion

	//region keyguard bottom area shortcuts and transparency
	private static boolean transparentBGcolor = false;
	//endregion

	//region hide user avatar
	private boolean HideLockScreenUserAvatar = false;
	private static boolean ForceAODwCharging = false;
	private Object KeyguardIndicationController;
	//endregion

	private static boolean AnimateFlashlight = false;

	public KeyguardMods(Context context) {
		super(context);

		instance = new WeakReference<>(this);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		DisableUnlockHintAnimation = Xprefs.getBoolean("DisableUnlockHintAnimation", false);

		KGMiddleCustomText = Xprefs.getString("KGMiddleCustomText", "");

		customCarrierTextEnabled = Xprefs.getBoolean("carrierTextMod", false);
		customCarrierText = Xprefs.getString("carrierTextValue", "");

		ShowChargingInfo = Xprefs.getBoolean("ShowChargingInfo", false);
		TemperatureUnitF = Xprefs.getBoolean("TemperatureUnitF", false);

		HideLockScreenUserAvatar = Xprefs.getBoolean("HideLockScreenUserAvatar", false);

		ForceAODwCharging = Xprefs.getBoolean("ForceAODwCharging", false);

		KeyGuardDimAmount = Xprefs.getSliderFloat( "KeyGuardDimAmount", -1f) / 100f;

		transparentBGcolor = Xprefs.getBoolean("KeyguardBottomButtonsTransparent", false);

		AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false);

		if (Key.length > 0) {
			switch (Key[0]) {
				case "KGMiddleCustomText":
					updateMiddleTexts();
					break;
				case "carrierTextValue":
				case "carrierTextMod":
					if (customCarrierTextEnabled) {
						setCarrierText();
					} else {
						try {
							callMethod(
									getObjectField(carrierTextController, "mCarrierTextManager"),
									"updateCarrierText");
						} catch (Throwable ignored) {
						} //probably not initiated yet
					}
					break;
			}
		}
	}

	@SuppressLint({"DiscouragedApi", "DefaultLocale"})
	@Override
	public void onPackageLoaded(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		ReflectedClass CarrierTextControllerClass = ReflectedClass.of("com.android.keyguard.CarrierTextController");
		ReflectedClass KeyguardIndicationControllerClass = ReflectedClass.of("com.android.systemui.statusbar.KeyguardIndicationController");
		ReflectedClass ScrimControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimController");
		ReflectedClass ScrimStateEnum = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimState");
		ReflectedClass KeyguardStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.KeyguardStatusBarView");
		ReflectedClass CentralSurfacesImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.CentralSurfacesImpl");
		ReflectedClass NotificationPanelViewControllerClass = ReflectedClass.of("com.android.systemui.shade.NotificationPanelViewController"); //used to launch camera
		ReflectedClass AmbientDisplayConfigurationClass = ReflectedClass.of("android.hardware.display.AmbientDisplayConfiguration");
		ReflectedClass DefaultShortcutsSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection");
		ReflectedClass SmartspaceSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection");
		ReflectedClass DefaultNotificationStackScrollLayoutSectionClass = ReflectedClass.of("com.android.systemui.keyguard.ui.view.layout.sections.DefaultNotificationStackScrollLayoutSection");
		ReflectedClass KeyguardIndicationControllerGoogleClass = ReflectedClass.of("com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle");

		ReflectedClass.of(CameraManager.class)
				.before("setTorchMode")
				.run(param -> {
					SystemUtils.setFlash((Boolean) param.args[1], AnimateFlashlight);
					param.setResult(null);
				});

		DefaultShortcutsSectionClass
				.after("addViews")
				.run(param -> {
					Resources res = mContext.getResources();

					ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
							(ImageView) callMethod(param.args[0], "findViewById", res.getIdentifier(
									"end_button",
									"id",
									mContext.getPackageName())));

					ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
							(ImageView) callMethod(param.args[0], "findViewById", res.getIdentifier(
									"start_button",
									"id",
									mContext.getPackageName())));
				});

		DefaultNotificationStackScrollLayoutSectionClass
				.after("applyConstraints")
				.run(param -> {
					Object constraintSet = param.args[0];

					callMethod(constraintSet,
							"connect",
							mComposeKGMiddleCustomTextView.getId(),
							ConstraintSet.BOTTOM,
							idOf("nssl_placeholder"),
							ConstraintSet.TOP);
				});

		SmartspaceSectionClass
				.after("addViews")
				.run(param -> {
					try {
						if(mComposeKGMiddleCustomTextView == null) {
							mComposeKGMiddleCustomTextView = new TextView(mContext);
							mComposeKGMiddleCustomTextView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
							mComposeKGMiddleCustomTextView.setMaxLines(2);
							mComposeKGMiddleCustomTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
							mComposeKGMiddleCustomTextView.setLetterSpacing(.03f);
							mComposeKGMiddleCustomTextView.setId(View.generateViewId());
							mComposeKGMiddleCustomTextView.setZ(100f);
						}

						try {
							LinearLayout smartSpaceContainer = (LinearLayout) getObjectField(param.thisObject, "dateView");
							mKeyguardRootView = smartSpaceContainer.getRootView().findViewById(idOf("keyguard_root_view"));
						}
						catch (Throwable ignored) {}

						updateMiddleTexts();
						setMiddleColor();
					}
					catch (Throwable ignored){
					}
				});

		AmbientDisplayConfigurationClass
				.after("alwaysOnEnabled")
				.run(param -> {
					if(ForceAODwCharging) {
						param.setResult((boolean) param.getResult() || isCharging());
					}
				});

		NotificationPanelViewControllerClass
				.before("startUnlockHintAnimation")
				.run(param -> {
					if(DisableUnlockHintAnimation) param.setResult(null);
				});

		//needed to extract wallpaper colors and capabilities. This is a SysUIColorExtractor
		CentralSurfacesImplClass
				.afterConstruction()
				.run(param -> mColorExtractor = getObjectField(param.thisObject, "mColorExtractor"));

		//region hide user avatar
		KeyguardStatusBarViewClass
				.after("updateVisibilities")
				.run(param -> {
					View mMultiUserAvatar = (View) getObjectField(param.thisObject, "mMultiUserAvatar");
					boolean mIsUserSwitcherEnabled = getBooleanField(param.thisObject, "mIsUserSwitcherEnabled");
					mMultiUserAvatar.setVisibility(!HideLockScreenUserAvatar && mIsUserSwitcherEnabled
							? VISIBLE
							: GONE);
				});
		//endregion

		//region keyguard battery info

		KeyguardIndicationControllerGoogleClass
				.afterConstruction()
				.run(param ->
						KeyguardIndicationController = param.thisObject);

		KeyguardIndicationControllerGoogleClass
				.after("computePowerIndication")
				.run(param -> {
					if (ShowChargingInfo) {
						String result = (String) param.getResult();

						Float shownTemperature = (TemperatureUnitF)
								? (temperature * 1.8f) + 32f
								: temperature;

						param.setResult(
								String.format(
										"%s\n%.1fW (%.1fV, %.1fA) • %.0fº%s"
										, result
										, max_charging_current * max_charging_voltage
										, max_charging_voltage
										, max_charging_current
										, shownTemperature
										, TemperatureUnitF
												? "F"
												: "C"));
					}
				});


		BatteryDataProvider.registerStatusCallback((batteryStatus, batteryStatusIntent) -> {
			max_charging_current = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / 1000000f;
			max_charging_voltage = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / 1000000f;
			temperature = batteryStatusIntent.getIntExtra(EXTRA_TEMPERATURE, 0) / 10f;
		});
		//endregion

		//region keyguardDimmer
		ScrimControllerClass
				.before(Pattern.compile("scheduleUpdate.*"))
				.run(param -> {
					if (KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1) return;

					setObjectField(param.thisObject, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount);
					Object[] constants = ScrimStateEnum.getClazz().getEnumConstants();
					for (Object constant : constants) {
						setObjectField(constant, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount);
					}
				});

		ReflectedClass.of(WallpaperManager.class)
				.after("getWallpaperDimAmount")
				.run(param -> {
					//noinspection ConstantValue
					if ((KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1)
							|| param.getResult().equals(WALLPAPER_DIM_AMOUNT_DIMMED))
						return;

					//ref ColorUtils.compositeAlpha - Since KEYGUARD_SCRIM_ALPHA = .2f, we need to range the result between -70 and 255 to get the correct value when composed with 20% - we use 60 to cover float inaccuracies and never see a negative final result
					param.setResult((325 * KeyGuardDimAmount - 60) / 255);
				});
		//endregion

		carrierStringFormatter.registerCallback(this::setCarrierText);

		clockStringFormatter.registerCallback(this::updateMiddleTexts);

		CarrierTextControllerClass
				.after("onInit")
				.run(param -> {
					carrierTextController = param.thisObject;
					Object carrierTextCallback = getObjectField(carrierTextController, "mCarrierTextCallback");
					setCarrierText();
					ReflectedClass.of(carrierTextCallback.getClass())
							.before("updateCarrierInfo")
							.run(param1 -> {
								if (customCarrierTextEnabled)
									param1.setResult(null);
							});
				});

		//a way to know when the device goes to AOD/dozing
		KeyguardIndicationControllerClass
				.after("updateDeviceEntryIndication")
				.run(param -> {
					if (mDozing != (boolean) getObjectField(param.thisObject, "mDozing")) {
						mDozing = !mDozing;
						setMiddleColor();
					}
				});
	}

	private void setMiddleColor() {
		if(mColorExtractor != null) {
			Object colors = callMethod(mColorExtractor, "getColors", WallpaperManager.FLAG_LOCK);
			mSupportsDarkText = (boolean) callMethod(colors, "supportsDarkText");
		}
		int color = (mDozing || !mSupportsDarkText) ? Color.WHITE : Color.BLACK;

		try {
			mComposeKGMiddleCustomTextView.setShadowLayer(1, 1, 1, color == Color.BLACK ? Color.TRANSPARENT : Color.BLACK); //shadow only for white color
			mComposeKGMiddleCustomTextView.setTextColor(color);
		}
		catch (Throwable ignored) {}
	}

	private void setCarrierText() {
		if(!customCarrierTextEnabled) return;

		try {
			TextView mView = (TextView) getObjectField(carrierTextController, "mView");
			mView.post(() -> mView.setText(carrierStringFormatter.formatString(customCarrierText)));
		} catch (Throwable ignored) {} //probably not initiated yet
	}

	private void updateMiddleTexts()
	{
		CharSequence text = null;
		if(!KGMiddleCustomText.isEmpty())
		{
			text = clockStringFormatter.formatString(KGMiddleCustomText);
		}

		try {
			setMiddleTextCompose(text);
		} catch (Throwable ignored) {}
	}

	private void setMiddleTextCompose(CharSequence text) {
		mKeyguardRootView.post(() -> {
			if (text == null) {
				mKeyguardRootView.removeView(mComposeKGMiddleCustomTextView);
			} else {
				ReflectionTools.reAddView(mKeyguardRootView, mComposeKGMiddleCustomTextView);
				mComposeKGMiddleCustomTextView.setText(text);
			}
		});
	}


	public static String getPowerIndicationString()
	{
		try {
			return (String) callMethod(instance.get().KeyguardIndicationController, "computePowerIndication");
		}
		catch (Throwable ignored)
		{
			return ResourceManager.modRes.getString(R.string.power_indication_error);
		}
	}

	public static class ControlledLaunchableImageViewBackgroundDrawable extends Drawable
	{
		Context mContext;
		Drawable mDrawable;
		WeakReference<ImageView> parentImageViewReference;

		public static void captureDrawable(ImageView imageView)
		{
			try {
				Drawable background = imageView.getBackground();

				background = new ControlledLaunchableImageViewBackgroundDrawable(background.getCurrent().mutate(), imageView);
				imageView.setBackground(background);
			} catch (Throwable ignored) {}
		}
		@NonNull
		@Override
		public Drawable mutate()
		{
			return new ControlledLaunchableImageViewBackgroundDrawable(mDrawable.mutate(), parentImageViewReference.get());
		}
		public ControlledLaunchableImageViewBackgroundDrawable(Drawable drawable, ImageView parentView)
		{
			parentImageViewReference = new WeakReference<>(parentView);
			mContext = parentView.getContext();
			mDrawable = drawable;
		}
		@Override
		public void setTint(int tintColor)
		{
			if(!transparentBGcolor)
			{
				mDrawable.setTint(tintColor);
			}
			else
			{
				mDrawable.setTint(TRANSPARENT);
			}
		}

		@Override
		public void setTintList(ColorStateList tintList) {
			if(transparentBGcolor)
			{
				mDrawable.setTint(TRANSPARENT);

				ImageView parentView = parentImageViewReference.get();
				if (parentView != null && parentView.getDrawable() != null) {
					@SuppressLint("DiscouragedApi") int wallpaperTextColorAccent = getColorAttrDefaultColor(
							mContext,
							mContext.getResources().getIdentifier("wallpaperTextColorAccent", "attr", mContext.getPackageName()));

					parentView.getDrawable().setTint(wallpaperTextColorAccent);
				}
			}
			else
			{
				mDrawable.setTintList(tintList);
			}
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			mDrawable.draw(canvas);
		}

		@Override
		public void jumpToCurrentState()
		{
			mDrawable.jumpToCurrentState();
		}

		@Override
		public void setAlpha(int alpha) {
			mDrawable.setAlpha(alpha);
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
			if(!transparentBGcolor)
			{
				mDrawable.setColorFilter(colorFilter);
			}
		}

		@Override
		public int getOpacity() {
			//noinspection deprecation
			return mDrawable.getOpacity();
		}

		@Override
		public boolean getPadding(@NonNull Rect padding)
		{
			return mDrawable.getPadding(padding);
		}

		@Override
		public int getMinimumHeight()
		{
			return mDrawable.getMinimumHeight();
		}

		@Override
		public int getMinimumWidth()
		{
			return mDrawable.getMinimumWidth();
		}

		@Override
		public boolean isStateful()
		{
			return mDrawable.isStateful();
		}

		@Override
		public boolean setVisible(boolean visible, boolean restart)
		{
			return mDrawable.setVisible(visible, restart);
		}

		@Override
		public void getOutline(@NonNull Outline outline)
		{
			mDrawable.getOutline(outline);
		}

		@Override
		public boolean isProjected()
		{
			return mDrawable.isProjected();
		}

		@Override
		public void setBounds(@NonNull Rect bounds)
		{
			mDrawable.setBounds(bounds);
		}

		@Override
		public void setBounds(int l, int t, int r, int b)
		{
			mDrawable.setBounds(l,t,r,b);
		}

		@NonNull
		@Override
		public Drawable getCurrent()
		{
			return mDrawable.getCurrent();
		}

		@Override
		public boolean setState(@NonNull int[] stateSet)
		{
			return mDrawable.setState(stateSet);
		}

		@Override
		public int getIntrinsicWidth()
		{
			return mDrawable.getIntrinsicWidth();
		}

		@Override
		public int getIntrinsicHeight()
		{
			return mDrawable.getIntrinsicHeight();
		}
	}
}