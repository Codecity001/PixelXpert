package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.hardware.camera2.CameraManager
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintSet
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.StringFormatter
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass.ReflectionConsumer
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools
import sh.siava.pixelxpert.xposed.utils.toolkit.ColorUtils.getColorAttrDefaultColor
import java.lang.ref.WeakReference
import java.util.regex.Pattern

/**
 * Keyguard customisation for SystemUI: a custom carrier text, a line of
 * custom text in the middle of the lock screen, charging watts/volts/amps and
 * temperature appended to the power indication, forced AOD while charging,
 * user-controlled keyguard and wallpaper dim amounts, a hidden user avatar,
 * transparent bottom shortcut buttons on both the classic view path and the
 * Compose shortcut path, an animated torch, and a suppressed unlock hint
 * animation.
 *
 * Every preference is read up front in [onPreferenceUpdated] into fields the
 * hooks installed by [onPackageLoaded] consult on each callback, so no hook
 * ever touches the preference store on a hot path. The pack also owns
 * [ControlledLaunchableImageViewBackgroundDrawable], which wraps the shortcut
 * buttons' backgrounds so the transparency preference can retint them when the
 * framework draws them, rather than the buttons being rebuilt.
 */
@SystemUIModPack
class KeyguardMods(context: Context) : XposedModPack(context) {

	companion object {
		const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
		const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"
		const val EXTRA_TEMPERATURE = "temperature"

		private val WALLPAPER_DIM_AMOUNT_DIMMED: Any = 0.6F // DefaultDeviceEffectsApplier
		private val isRenderingShortcut: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

		private var ShowChargingInfo = false

		private var customCarrierTextEnabled = false
		private var customCarrierText = ""
		private var carrierTextController: Any? = null

		// `onViewAttached` re-runs the init hook every time SystemUI attaches the
		// controller, so without this the same before-hook lands on
		// `updateCarrierInfo` once per attach.
		private var isCarrierCallbackHooked = false

		private var KGMiddleCustomText = ""

		private var DisableUnlockHintAnimation = false

		@JvmStatic
		var KeyGuardDimAmount = -1f
		private var TemperatureUnitF = false

		private var transparentBGcolor = false

		private var ForceAODwCharging = false

		private var AnimateFlashlight = false
	}

	private var max_charging_current = 0f
	private var max_charging_voltage = 0f
	private var temperature = 0f

	internal val carrierStringFormatter = StringFormatter()
	internal val clockStringFormatter = StringFormatter()
	private var mComposeKGMiddleCustomTextView: TextView? = null
	private var mKeyguardRootView: ViewGroup? = null
	private var mColorExtractor: Any? = null
	private var mDozing = false
	private var mSupportsDarkText = false
	private var batteryStatusCallback: BatteryDataProvider.BatteryStatusCallback? = null

	private var HideLockScreenUserAvatar = false

	override fun onPreferenceUpdated(vararg key: String?) {
		DisableUnlockHintAnimation = Xprefs.getBoolean("DisableUnlockHintAnimation", false)

		KGMiddleCustomText = Xprefs.getString("KGMiddleCustomText", "")!!

		customCarrierTextEnabled = Xprefs.getBoolean("carrierTextMod", false)
		customCarrierText = Xprefs.getString("carrierTextValue", "")!!

		ShowChargingInfo = Xprefs.getBoolean("ShowChargingInfo", false)
		TemperatureUnitF = Xprefs.getBoolean("TemperatureUnitF", false)

		HideLockScreenUserAvatar = Xprefs.getBoolean("HideLockScreenUserAvatar", false)

		ForceAODwCharging = Xprefs.getBoolean("ForceAODwCharging", false)

		KeyGuardDimAmount = Xprefs.getSliderFloat("KeyGuardDimAmount", -1f) / 100f

		transparentBGcolor = Xprefs.getBoolean("KeyguardBottomButtonsTransparent", false)

		AnimateFlashlight = Xprefs.getBoolean("AnimateFlashlight", false)

		if (key.size > 0) {
			when (key[0]) {
				"KGMiddleCustomText" -> updateMiddleTexts()

				"carrierTextValue", "carrierTextMod" -> if (customCarrierTextEnabled) {
					setCarrierText()
				} else {
					try {
						var manager: Any? = null
						try {
							manager = getObjectField(carrierTextController, "mCarrierTextManager")
						} catch (t: Throwable) {
							manager = getObjectField(carrierTextController, "carrierTextManager")
						}
						callMethod<Any?>(manager, "updateCarrierText")
					} catch (ignored: Throwable) {
					} // probably not initiated yet
				}
			}
		}
	}

	@SuppressLint("DiscouragedApi", "DefaultLocale")
	override fun onUnloaded() {
		batteryStatusCallback?.let { BatteryDataProvider.unRegisterStatusCallback(it) }
		batteryStatusCallback = null
		carrierStringFormatter.release()
		clockStringFormatter.release()
	}

	override fun onSaveHotReloadState(outState: MutableMap<String, Any?>) {
		mKeyguardRootView?.takeIf { it.isAttachedToWindow }?.let { outState["keyguardRootView"] = it }
		mComposeKGMiddleCustomTextView?.let { outState["middleTextId"] = it.id }
		mColorExtractor?.let { outState["colorExtractor"] = it }
		outState["dozing"] = mDozing
		outState["supportsDarkText"] = mSupportsDarkText
	}

	override fun onRestoreHotReloadState(savedState: Map<String, Any?>) {
		val root = savedState["keyguardRootView"] as? ViewGroup
		mKeyguardRootView = root
		mColorExtractor = savedState["colorExtractor"]
		mDozing = savedState["dozing"] as? Boolean ?: false
		mSupportsDarkText = savedState["supportsDarkText"] as? Boolean ?: false

		if (root != null) {
			(savedState["middleTextId"] as? Int)?.let { id ->
				root.findViewById<View>(id)?.let { root.removeView(it) }
			}
			createMiddleTextViews()
			updateMiddleTexts()
			setMiddleColor()
		}
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val CarrierTextControllerClass = ReflectedClass.of("com.android.keyguard.CarrierTextController")
		val KeyguardIndicationControllerClass = ReflectedClass.of(
			"com.android.systemui.statusbar.KeyguardIndicationController",
		)
		val ScrimControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimController")
		val ScrimStateEnum = ReflectedClass.of("com.android.systemui.statusbar.phone.ScrimState")
		val KeyguardStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.KeyguardStatusBarView")
		val CentralSurfacesImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.CentralSurfacesImpl")
		val NotificationPanelViewControllerClass = ReflectedClass.of(
			"com.android.systemui.shade.NotificationPanelViewController",
		) // used to launch camera
		val AmbientDisplayConfigurationClass = ReflectedClass.of("android.hardware.display.AmbientDisplayConfiguration")
		val DefaultShortcutsSectionClass = ReflectedClass.of(
			"com.android.systemui.keyguard.ui.view.layout.sections.DefaultShortcutsSection",
		)
		val SmartspaceSectionClass = ReflectedClass.of(
			"com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection",
		)
		val DefaultNotificationStackScrollLayoutSectionClass = ReflectedClass.of(
			"com.android.systemui.keyguard.ui.view.layout.sections.DefaultNotificationStackScrollLayoutSection",
		)
		val KeyguardIndicationControllerGoogleClass = ReflectedClass.of(
			"com.google.android.systemui.statusbar.KeyguardIndicationControllerGoogle",
		)
		val ShortcutElementProviderClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.keyguard.ui.composable.elements.ShortcutElementProvider",
		)
		val NotificationShadeWindowViewClass = ReflectedClass.of("com.android.systemui.shade.NotificationShadeWindowView")
		val BackgroundKtClass = ReflectedClass.of("androidx.compose.foundation.BackgroundKt")
		val IconKtClass = ReflectedClass.of("androidx.compose.material3.IconKt")
		val KeyguardQuickAffordanceViewBinderClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.keyguard.ui.binder.KeyguardQuickAffordanceViewBinder",
		)
		val KeyguardQuickAffordanceViewClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.keyguard.ui.view.KeyguardQuickAffordanceView",
		)

		NotificationShadeWindowViewClass
			.after("onAttachedToWindow")
			.run { param ->
				val thisView: ViewGroup = param.getThisObject()
				ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
					thisView.findViewById(SystemUtils.idOf("start_button")),
				)
				ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
					thisView.findViewById(SystemUtils.idOf("end_button")),
				)
			}

		ShortcutElementProviderClass
			.before("KeyguardShortcut")
			.run { param -> isRenderingShortcut.set(true) }

		ShortcutElementProviderClass
			.after("KeyguardShortcut")
			.run { param -> isRenderingShortcut.set(false) }

		BackgroundKtClass
			.before(Pattern.compile("background.*|m.*background.*"))
			.run { param ->
				//noinspection DataFlowIssue
				if (transparentBGcolor && isRenderingShortcut.get() == true && param.args.size == 3 && param.args[1] is Long) {
					param.args[1] = 0L // Color.Transparent
				}
			}

		IconKtClass
			.before(Pattern.compile("Icon.*|m.*Icon.*"))
			.run { param ->
				//noinspection DataFlowIssue
				if (transparentBGcolor && isRenderingShortcut.get() == true && param.args.size >= 4 && param.args[3] is Long) {
					val wallpaperTextColorAccent = getColorAttrDefaultColor(
						mContext,
						mContext.resources.getIdentifier("wallpaperTextColorAccent", "attr", mContext.packageName),
					)
					param.args[3] = (wallpaperTextColorAccent.toLong() shl 32)
				}
			}

		KeyguardQuickAffordanceViewBinderClass
			.after("bind").run { param -> ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(param.getArg(0)) }

		KeyguardQuickAffordanceViewClass
			.afterConstruction()
			.run { param -> ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(param.getThisObject()) }

		KeyguardQuickAffordanceViewClass
			.after("setBackground")
			.run { param -> ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(param.getThisObject()) }

		ReflectedClass.of(CameraManager::class.java)
			.before("setTorchMode")
			.run { param ->
				if (SystemUtils.isInternalTorchCall) return@run
				if (AnimateFlashlight) {
					SystemUtils.setFlash(param.args[1] as Boolean, true)
					param.setResult(null)
				}
			}

		ReflectedClass.of(CameraManager::class.java)
			.before("turnOnTorchWithStrengthLevel")
			.run { param ->
				if (SystemUtils.isInternalTorchCall) return@run
				if (AnimateFlashlight) {
					val level = param.args[1] as Int
					if (!SystemUtils.isFlashOn()) {
						SystemUtils.setFlash(true, level, true)
						param.setResult(null)
					}
				}
			}

		DefaultShortcutsSectionClass
			.after("addViews")
			.run { param ->
				val res: Resources = mContext.resources

				ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
					callMethod(
						param.args[0],
						"findViewById",
						res.getIdentifier(
							"end_button",
							"id",
							mContext.packageName,
						),
					),
				)

				ControlledLaunchableImageViewBackgroundDrawable.captureDrawable(
					callMethod(
						param.args[0],
						"findViewById",
						res.getIdentifier(
							"start_button",
							"id",
							mContext.packageName,
						),
					),
				)
			}

		DefaultNotificationStackScrollLayoutSectionClass
			.after("applyConstraints")
			.run { param ->
				val middleTextView = mComposeKGMiddleCustomTextView ?: return@run
				val constraintSet = param.args[0]

				callMethod<Any?>(
					constraintSet,
					"connect",
					middleTextView.id,
					ConstraintSet.BOTTOM,
					SystemUtils.idOf("nssl_placeholder"),
					ConstraintSet.TOP,
				)
			}

		SmartspaceSectionClass
			.after("addViews")
			.run(this::run)

		try {
			ReflectedClass.of("com.android.systemui.keyguard.ui.view.KeyguardRootView")
				.after("<init>")
				.run { param ->
					val view = param.thisObject as View
					view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
						override fun onViewAttachedToWindow(v: View) {
							mKeyguardRootView = v as ViewGroup
							createMiddleTextViews()
							updateMiddleTexts()
						}

						override fun onViewDetachedFromWindow(v: View) {}
					})
				}
		} catch (t: Throwable) {
		}

		AmbientDisplayConfigurationClass
			.after("alwaysOnEnabled")
			.run { param ->
				if (ForceAODwCharging) {
					param.setResult(param.getResult<Boolean>() || BatteryDataProvider.isCharging())
				}
			}

		NotificationPanelViewControllerClass
			.before("startUnlockHintAnimation")
			.run { param ->
				if (DisableUnlockHintAnimation) param.setResult(null)
			}

		// needed to extract wallpaper colors and capabilities. This is a SysUIColorExtractor
		CentralSurfacesImplClass
			.afterConstruction()
			.run { param -> mColorExtractor = getObjectField(param.thisObject, "mColorExtractor") }

		KeyguardStatusBarViewClass
			.after("updateVisibilities")
			.run { param ->
				val mMultiUserAvatar: View = getObjectField(param.thisObject, "mMultiUserAvatar")
				val mIsUserSwitcherEnabled = getBooleanField(param.thisObject, "mIsUserSwitcherEnabled")
				mMultiUserAvatar.setVisibility(
					if (!HideLockScreenUserAvatar && mIsUserSwitcherEnabled) {
						View.VISIBLE
					} else {
						View.GONE
					},
				)
			}

		KeyguardIndicationControllerGoogleClass
			.after("computePowerIndication")
			.run { param ->
				if (ShowChargingInfo) {
					val result: String = param.getResult()

					val shownTemperature: Float = if (TemperatureUnitF) {
						(temperature * 1.8f) + 32f
					} else {
						temperature
					}

					param.setResult(
						String.format(
							"%s\n%.1fW (%.1fV, %.1fA) • %.0fº%s",
							result,
							max_charging_current * max_charging_voltage,
							max_charging_voltage,
							max_charging_current,
							shownTemperature,
							if (TemperatureUnitF) {
								"F"
							} else {
								"C"
							},
						),
					)
				}
			}

		val statusCallback =
			object : BatteryDataProvider.BatteryStatusCallback {
				override fun onBatteryStatusChanged(batteryStatus: Int, batteryStatusIntent: Intent) {
					max_charging_current = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, 0) / 1000000f
					max_charging_voltage = batteryStatusIntent.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, 0) / 1000000f
					temperature = batteryStatusIntent.getIntExtra(EXTRA_TEMPERATURE, 0) / 10f
				}
			}
		batteryStatusCallback = statusCallback
		BatteryDataProvider.registerStatusCallback(statusCallback)

		ScrimControllerClass
			.before(Pattern.compile("scheduleUpdate.*"))
			.run { param ->
				if (KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1) return@run

				setObjectField(param.thisObject, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount)
				val constants = ScrimStateEnum.clazz!!.enumConstants
				for (constant in constants) {
					setObjectField(constant, "mScrimBehindAlphaKeyguard", KeyGuardDimAmount)
				}
			}

		ReflectedClass.of(WallpaperManager::class.java)
			.after("getWallpaperDimAmount")
			.run { param ->
				if ((KeyGuardDimAmount < 0 || KeyGuardDimAmount > 1) ||
					param.getResult<Any>().equals(WALLPAPER_DIM_AMOUNT_DIMMED)
				) {
					return@run
				}

				// ref ColorUtils.compositeAlpha - Since KEYGUARD_SCRIM_ALPHA = .2f, we need to range the result between -70 and 255 to get the correct value when composed with 20% - we use 60 to cover float inaccuracies and never see a negative final result
				param.setResult((325 * KeyGuardDimAmount - 60) / 255)
			}

		carrierStringFormatter.registerCallback(object : StringFormatter.FormattedStringCallback {
			override fun onRefreshNeeded() {
				setCarrierText()
			}
		})

		val carrierInitHook = ReflectionConsumer { param ->
			carrierTextController = param.thisObject
			var carrierTextCallback: Any? = null
			try {
				carrierTextCallback = getObjectField(carrierTextController, "mCarrierTextCallback")
			} catch (t: Throwable) {
				carrierTextCallback = getObjectField(carrierTextController, "carrierTextCallback")
			}
			setCarrierText()
			if (!isCarrierCallbackHooked && carrierTextCallback != null) {
				ReflectedClass.of(carrierTextCallback.javaClass)
					.before("updateCarrierInfo")
					.run { param1 ->
						if (customCarrierTextEnabled) param1.setResult(null)
					}
				isCarrierCallbackHooked = true
			}
		}

		CarrierTextControllerClass.after("onInit").run(carrierInitHook)
		CarrierTextControllerClass.after("onViewAttached").run(carrierInitHook)

		// a way to know when the device goes to AOD/dozing
		KeyguardIndicationControllerClass
			.after("updateDeviceEntryIndication")
			.run { param ->
				if (mDozing != getObjectField<Boolean>(param.thisObject, "mDozing")) {
					mDozing = !mDozing
					setMiddleColor()
				}
			}
	}

	private fun createMiddleTextViews() {
		var middleTextView = mComposeKGMiddleCustomTextView
		if (middleTextView == null) {
			middleTextView =
				TextView(mContext).apply {
					layoutParams =
						LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
					maxLines = 2
					setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
					setLetterSpacing(.03f)
					id = View.generateViewId()
					z = 100f
				}
			mComposeKGMiddleCustomTextView = middleTextView
		}
	}

	private fun setMiddleColor() {
		val middleTextView = mComposeKGMiddleCustomTextView ?: return

		if (mColorExtractor != null) {
			val colors: Any? = callMethod<Any?>(mColorExtractor, "getColors", WallpaperManager.FLAG_LOCK)
			mSupportsDarkText = callMethod(colors, "supportsDarkText")
		}
		val color = if (mDozing || !mSupportsDarkText) Color.WHITE else Color.BLACK

		try {
			middleTextView.setShadowLayer(
				1f,
				1f,
				1f,
				if (color ==
					Color.BLACK
				) {
					Color.TRANSPARENT
				} else {
					Color.BLACK
				},
			) // shadow only for white color
			middleTextView.setTextColor(color)
		} catch (ignored: Throwable) {
		}
	}

	private fun setCarrierText() {
		if (!customCarrierTextEnabled) return

		try {
			val mView: TextView = getObjectField(carrierTextController, "mView")
			mView.post { mView.text = carrierStringFormatter.formatString(customCarrierText) }
		} catch (ignored: Throwable) {
		} // probably not initiated yet
	}

	private fun updateMiddleTexts() {
		var text: CharSequence? = null
		if (!KGMiddleCustomText.isEmpty()) {
			text = clockStringFormatter.formatString(KGMiddleCustomText)
		}

		try {
			setMiddleTextCompose(text)
		} catch (ignored: Throwable) {
		}
	}

	private fun setMiddleTextCompose(text: CharSequence?) {
		val root = mKeyguardRootView ?: return
		val middleTextView = mComposeKGMiddleCustomTextView ?: return

		root.post {
			if (text == null) {
				root.removeView(middleTextView)
			} else {
				ReflectionTools.reAddView(root, middleTextView)
				middleTextView.text = text
			}
		}
	}

	private fun run(param: HookHelper.RunParam) {
		try {
			createMiddleTextViews()

			try {
				val smartSpaceContainer: LinearLayout = getObjectField(param.thisObject, "dateView")
				mKeyguardRootView = smartSpaceContainer.rootView.findViewById(SystemUtils.idOf("keyguard_root_view"))
			} catch (ignored: Throwable) {
			}

			updateMiddleTexts()
			setMiddleColor()
		} catch (ignored: Throwable) {
		}
	}

	class ControlledLaunchableImageViewBackgroundDrawable(drawable: Drawable, parentView: ImageView?) :
		DrawableWrapper(drawable) {
		private val wrapped: Drawable get() = requireNotNull(drawable)
		private val context: Context = parentView!!.context
		private val parentImageViewReference = WeakReference(parentView)

		companion object {
			@JvmStatic
			fun captureDrawable(imageView: ImageView?) {
				if (imageView == null) return
				try {
					var background = imageView.getBackground()
					if (background == null || background is ControlledLaunchableImageViewBackgroundDrawable) return

					background = ControlledLaunchableImageViewBackgroundDrawable(background.getCurrent().mutate(), imageView)
					imageView.setBackground(background)
				} catch (ignored: Throwable) {
				}
			}
		}

		override fun mutate(): Drawable {
			wrapped.mutate()
			return this
		}

		override fun setTint(tintColor: Int) {
			if (!transparentBGcolor) {
				wrapped.setTint(tintColor)
			} else {
				wrapped.setTint(Color.TRANSPARENT)
			}
		}

		override fun setTintList(tintList: ColorStateList?) {
			if (transparentBGcolor) {
				wrapped.setTint(Color.TRANSPARENT)

				val parentView = parentImageViewReference.get()
				if (parentView != null && parentView.getDrawable() != null) {
					@SuppressLint("DiscouragedApi")
					val wallpaperTextColorAccent = getColorAttrDefaultColor(
						context,
						context.resources.getIdentifier("wallpaperTextColorAccent", "attr", context.packageName),
					)

					parentView.getDrawable().setTint(wallpaperTextColorAccent)
				}
			} else {
				wrapped.setTintList(tintList)
			}
		}

		override fun setColorFilter(colorFilter: ColorFilter?) {
			if (!transparentBGcolor) {
				wrapped.setColorFilter(colorFilter)
			}
		}
	}
}
