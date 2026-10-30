package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Display
import android.view.DisplayCutout
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.Nullable
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getFloatField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.Reflection.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import org.objenesis.ObjenesisHelper
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.NetworkTraffic
import sh.siava.pixelxpert.xposed.utils.ShyLinearLayout
import sh.siava.pixelxpert.xposed.utils.StringFormatter
import sh.siava.pixelxpert.xposed.utils.StringFormatter.FormattedStringCallback
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.batteryStyles.BatteryBarView
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.getStateFlowImplOf
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools
import java.lang.ref.WeakReference
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Executor
import javax.security.auth.callback.Callback

/**
 * Status-bar content and layout pack: the clock can be moved between left /
 * centre / right (with optional custom text before, after and AM/PM styling),
 * a network-traffic indicator and a battery bar are planted into the bar, the
 * left side is split into rows so notification icons no longer squeeze the
 * clock, and extra icon slots (app switch, VoLTE, VoWi-Fi) are driven straight
 * into SystemUI's icon controller. It also hides the privacy chip and the
 * roaming state, and mirrors the status-bar clock's colour and keyguard
 * visibility to every registered [StatusbarTextColorCallback] /
 * [ClockVisibilityCallback] - which is how [NetworkTraffic] tints itself.
 *
 * Nearly all of its mutable state lives in the [companion] as static
 * members: SystemUI's view tree hands this pack one hierarchy
 * while preference updates and hook callbacks arrive from several directions,
 * and the pack instance itself is process-scoped, so the two are equally
 * long-lived here.
 */
@SystemUIModPack
class StatusbarMods
@SuppressLint("DiscouragedApi")
constructor(context: Context) : XposedModPack(context) {

	companion object {
		//region Clock
		const val POSITION_LEFT = 0
		const val POSITION_CENTER = 1
		const val POSITION_RIGHT = 2
		const val POSITION_LEFT_EXTRA_LEVEL = 3

		private const val AM_PM_STYLE_SMALL = 1
		private const val AM_PM_STYLE_GONE = 2

		/** U+200B, zero width but full line height; keeps a 70% line box full. */
		private const val ZERO_WIDTH_SPACE = "\u200b"

		// The family and weight the Compose clock draws with.
		private const val COMPOSE_CLOCK_FAMILY = "variable-body-medium-emphasized"
		private const val CLOCK_FONT_WEIGHT = 500
		private var isJetpackClock = false

		@JvmStatic
		var isMovingClock = false

		@JvmStatic
		var isReparentingComposeView = false

		private var clockPosition = POSITION_LEFT
		private var clockMultiRowStartOffset = 2
		private var mAmPmStyle = AM_PM_STYLE_GONE
		private var mShowSeconds = false
		private var mStringFormatBefore = ""
		private var mStringFormatAfter = ""
		private var mBeforeSmall = true
		private var mAfterSmall = true
		//endregion

		//region network traffic
		private var networkOnSBEnabled = false
		private var networkTrafficPosition = POSITION_LEFT
		//endregion

		//region battery bar
		private var BBarEnabled = false
		private var BBarColorful = false
		private var BBOnlyWhileCharging = false
		private var BBOnBottom = false
		private var BBSetCentered = false
		private var BBOpacity = 100
		private var BBarHeight = 10
		private var batteryLevels: List<Float> = listOf(20f, 40f)
		private var batteryColors: IntArray = intArrayOf(Color.RED, Color.YELLOW)
		private var chargingColor = Color.WHITE
		private var fastChargingColor = Color.WHITE
		private var powerSaveColor = Color.parseColor("#FFBF00")
		private var indicateCharging = false
		private var indicateFastCharging = false
		private var indicatePowerSave = false
		private var BBarTransitColors = false
		private var BBAnimateCharging = false
		//endregion

		//region privacy chip
		private var HidePrivacyChip = false // works
		//endregion

		//region general use
		private const val PADDING_DEFAULT = -0.5f
		private val clockVisibilityCallbacks = ArrayList<ClockVisibilityCallback>()
		private var notificationAreaMultiRow = false
		private var multiRowClockBottom = false
		private var statusbarHeightFactor = 100
		private var NotificationAODIconLimit = 3
		private var NotificationIconLimit = 4
		private var currentClockColor = 0
		private val mTextColorCallbacks = ArrayList<StatusbarTextColorCallback>()

		// PADDING_DEFAULT from the outset: until the hook has actually read a
		// stored pair there is nothing to enforce, which is also the state the
		// new reset icon puts things back into. Starting at 0 is only safe when
		// androidx preference persists its declared default on first
		// run; this renderer deliberately does not write a range until it is
		// slid, so 0 here would mean "force zero padding" rather than "no
		// opinion" - and the guard below only recognises PADDING_DEFAULT.
		private var SBPaddingStart = PADDING_DEFAULT
		private var SBPaddingEnd = PADDING_DEFAULT
		private var LandscapeSBPaddingStart = PADDING_DEFAULT
		private var LandscapeSBPaddingEnd = PADDING_DEFAULT
		private var mShadeHeaderController: Any? = null
		private var mShadeHeaderView: View? = null
		//endregion

		//region vo_data
		private const val VO_LTE_SLOT = "volte"
		private const val VO_WIFI_SLOT = "vowifi"

		private var VolteIconEnabled = false // works

		private var VowifiIconEnabled = false
		//endregion

		//region combined signal icons
		private var CombineSignalIcons = false
		private var HideRoamingState = false
		const val ICON_HIDE_LIST = "icon_blacklist"
		//endregion

		//region app profile switch
		const val APP_SWITCH_SLOT = "app_switch"

		private var StatusbarAppSwitchIconEnabled = false // works
		//endregion

		//region QS footer build number
		private var qsBuildNumberEnabled = false
		private var qsBuildNumberText = ""
		private var buildNumberInteractor: Any? = null
		private var originalBuildNumberText: String? = null
		//endregion

		@JvmStatic
		@ColorInt
		fun getCurrentClockColor(): Int = currentClockColor

		@JvmStatic
		fun registerTextColorCallback(callback: StatusbarTextColorCallback): Int {
			mTextColorCallbacks.add(callback)
			return currentClockColor
		}

		@JvmStatic
		fun registerClockVisibilityCallback(callback: ClockVisibilityCallback) {
			clockVisibilityCallbacks.add(callback)
		}

		@JvmStatic
		fun unRegisterClockVisibilityCallback(callback: ClockVisibilityCallback) {
			clockVisibilityCallbacks.remove(callback)
		}
	}

	//region Clock
	private val leftClockPadding: Int
	private val rightClockPadding: Int
	private var mJetpackClockView: View? = null
	private var mOngoingChipComposeView: View? = null
	private val mSetHeightsRunnable = Runnable { setHeights() }

	private fun scheduleHeightsUpdate() {
		val sbView = mPhoneStatusbarView ?: return
		sbView.removeCallbacks(mSetHeightsRunnable)
		sbView.post(mSetHeightsRunnable)
	}

	private var mBeforeClockColor: Int? = null
	private var mAfterClockColor: Int? = null
	private var clockColor: Int? = null
	private var clockTextEffect: Int = 0

	// The Compose clock renders the whole time string itself,
	// it is re-parented between these two TextViews.
	private var mClockContainer: LinearLayout? = null
	private var mBeforeClockView: TextView? = null
	private var mAfterClockView: TextView? = null

	// One callback for the whole container
	private var mCompanionFormatCallback: FormattedStringCallback? = null
	private var mBuildNumberFormatCallback: FormattedStringCallback? = null

	// Mirrors SystemUI's own clock visibility
	private var mStatusBarClockVisible = true

	// Schedule own refresh since compose clock can't handle it
	private var mCompanionTickScheduled = false
	private val mCompanionTick = object : Runnable {
		override fun run() {
			mCompanionTickScheduled = false
			updateClockCompanionViews()
			scheduleCompanionTick()
		}
	}
	//endregion

	init {
		rightClockPadding =
			mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_clock_starting_padding"))
		leftClockPadding =
			mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_left_clock_end_padding"))
	}

	//region network traffic
	private var networkTrafficSB: NetworkTraffic? = null
	//endregion

	//region general use
	private var mActivityStarter: Any? = null
	private var AODNIC: Any? = null
	private var SBNIC: Any? = null
	private var mStatusbarStartSide: ViewGroup? = null
	private var mCenteredIconArea: View? = null
	private var mSystemIconArea: LinearLayout? = null

	private var mClockView: TextView? = null
	private var mNotificationIconContainer: ViewGroup? = null
	private var mNotificationIconArea: ViewGroup? = null
	internal var mNotificationContainerContainer: LinearLayout? = null
	private var mLeftVerticalSplitContainer: FrameLayout? = null
	private var mLeftExtraRowContainer: LinearLayout? = null
	private var mPhoneStatusbarView: FrameLayout? = null
	private var mStatusBarContents: View? = null
	private var mLastAppliedStatusBarHeight = 0
	//endregion

	//region vo_data
	private val voDataExec: Executor = Executor { it.run() }

	private var mStatusBarIconController: Any? = null
	private var mCommandQueue: Any? = null
	private var isLegacyStatusBar = false

	private var StatusBarIconClass: ReflectedClass? = null
	private var StatusBarIconHolderClass: ReflectedClass? = null
	private var volteStatusbarIcon: Any? = null
	private var volteStatusbarIconHolder: Any? = null
	private var telephonyCallbackRegistered = false
	private var lastVolteAvailable = false
	private val voDataCallback = serverStateCallback()

	private var vowifiStatusbarIcon: Any? = null
	private var vowifiStatusbarIconHolder: Any? = null
	private var lastVowifiAvailable = false
	//endregion

	//region combined signal icons
	private var mWifiVisible = false
	private var mTunerService: Any? = null
	//endregion

	//region app profile switch
	private var mAppSwitchStatusbarIcon: Any? = null
	private var mAppSwitchStatusbarIconHolder: Any? = null

	private val mAppProfileSwitchReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context?, intent: Intent) {
			if (Constants.ACTION_PROFILE_SWITCH_AVAILABLE == intent.action) {
				val isAvailable = intent.getBooleanExtra("available", false)
				if (isAvailable && StatusbarAppSwitchIconEnabled) {
					if (mAppSwitchStatusbarIcon == null) {
						initSwitchIcon()
					}
					setSBIcon(APP_SWITCH_SLOT, mAppSwitchStatusbarIconHolder, mAppSwitchStatusbarIcon)
				} else {
					removeSBIconSlot(APP_SWITCH_SLOT)
				}
			}
		}
	}
	//endregion

	private fun initSwitchIcon() {
		try {
			val appSwitchIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_app_switch)

			mAppSwitchStatusbarIcon = getStatusbarIconFor(appSwitchIcon, APP_SWITCH_SLOT)

			mAppSwitchStatusbarIconHolder = getStatusbarIconHolderFor(mAppSwitchStatusbarIcon)
		} catch (ignored: Throwable) {
		}
	}

	@Suppress("SENSELESS_COMPARISON")
	override fun onPreferenceUpdated(vararg key: String?) {
		StatusbarAppSwitchIconEnabled = Xprefs.getBoolean("StatusbarAppSwitchIconEnabled", false)

		HidePrivacyChip = Xprefs.getBoolean("HidePrivacyChip", false)

		HideRoamingState = Xprefs.getBoolean("HideRoamingState", false)

		qsBuildNumberEnabled = Xprefs.getBoolean("QSFooterBuildNumberEnabled", false)
		qsBuildNumberText = Xprefs.getString("QSFooterBuildNumberText", "") ?: ""

		CombineSignalIcons = Xprefs.getBoolean("combinedSignalEnabled", false)
		wifiVisibleChanged()

		if (key.isNotEmpty() && key[0] == "notificationAreaMultiRow") {
			val newnotificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false)
			if (newnotificationAreaMultiRow != notificationAreaMultiRow) {
				SystemUtils.killSelf()
			}
		}
		if (key.isNotEmpty() && key[0] == "multiRowClockBottom") {
			val newMultiRowClockBottom = Xprefs.getBoolean("multiRowClockBottom", false)
			if (newMultiRowClockBottom != multiRowClockBottom) {
				SystemUtils.killSelf()
			}
		}
		notificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false)
		multiRowClockBottom = Xprefs.getBoolean("multiRowClockBottom", false)
		statusbarHeightFactor = Xprefs.getSliderInt("statusbarHeightFactor", 100)

		try {
			NotificationIconLimit = Integer.parseInt(Xprefs.getString("NotificationIconLimit", "")!!.trim())
		} catch (ignored: Throwable) {
			NotificationIconLimit = getIntegerResource("max_notif_static_icons", 4)
		}

		try {
			NotificationAODIconLimit = Integer.parseInt(Xprefs.getString("NotificationAODIconLimit", "")!!.trim())
		} catch (ignored: Throwable) {
			NotificationAODIconLimit = getIntegerResource("max_notif_icons_on_aod", 3)
		}

		if (AODNIC != null) {
			setObjectField(AODNIC, "maxIcons", NotificationAODIconLimit)
		}
		if (SBNIC != null) {
			setObjectField(SBNIC, "maxIcons", NotificationIconLimit)
		}

		val paddings = Xprefs.getSliderValues("statusbarPaddings", 0f)

		if (paddings.size > 1) {
			SBPaddingStart = paddings[0]
			SBPaddingEnd = 100f - paddings[1]
		} else {
			SBPaddingStart = PADDING_DEFAULT
			SBPaddingEnd = PADDING_DEFAULT
		}

		val qsPaddings = Xprefs.getSliderValues("qsStatusbarPaddings", 0f)

		if (qsPaddings.size > 1) {
			LandscapeSBPaddingStart = qsPaddings[0]
			LandscapeSBPaddingEnd = 100f - qsPaddings[1]
		} else {
			LandscapeSBPaddingStart = PADDING_DEFAULT
			LandscapeSBPaddingEnd = PADDING_DEFAULT
		}

		//region BatteryBar Settings
		BBarEnabled = Xprefs.getBoolean("BBarEnabled", false)
		BBarColorful = Xprefs.getBoolean("BBarColorful", false)
		BBOnlyWhileCharging = Xprefs.getBoolean("BBOnlyWhileCharging", false)
		BBOnBottom = Xprefs.getBoolean("BBOnBottom", false)
		BBSetCentered = Xprefs.getBoolean("BBSetCentered", false)
		BBOpacity = Xprefs.getSliderInt("BBOpacity", 100)
		BBarHeight = Xprefs.getSliderInt("BBarHeight", 50)
		BBarTransitColors = Xprefs.getBoolean("BBarTransitColors", false)
		BBAnimateCharging = Xprefs.getBoolean("BBAnimateCharging", false)

		batteryLevels = Xprefs.getSliderValues("batteryWarningRange", 0f)

		batteryColors = intArrayOf(
			Xprefs.getInt("batteryCriticalColor", Color.RED),
			Xprefs.getInt("batteryWarningColor", Color.YELLOW),
		)

		indicateFastCharging = Xprefs.getBoolean("indicateFastCharging", false)
		indicatePowerSave = Xprefs.getBoolean("indicatePowerSave", false)
		indicateCharging = Xprefs.getBoolean("indicateCharging", true)

		chargingColor = Xprefs.getInt("batteryChargingColor", Color.GREEN)
		fastChargingColor = Xprefs.getInt("batteryFastChargingColor", Color.BLUE)
		powerSaveColor = Xprefs.getInt("batteryPowerSaveColor", Color.parseColor("#FFBF00"))

		if (BBarEnabled) {
			placeBatteryBar()
		}

		if (BatteryBarView.hasInstance()) {
			refreshBatteryBar(BatteryBarView.getInstance()!!)
		}
		//endregion BatteryBar Settings

		//region network Traffic settings
		networkOnSBEnabled = Xprefs.getBoolean("networkOnSBEnabled", false)
		val networkTrafficModeStr = Xprefs.getString("networkTrafficMode", "0") ?: "0"
		val networkTrafficMode = networkTrafficModeStr.toIntOrNull() ?: 0

		val networkTrafficRXTop = Xprefs.getBoolean("networkTrafficRXTop", true)
		val networkTrafficColorful = Xprefs.getBoolean("networkTrafficColorful", false)
		val networkTrafficDLColor = Xprefs.getInt("networkTrafficDLColor", Color.GREEN)
		val networkTrafficULColor = Xprefs.getInt("networkTrafficULColor", Color.RED)
		val networkTrafficOpacity = Xprefs.getSliderInt("networkTrafficOpacity", 100)
		val networkTrafficInterval = Xprefs.getSliderInt("networkTrafficInterval", 1)
		val networkTrafficShowIcons = Xprefs.getBoolean("networkTrafficShowIcons", true)
		val networkTrafficShowInBits = Xprefs.getBoolean("networkTrafficShowInBits", false)

		if (networkOnSBEnabled) {
			networkTrafficPosition = Xprefs.getString("networkTrafficPosition", POSITION_RIGHT.toString())?.toIntOrNull() ?: POSITION_RIGHT
			if (networkTrafficPosition == POSITION_LEFT_EXTRA_LEVEL) {
				Xprefs.edit().putString("networkTrafficPosition", POSITION_LEFT.toString()).apply()
				networkTrafficPosition = POSITION_LEFT
			}

			val thresholdText = Xprefs.getString("networkTrafficThreshold", "10")

			val networkTrafficThreshold: Int = try {
				Math.round(java.lang.Float.parseFloat(thresholdText))
			} catch (ignored: Exception) {
				10
			}
			val networkTrafficShadow = try {
				if (networkTrafficColorful) {
					Integer.parseInt(Xprefs.getString("networkTrafficShadow", "0"))
				} else {
					0
				}
			} catch (_: Throwable) {
				0
			}
			NetworkTraffic.setConstants(
				networkTrafficInterval,
				networkTrafficThreshold,
				networkTrafficMode,
				networkTrafficRXTop,
				networkTrafficColorful,
				networkTrafficDLColor,
				networkTrafficULColor,
				networkTrafficOpacity,
				networkTrafficShowIcons,
				networkTrafficShowInBits,
				networkTrafficShadow,
			)
			networkTrafficSB = NetworkTraffic.getInstance(mContext, true)
			networkTrafficSB!!.update()
		}
		placeNTSB()
		//endregion network settings

		//region clock settings
		clockPosition = Integer.parseInt(Xprefs.getString("SBClockLoc", POSITION_LEFT.toString()))
		if (clockPosition == POSITION_LEFT_EXTRA_LEVEL) {
			Xprefs.edit().putString("SBClockLoc", POSITION_LEFT.toString()).apply()
			clockPosition = POSITION_LEFT
		}

		mShowSeconds = Xprefs.getBoolean("SBCShowSeconds", false)
		mAmPmStyle = Integer.parseInt(Xprefs.getString("SBCAmPmStyle", AM_PM_STYLE_GONE.toString()))

		mStringFormatBefore = Xprefs.getString("DateFormatBeforeSBC", "")!!
		mStringFormatAfter = Xprefs.getString("DateFormatAfterSBC", "")!!
		mBeforeSmall = Xprefs.getBoolean("BeforeSBCSmall", true)
		mAfterSmall = Xprefs.getBoolean("AfterSBCSmall", true)
		clockMultiRowStartOffset = Xprefs.getSliderInt("clockMultiRowStartOffset", 2)
		clockTextEffect = try {
			if (Xprefs.getBoolean("SBCClockColorful", false)) {
				Integer.parseInt(Xprefs.getString("SBCClockTextEffect", "0"))
			} else {
				0
			}
		} catch (_: Throwable) {
			0
		}

		if (Xprefs.getBoolean("SBCClockColorful", false)) {
			clockColor = Xprefs.getInt("SBCClockColor", Color.WHITE)
			mBeforeClockColor = Xprefs.getInt("SBCBeforeClockColor", Color.WHITE)
			mAfterClockColor = Xprefs.getInt("SBCAfterClockColor", Color.WHITE)
		} else {
			clockColor = null
			mBeforeClockColor = null
			mAfterClockColor = null
		}

		if ((mStringFormatBefore + mStringFormatAfter).trim().isEmpty()) {
			val SBCDayOfWeekMode = Integer.parseInt(Xprefs.getString("SBCDayOfWeekMode", "0"))

			when (SBCDayOfWeekMode) {
				0 -> {
					mStringFormatAfter = ""
					mStringFormatBefore = ""
				}

				1 -> {
					mStringFormatBefore = "\$GEEE "
					mStringFormatAfter = ""
					mBeforeSmall = false
				}

				2 -> {
					mStringFormatBefore = "\$GEEE "
					mStringFormatAfter = ""
					mBeforeSmall = true
				}

				3 -> {
					mStringFormatBefore = ""
					mStringFormatAfter = " \$GEEE"
					mAfterSmall = false
				}

				4 -> {
					mStringFormatBefore = ""
					mStringFormatAfter = " \$GEEE"
					mAfterSmall = true
				}
			}
		}

		if (mContext != null && isJetpackClock) {
			try {
				val current = android.provider.Settings.Secure.getInt(mContext.contentResolver, "clock_seconds", 0)
				val desired = if (mShowSeconds) 1 else 0
				if (current != desired) {
					android.provider.Settings.Secure.putInt(mContext.contentResolver, "clock_seconds", desired)
				}
			} catch (t: Throwable) {
			}
		}

		try {
			placeClock()
			updateClock()
		} catch (ignored: Throwable) {
		}
		//endregion clock settings

		//region vo_data
		VolteIconEnabled = Xprefs.getBoolean("VolteIconEnabled", false)
		VowifiIconEnabled = Xprefs.getBoolean("VowifiIconEnabled", false)
		//endregion

		if (key.isNotEmpty()) {
			when (key[0]) {
				"statusbarPaddings" -> updateStatusbarHeight()

				"QSFooterBuildNumberEnabled", "QSFooterBuildNumberText" -> updateQSFooterBuildNumber()

				"qsStatusbarPaddings" -> {
					updateShadeHeaderPadding()
					updateStatusbarHeight()
				}

				"NotificationIconLimit" -> applyNotificationIconLimit()

				"VolteIconEnabled", "VowifiIconEnabled" -> {
					if (VolteIconEnabled || VowifiIconEnabled) {
						initVoData()

						if (!VolteIconEnabled) removeSBIconSlot(VO_LTE_SLOT)
						if (!VowifiIconEnabled) removeSBIconSlot(VO_WIFI_SLOT)
					} else {
						removeVoDataCallback()
					}
				}
			}
		}
	}

	private fun captureBuildNumberInteractor(interactor: Any) {
		if (buildNumberInteractor != null) return
		val original = try {
			getObjectField(interactor, "buildText") as? String
		} catch (ignored: Throwable) {
			null
		} ?: return
		buildNumberInteractor = interactor
		originalBuildNumberText = original
		updateQSFooterBuildNumber()
	}

	private fun registerBuildNumberFormatCallback() {
		if (mBuildNumberFormatCallback != null) return
		val callback = object : FormattedStringCallback {
			override fun onRefreshNeeded() {
				runOnMainThread { updateQSFooterBuildNumber() }
			}
		}
		buildNumberStringFormatter.registerCallback(callback)
		mBuildNumberFormatCallback = callback
	}

	/**
	 * Updates `BuildNumberInteractor.buildText` with custom or default text.
	 *
	 * Notifying `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED` signals SystemUI's
	 * `DevelopmentSettingRepository` to recompose immediately.
	 */
	private fun updateQSFooterBuildNumber() {
		val interactor = buildNumberInteractor ?: return
		val original = originalBuildNumberText ?: return
		val text = if (qsBuildNumberEnabled && qsBuildNumberText.isNotEmpty()) {
			buildNumberStringFormatter.formatString(qsBuildNumberText).toString()
		} else {
			original
		}
		try {
			setObjectField(interactor, "buildText", text)
			mContext.contentResolver.notifyChange(
				Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
				null,
			)
		} catch (ignored: Throwable) {
		}
	}

	@SuppressLint("DiscouragedApi")
	private fun getIntegerResource(resourceName: String, defaultValue: Int): Int {
		try {
			return mContext.resources.getInteger(SystemUtils.resourceIdOf(resourceName, "integer"))
		} catch (ignored: Throwable) {
			return defaultValue
		}
	}

	private fun updateClock() {
		runOnMainThread {
			updateClockColor()
			if (isJetpackClock) {
				updateClockCompanionViews()
				scheduleCompanionTick(restart = true)
				return@runOnMainThread
			}
			try {
				callMethod(getObjectField(mClockView, "mCalendar"), "setTimeInMillis", System.currentTimeMillis())
				mClockView!!.text = callMethod(mClockView, "getSmallTime") as CharSequence
				applyClockTextEffect(mClockView)
				mClockView!!.invalidate()
				mClockContainer?.invalidate()
			} catch (ignored: Throwable) {
			}
		}
	}

	@SuppressLint("DiscouragedApi")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		StatusBarIconClass = ReflectedClass.of("com.android.internal.statusbar.StatusBarIcon")
		StatusBarIconHolderClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconHolder")
		isLegacyStatusBar =
			ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder",
			).clazz !=
			null

		onPreferenceUpdated()
		val filter = IntentFilter()
		filter.addAction(Constants.ACTION_PROFILE_SWITCH_AVAILABLE)
		mContext.registerReceiver(mAppProfileSwitchReceiver, filter, Context.RECEIVER_EXPORTED)

		//region needed classes
		val ClockClass = ReflectedClass.of("com.android.systemui.statusbar.policy.Clock")
		val PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")
		val NotificationIconContainerClass = ReflectedClass.of(
			"com.android.systemui.statusbar.phone.NotificationIconContainer",
		)
		val TunerServiceImplClass = ReflectedClass.of("com.android.systemui.tuner.TunerServiceImpl")

		try {
			val AbstractComposeViewClass = ReflectedClass.of("androidx.compose.ui.platform.AbstractComposeView")
			AbstractComposeViewClass.before("disposeComposition").run { param ->
				if (isMovingClock || isReparentingComposeView) {
					param.setResult(null)
				}
			}
		} catch (ignored: Throwable) {
		}

		val ConnectivityCallbackHandlerClass = ReflectedClass.of(
			"com.android.systemui.statusbar.connectivity.CallbackHandler",
		)
		val NotificationIconContainerAlwaysOnDisplayViewModelClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel",
		)
		val NotificationIconContainerStatusBarViewModelClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel",
		)
		val PrivacyItemClass = ReflectedClass.of("com.android.systemui.privacy.PrivacyItem")
		val PhoneStatusBarViewControllerClass = ReflectedClass.of(
			"com.android.systemui.statusbar.phone.PhoneStatusBarViewController",
		)
		val KeyguardStateControllerImplClass = ReflectedClass.of(
			"com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
		)
		val StatusBarIconControllerImplClass = ReflectedClass.ofIfPossible(
			"com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
		).let {
			if (it.clazz !=
				null
			) {
				it
			} else {
				ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconControllerImpl")
			}
		}
		val CommandQueueClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.CommandQueue")
		val ShadeHeaderControllerClass = ReflectedClass.of("com.android.systemui.shade.ShadeHeaderController")
		val ActivityStarterImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ActivityStarterImpl")
		//endregion

		KeyguardStateControllerImplClass
			.after("notifyKeyguardState")
			.run { param ->
				val mKeyguardUpdateMonitor = getObjectField(param.thisObject, "mKeyguardUpdateMonitor")
				val keyguardShowing = getObjectField(mKeyguardUpdateMonitor, "mKeyguardShowing") as Boolean
				for (c in clockVisibilityCallbacks) {
					try {
						c.OnVisibilityChanged(!keyguardShowing)
					} catch (ignored: Throwable) {
					}
				}
			}

		try {
			val isClockVisibleClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModelImpl\$isClockVisible\$1",
			)
			if (isClockVisibleClass.clazz != null) {
				isClockVisibleClass
					.after("invoke")
					.run { param ->
						val args = param.args
						if (args != null && args.size >= 3 && args[0] is Boolean) {
							val allowedByPolicy = args[0] as Boolean
							val isSuppressed = args[1] as? Boolean ?: false
							val disableFlags = asDisableFlagsVisibilityModel(args[1])
								?: asDisableFlagsVisibilityModel(args[2])
							if (disableFlags != null) {
								val isClockAllowed = getBooleanField(disableFlags, "isClockAllowed")
								val visible = allowedByPolicy && !isSuppressed && isClockAllowed
								if (visible != mStatusBarClockVisible) {
									mStatusBarClockVisible = visible
									runOnMainThread { updateClockCompanionViews() }
								}
							}
						}
					}
			}
		} catch (t: Throwable) {
			log(t)
		}

		CommandQueueClass
			.afterConstruction()
			.run { param ->
				mCommandQueue = param.thisObject
				if (VolteIconEnabled || VowifiIconEnabled) {
					initVoData()
				}
			}

		StatusBarIconControllerImplClass
			.afterConstruction()
			.run { param ->
				mStatusBarIconController = param.thisObject
				if (mCommandQueue == null) {
					for (arg in param.args) {
						if (arg != null && arg.javaClass.name.endsWith("CommandQueue")) {
							mCommandQueue = arg
							break
						}
					}
				}
				if (VolteIconEnabled || VowifiIconEnabled) {
					initVoData()
				}
			}

		if (NotificationIconContainerAlwaysOnDisplayViewModelClass.clazz != null) {
			NotificationIconContainerAlwaysOnDisplayViewModelClass
				.afterConstruction()
				.run { param ->
					AODNIC = param.thisObject
					setObjectField(AODNIC, "maxIcons", NotificationAODIconLimit)
				}

			NotificationIconContainerStatusBarViewModelClass
				.afterConstruction()
				.run { param ->
					SBNIC = param.thisObject
					setObjectField(SBNIC, "maxIcons", NotificationIconLimit)
				}
		}

		initSwitchIcon()

		TunerServiceImplClass
			.afterConstruction()
			.run { param ->
				mTunerService = param.thisObject
				val mObserver = getObjectField(param.thisObject, "mObserver")
				if (mObserver != null) {
					ReflectedClass.of(mObserver.javaClass)
						.after("onChange")
						.run { _ -> wifiVisibleChanged() }
				}
			}

		TunerServiceImplClass
			.after("addTunable")
			.run { param ->
				if (param.args[1]!!.javaClass == Array<String>::class.java &&
					Arrays.asList(*(param.args[1] as Array<*>)).contains(ICON_HIDE_LIST)
				) {
					wifiVisibleChanged()
				} else if (ICON_HIDE_LIST == param.args[1]) {
					wifiVisibleChanged()
				}
			}

		ConnectivityCallbackHandlerClass
			.after("setWifiIndicators")
			.run { param ->
				val wifiVisible = getBooleanField(getObjectField(param.args[0], "statusIcon"), "visible")
				if (wifiVisible != mWifiVisible) {
					mWifiVisible = wifiVisible
					if (CombineSignalIcons) {
						wifiVisibleChanged()
					}
				}
			}

		PrivacyItemClass
			.before("hashCode")
			.run { param ->
				if (HidePrivacyChip) {
					setObjectField(param.thisObject, "paused", true)
				}
			}

		//region SB Padding
		PhoneStatusBarViewClass
			.afterConstruction()
			.run { param -> mPhoneStatusbarView = param.thisObject as FrameLayout }

		PhoneStatusBarViewClass
			.after("onFinishInflate")
			.run { param ->
				mPhoneStatusbarView = param.thisObject as FrameLayout
				scheduleHeightsUpdate()
			}

		PhoneStatusBarViewClass
			.after("onAttachedToWindow")
			.run { param ->
				mPhoneStatusbarView = param.thisObject as FrameLayout
				scheduleHeightsUpdate()
			}

		PhoneStatusBarViewClass
			.after("updateStatusBarHeight")
			.run { param ->
				@SuppressLint("DiscouragedApi")
				val sbContentsView = (param.thisObject as View).findViewById<View>(SystemUtils.idOf("status_bar_contents"))
				mStatusBarContents = sbContentsView
				applyPhoneStatusBarViewHeight()
				applyStatusBarContentPadding(sbContentsView)
				applyStatusBarContainerLayout()
			}
		//endregion

		//region multi row statusbar
		NotificationIconContainerClass
			.before("onMeasure")
			.run { param ->
				if (param.thisObject == mNotificationIconContainer) {
					setObjectField(param.thisObject, "mMaxIcons", NotificationIconLimit)
				}
				setObjectField(param.thisObject, "mIsStaticLayout", false)
			}

		NotificationIconContainerClass
			.after("onMeasure")
			.run { param ->
				setObjectField(param.thisObject, "mIsStaticLayout", true)
				if (param.thisObject != mNotificationIconContainer &&
					(param.thisObject as? View)?.id == SystemUtils.idOf("notificationIcons")
				) {
					mNotificationIconContainer = param.thisObject as? ViewGroup
				}
			}

		NotificationIconContainerClass
			.before("setMaxIconsAmount")
			.run { param ->
				if (param.thisObject == mNotificationIconContainer && param.args.isNotEmpty()) {
					param.args[0] = NotificationIconLimit
				}
			}

		NotificationIconContainerClass
			.after("setMaxIconsAmount")
			.run { param ->
				if (param.thisObject == mNotificationIconContainer) {
					scheduleHeightsUpdate()
				}
			}
		//endregion

		PhoneStatusBarViewClass
			.after("onConfigurationChanged")
			.run { param ->
				mPhoneStatusbarView = param.thisObject as? FrameLayout
				mPhoneStatusbarView?.post {
					try {
						placeNTSB()
					} catch (ignored: Throwable) {}
					try {
						placeClock()
					} catch (ignored: Throwable) {}
					try {
						repositionOngoingChip()
					} catch (ignored: Throwable) {}
					scheduleHeightsUpdate()
					val sbContents =
						mStatusBarContents
							?: mPhoneStatusbarView?.findViewById<View>(SystemUtils.idOf("status_bar_contents"))?.also {
								mStatusBarContents =
									it
							}
					sbContents?.let { applyStatusBarContentPadding(it) }
					updateShadeHeaderPadding()
				}
				mainHandler.postDelayed({
					if (BatteryBarView.hasInstance()) {
						BatteryBarView.getInstance()?.let { refreshBatteryBar(it) }
					}
				}, 2000L)
			}

		ActivityStarterImplClass
			.afterConstruction()
			.run { param ->
				if (mActivityStarter == null) {
					mActivityStarter = param.thisObject
				}
			}

		val clickListener = ClickListener()

		ShadeHeaderControllerClass
			.after("onInit")
			.run { param ->
				mShadeHeaderController = param.thisObject
				val mView = getObjectField(param.thisObject, "mView") as View
				mShadeHeaderView = mView
				applyShadeHeaderPaddingOnly(mView)

				mView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
					applyShadeHeaderPaddingOnly(v)
				}

				mView.findViewById<View>(SystemUtils.idOf("clock")).setOnClickListener(clickListener)
				mView.findViewById<View>(SystemUtils.idOf("clock")).setOnLongClickListener(clickListener)

				mView.findViewById<View>(SystemUtils.idOf("date")).setOnClickListener(clickListener)
				mView.findViewById<View>(SystemUtils.idOf("date")).setOnLongClickListener(clickListener)

				legacyTapListenerAttached = true
			}

		try {
			ReflectedClass.ofIfPossible("androidx.compose.ui.platform.AndroidComposeView")
				.takeIf { it.clazz != null }?.before("dispatchTouchEvent")
				?.run { param ->
					(param.thisObject as? View)?.let { view ->
						(param.args?.firstOrNull() as? MotionEvent)?.let { recordComposeTap(view, it) }
					}
				}
			ReflectedClass.ofIfPossible("com.android.systemui.clock.domain.interactor.ClockInteractor")
				.takeIf { it.clazz != null }?.before("launchClockActivity")
				?.run { param -> if (interceptComposeHeaderTap()) param.setResult(null) }
		} catch (t: Throwable) {
		}

		ShadeHeaderControllerClass
			.before("updateConstraintsForInsets")
			.run { param ->
				mShadeHeaderController = param.thisObject
				if (param.args.isNotEmpty() && param.args[0] is View) {
					val view = param.args[0] as View
					mShadeHeaderView = view
					applyShadeHeaderPaddingOnly(view)
				}
			}

		ShadeHeaderControllerClass
			.after("updateVisibility")
			.run {
				if (mShadeHeaderView != null && mShadeHeaderView!!.visibility == View.VISIBLE) {
					applyShadeHeaderPaddingOnly(mShadeHeaderView)
				}
			}

		PhoneStatusBarViewControllerClass
			.after("onViewAttached")
			.run { param ->
				mClockView = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("clock"))
				updateClockColor()

				mPhoneStatusbarView!!.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
					scheduleHeightsUpdate()
				}
				applyPhoneStatusBarViewHeight()

				mStatusBarContents = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_contents"))
				applyStatusBarContentPadding(mStatusBarContents)

				mStatusbarStartSide = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_start_side_except_heads_up"))
				val chipHierarchyListener = object : ViewGroup.OnHierarchyChangeListener {
					override fun onChildViewAdded(parent: View, child: View?) {
						if (isNotificationMultiRowActive() && child != null && child != mClockView && child != mJetpackClockView &&
							child.javaClass.name.endsWith("ComposeView")
						) {
							mOngoingChipComposeView = child
							repositionOngoingChip()
							scheduleHeightsUpdate()
						}
					}

					override fun onChildViewRemoved(parent: View, child: View?) {
					}
				}
				mStatusbarStartSide?.setOnHierarchyChangeListener(chipHierarchyListener)
				mPhoneStatusbarView!!.findViewById<ViewGroup?>(
					SystemUtils.idOf("start_side_notif_and_chip_container"),
				)?.setOnHierarchyChangeListener(chipHierarchyListener)

				mSystemIconArea = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("statusIcons"))

				if (isJetpackClock) {
					mJetpackClockView = findComposeView(mStatusbarStartSide)
				}

				createCenterIconArea()
				makeLeftSplitArea()

				if (BBarEnabled) {
					placeBatteryBar()
				}

				if (VolteIconEnabled || VowifiIconEnabled) {
					initVoData()
				}

				if (networkOnSBEnabled) {
					networkTrafficSB = NetworkTraffic.getInstance(mContext, true)
					placeNTSB()
				}

				setHeights()
				placeClock()
				setHeights()
			}

		val hasComposeClock = ReflectedClass.ofIfPossible("com.android.systemui.clock.ui.composable.ClockKt").clazz != null
		if (hasComposeClock) {
			try {
				val clockInteractorClass = ReflectedClass.of("com.android.systemui.clock.domain.interactor.ClockInteractor")
				clockInteractorClass.after("getClockTextFormatString").run { param ->
					val orig: String = param.getResult()
					val customFormat = orig.replace(Regex("(?<!H)H(?!H)"), "HH").trim()
					param.setResult(customFormat)
				}
			} catch (t: Throwable) {
				log("ClockInteractor not found, skipping Android 17 Compose hook.")
			}
		}
		isJetpackClock = hasComposeClock
		ClockClass
			.before("getSmallTime")
			.run { param ->
				if (isJetpackClock) return@run
				setObjectField(param.thisObject, "mAmPmStyle", AM_PM_STYLE_GONE)
				setObjectField(param.thisObject, "mShowSeconds", mShowSeconds)
			}

		ClockClass
			.after("getSmallTime")
			.run { param ->
				if (param.thisObject != mClockView) return@run
				if (isJetpackClock) return@run

				val result = SpannableStringBuilder()
				result.append(getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor))
				val clockText = SpannableStringBuilder.valueOf(param.getResult<CharSequence>())
				if (clockColor != null) {
					clockText.setSpan(ForegroundColorSpan(clockColor!!), 0, clockText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				}
				result.append(clockText)
				if (mAmPmStyle != AM_PM_STYLE_GONE) {
					result.append(getFormattedString("\$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor))
				}
				result.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor))

				if (getAdditionalInstanceField(param.thisObject, "stringFormatCallBack") == null) {
					val callback = object : FormattedStringCallback {
						override fun onRefreshNeeded() {
							if (!mShowSeconds) updateClock()
						}
					}
					stringFormatter.registerCallback(callback)
					setAdditionalInstanceField(param.thisObject, "stringFormatCallBack", callback)
				}
				param.setResult(result)
			}

		ClockClass
			.after("onDarkChanged")
			.run { param ->
				if (param.thisObject != mClockView) return@run
				updateClockColor()
				if (BatteryBarView.hasInstance()) {
					refreshBatteryBar(BatteryBarView.getInstance()!!)
				}
			}

		val MobileIconsInteractorImplClass = ReflectedClass.of(
			"com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl",
		)
		val ReadonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow")

		MobileIconsInteractorImplClass
			.after("getMobileConnectionInteractorForSubId")
			.run { param ->
				if (HideRoamingState) {
					setObjectField(
						param.getResult<Any?>(),
						"isRoaming",
						ReadonlyStateFlowClass.clazz!!.constructors[0].newInstance(getStateFlowImplOf(false)),
					)
				}
			}

		//region QS footer build number
		registerBuildNumberFormatCallback()
		try {
			ReflectedClass.of("com.android.systemui.development.domain.interactor.BuildNumberInteractor")
				.after("<init>")
				.run { param ->
					param.thisObject?.let { captureBuildNumberInteractor(it) }
				}
		} catch (ignored: Throwable) {
		}

		// The interactor can be built before this pack installs its hooks
		// `onActivated` is the first method the view model runs when QS opens
		try {
			ReflectedClass.of("com.android.systemui.development.ui.viewmodel.BuildNumberViewModel")
				.before("onActivated")
				.run { param ->
					val interactor = try {
						getObjectField(param.thisObject, "buildNumberInteractor")
					} catch (ignored: Throwable) {
						null
					}
					interactor?.let { captureBuildNumberInteractor(it) }
				}
		} catch (ignored: Throwable) {
		}
		//endregion
	}

	private fun createCenterIconArea() {
		mCenteredIconArea = LinearLayout(mContext)
		val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
		lp.gravity = Gravity.CENTER
		mCenteredIconArea!!.layoutParams = lp
		mPhoneStatusbarView!!.addView(mCenteredIconArea!!)
	}

	private fun updateClockColor() {
		if (mClockView == null) return

		currentClockColor = mClockView!!.textColors.defaultColor

		mBeforeClockView?.setTextColor(currentClockColor)
		mAfterClockView?.setTextColor(currentClockColor)
		applyClockTextEffect(mBeforeClockView)
		applyClockTextEffect(mAfterClockView)
		applyClockTextEffect(mClockView)

		for (callback in mTextColorCallbacks) {
			callback.onTextColorChanged(currentClockColor)
		}
	}

	private fun updateStatusbarHeight() {
		try {
			callMethod(mPhoneStatusbarView, "updateStatusBarHeight")
		} catch (ignored: Throwable) {
		}
	}

	private fun getDisplayRotation(view: View?): Int {
		try {
			if (view != null && view.display != null) {
				return view.display.rotation
			}
		} catch (ignored: Throwable) {}
		try {
			val dm = mContext?.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
			if (dm != null) {
				val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
				if (display != null) {
					return display.rotation
				}
			}
		} catch (ignored: Throwable) {}
		try {
			val context = view?.context ?: mPhoneStatusbarView?.context ?: mContext
			if (context != null && context.display != null) {
				return context.display.rotation
			}
		} catch (ignored: Throwable) {}
		return Surface.ROTATION_0
	}

	private fun isCutoutOnStart(cutoutStart: Int, cutoutEnd: Int, rotation: Int): Boolean {
		if (cutoutStart > cutoutEnd) return true
		if (cutoutEnd > cutoutStart) return false
		return rotation == Surface.ROTATION_90
	}

	private fun getDimensionPixelSize(view: View?, resName: String, defaultValue: Int): Int = try {
		(view?.resources ?: mContext?.resources)?.getDimensionPixelSize(SystemUtils.dimenIdOf(resName)) ?: defaultValue
	} catch (ignored: Throwable) {
		defaultValue
	}

	private fun getDisplayCutoutSafeInset(view: View?, isStart: Boolean): Int = try {
		val insets = view?.rootWindowInsets ?: mPhoneStatusbarView?.rootWindowInsets
		var cutout: DisplayCutout? = insets?.displayCutout
		if (cutout == null) {
			val display = view?.display ?: mPhoneStatusbarView?.display ?: run {
				val dm = mContext?.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
				dm?.getDisplay(Display.DEFAULT_DISPLAY)
			}
			cutout = display?.cutout
		}
		if (cutout != null) {
			val isRtl = view?.layoutDirection == View.LAYOUT_DIRECTION_RTL
			if (isStart) {
				if (isRtl) cutout.safeInsetRight else cutout.safeInsetLeft
			} else {
				if (isRtl) cutout.safeInsetLeft else cutout.safeInsetRight
			}
		} else {
			0
		}
	} catch (ignored: Throwable) {
		0
	}

	private fun applyStatusBarContentPadding(sbContentsView: View?) {
		if (sbContentsView == null) return
		val rotation = getDisplayRotation(sbContentsView)
		val screenWidth = sbContentsView.context.resources.displayMetrics.widthPixels
		val isLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)

		val defaultStart = getDimensionPixelSize(sbContentsView, "status_bar_padding_start", 0)
		val defaultEnd = getDimensionPixelSize(sbContentsView, "status_bar_padding_end", 0)

		val cutoutStart = getDisplayCutoutSafeInset(sbContentsView, true)
		val cutoutEnd = getDisplayCutoutSafeInset(sbContentsView, false)

		val parentPaddingStart = mPhoneStatusbarView?.paddingStart ?: 0
		val parentPaddingEnd = mPhoneStatusbarView?.paddingEnd ?: 0

		val stockStart = if (parentPaddingStart >=
			cutoutStart
		) {
			defaultStart
		} else {
			Math.max(cutoutStart - parentPaddingStart, defaultStart)
		}
		val stockEnd = if (parentPaddingEnd >= cutoutEnd) defaultEnd else Math.max(cutoutEnd - parentPaddingEnd, defaultEnd)

		val paddingStart: Int
		val paddingEnd: Int

		if (isLandscape) {
			val cutoutOnStart = isCutoutOnStart(cutoutStart, cutoutEnd, rotation)
			if (cutoutOnStart) {
				paddingStart = stockStart
				paddingEnd =
					if (LandscapeSBPaddingEnd != PADDING_DEFAULT) Math.round(LandscapeSBPaddingEnd * screenWidth / 100f) else stockEnd
			} else {
				paddingStart =
					if (LandscapeSBPaddingStart !=
						PADDING_DEFAULT
					) {
						Math.round(LandscapeSBPaddingStart * screenWidth / 100f)
					} else {
						stockStart
					}
				paddingEnd = stockEnd
			}
		} else {
			paddingStart = if (SBPaddingStart != PADDING_DEFAULT) Math.round(SBPaddingStart * screenWidth / 100f) else stockStart
			paddingEnd = if (SBPaddingEnd != PADDING_DEFAULT) Math.round(SBPaddingEnd * screenWidth / 100f) else stockEnd
		}

		if (sbContentsView.paddingStart != paddingStart || sbContentsView.paddingEnd != paddingEnd) {
			sbContentsView.setPaddingRelative(
				paddingStart,
				sbContentsView.paddingTop,
				paddingEnd,
				sbContentsView.paddingBottom,
			)
		}
	}

	private fun applyShadeHeaderPaddingOnly(view: View?) {
		if (view == null) return
		try {
			val rotation = getDisplayRotation(view)
			if (rotation != Surface.ROTATION_90 && rotation != Surface.ROTATION_270) {
				val defaultPadding = getDimensionPixelSize(view, "qs_panel_padding", 0)
				if (view.paddingStart != defaultPadding || view.paddingEnd != defaultPadding) {
					view.setPaddingRelative(
						defaultPadding,
						view.paddingTop,
						defaultPadding,
						view.paddingBottom,
					)
				}
				return
			}

			val cutoutStart = getDisplayCutoutSafeInset(view, true)
			val cutoutEnd = getDisplayCutoutSafeInset(view, false)

			val defaultLeft = Math.max(cutoutStart, getDimensionPixelSize(view, "large_screen_shade_header_left_padding", 0))
			val defaultRight = Math.max(cutoutEnd, getDimensionPixelSize(view, "qs_panel_padding", 0))
			val screenWidth = view.context.resources.displayMetrics.widthPixels

			val cutoutOnStart = isCutoutOnStart(cutoutStart, cutoutEnd, rotation)

			val paddingStart = if (cutoutOnStart) {
				defaultLeft
			} else {
				if (LandscapeSBPaddingStart !=
					PADDING_DEFAULT
				) {
					Math.round(LandscapeSBPaddingStart * 2.0f * screenWidth / 100f)
				} else {
					defaultLeft
				}
			}
			val paddingEnd = if (cutoutOnStart) {
				if (LandscapeSBPaddingEnd !=
					PADDING_DEFAULT
				) {
					Math.round(LandscapeSBPaddingEnd * 2.0f * screenWidth / 100f)
				} else {
					defaultRight
				}
			} else {
				defaultRight
			}

			if (view.paddingStart != paddingStart || view.paddingEnd != paddingEnd) {
				view.setPaddingRelative(
					paddingStart,
					view.paddingTop,
					paddingEnd,
					view.paddingBottom,
				)
			}
		} catch (ignored: Throwable) {}
	}

	private fun updateShadeHeaderPadding() {
		val headerView = mShadeHeaderView ?: return
		headerView.post {
			try {
				applyShadeHeaderPaddingOnly(headerView)

				if (mShadeHeaderController != null) {
					var insets = headerView.rootWindowInsets ?: mPhoneStatusbarView?.rootWindowInsets
					if (insets == null) {
						insets = getObjectField(mShadeHeaderController, "lastInsets") as? WindowInsets
					}
					if (insets != null) {
						callMethod(mShadeHeaderController, "updateConstraintsForInsets", headerView, insets)
					}
				}
				headerView.requestLayout()
				headerView.invalidate()
			} catch (ignored: Throwable) {}
		}
	}

	//region double row left area
	@SuppressLint("DiscouragedApi")
	private fun makeLeftSplitArea() {
		mNotificationIconContainer = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("notificationIcons"))

		mNotificationContainerContainer = LinearLayout(mContext).apply {
			isBaselineAligned = false
			gravity = Gravity.CENTER_VERTICAL
			clipChildren = false
		}

		if (mLeftVerticalSplitContainer == null) {
			mLeftVerticalSplitContainer = FrameLayout(mContext).apply {
				clipChildren = false
			}
		} else {
			mLeftVerticalSplitContainer!!.removeAllViews()
			if (mLeftVerticalSplitContainer!!.parent != null) {
				(mLeftVerticalSplitContainer!!.parent as ViewGroup).removeView(mLeftVerticalSplitContainer!!)
			}
		}

		mLeftVerticalSplitContainer!!.layoutTransition = null
		val parent = mNotificationIconContainer!!.parent as ViewGroup
		val originalParams = mNotificationIconContainer!!.layoutParams
		val lp: ViewGroup.LayoutParams = when {
			parent is LinearLayout -> (originalParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
				?: LinearLayout.LayoutParams(originalParams)

			parent is FrameLayout -> (originalParams as? FrameLayout.LayoutParams)?.let { FrameLayout.LayoutParams(it) }
				?: FrameLayout.LayoutParams(originalParams)

			originalParams is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(originalParams)

			else -> ViewGroup.LayoutParams(originalParams)
		}
		lp.height = ViewGroup.LayoutParams.MATCH_PARENT
		if (lp is ViewGroup.MarginLayoutParams) {
			setLeftSplitAreaMargins(lp)
		}

		mLeftVerticalSplitContainer!!.layoutParams = lp
		mLeftVerticalSplitContainer!!.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
			scheduleHeightsUpdate()
		}

		mLeftExtraRowContainer = ShyLinearLayout(mContext).apply {
			isBaselineAligned = false
			gravity = Gravity.CENTER_VERTICAL
			clipChildren = false
		}
		val extraRowGravity = if (!multiRowClockBottom) Gravity.TOP else Gravity.BOTTOM
		val extraRowParams = FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
			extraRowGravity,
		)
		mLeftVerticalSplitContainer!!.addView(mLeftExtraRowContainer!!, 0, extraRowParams)

		mNotificationIconArea = parent
		mNotificationIconArea!!.clipChildren = false
		mNotificationIconArea!!.clipToPadding = false

		parent.addView(mLeftVerticalSplitContainer!!, parent.indexOfChild(mNotificationIconContainer!!))
		parent.removeView(mNotificationIconContainer!!)
		val notifRowGravity = if (!multiRowClockBottom) Gravity.BOTTOM else Gravity.TOP
		val notificationRowParams = FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
			notifRowGravity,
		)
		mLeftVerticalSplitContainer!!.addView(mNotificationContainerContainer!!, notificationRowParams)

		repositionOngoingChip()

		mNotificationContainerContainer!!.addView(mNotificationIconContainer!!)

		val notificationIconParams = mNotificationIconContainer!!.layoutParams as LinearLayout.LayoutParams
		notificationIconParams.weight = 100f
		notificationIconParams.gravity = Gravity.CENTER_VERTICAL
		mNotificationIconContainer!!.layoutParams = notificationIconParams
		applyNotificationIconLimit()
		mNotificationIconContainer!!.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
			override fun onChildViewAdded(parent: View, child: View) {
				scheduleHeightsUpdate()
			}

			override fun onChildViewRemoved(parent: View, child: View) {
				scheduleHeightsUpdate()
			}
		})

		(mStatusbarStartSide!!.parent as View).layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		mStatusbarStartSide!!.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		mLeftVerticalSplitContainer!!.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		applyStatusBarContainerLayout()
	}

	private val ongoingChipLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
		scheduleHeightsUpdate()
	}

	private fun repositionOngoingChip() {
		if (!isNotificationMultiRowActive()) return
		if ((mOngoingChipComposeView == null || mOngoingChipComposeView?.parent == null) && mPhoneStatusbarView != null) {
			mOngoingChipComposeView =
				findComposeView(mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_start_side_except_heads_up")))
		}
		val chip = mOngoingChipComposeView ?: return
		val notifContainer = mNotificationContainerContainer ?: return
		if (chip.parent != notifContainer) {
			isReparentingComposeView = true
			try {
				reAddView(notifContainer, chip, 0)
			} finally {
				isReparentingComposeView = false
			}
		}
		chip.removeOnLayoutChangeListener(ongoingChipLayoutListener)
		chip.addOnLayoutChangeListener(ongoingChipLayoutListener)
	}

	private fun findComposeView(parent: ViewGroup?): View? {
		if (parent == null) return null
		for (i in 0 until parent.childCount) {
			val child = parent.getChildAt(i) ?: continue
			if (child != mClockView && child != mJetpackClockView && child.javaClass.name.endsWith("ComposeView")) {
				return child
			}
		}
		for (i in 0 until parent.childCount) {
			val child = parent.getChildAt(i)
			if (child is ViewGroup &&
				child != mLeftVerticalSplitContainer &&
				child != mNotificationContainerContainer &&
				child != mLeftExtraRowContainer &&
				child != mClockContainer
			) {
				val found = findComposeView(child)
				if (found != null) return found
			}
		}
		return null
	}

	private fun isNotificationMultiRowActive(): Boolean = notificationAreaMultiRow && !isLandscapeStatusBar()

	private fun isLandscapeStatusBar(): Boolean {
		val context = mPhoneStatusbarView?.context ?: mContext
		return context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
	}

	private fun setLeftSplitAreaMargins(layoutParams: ViewGroup.MarginLayoutParams) {
		if (isNotificationMultiRowActive() || isLandscapeStatusBar()) {
			layoutParams.topMargin = 0
			layoutParams.bottomMargin = 0
		} else {
			val margin = ResourceTools.dpToPx(mContext, 4)
			layoutParams.topMargin = margin
			layoutParams.bottomMargin = margin
		}
	}

	private fun setLeftSplitAreaMargins(view: View?) {
		val layoutParams = view?.layoutParams as? ViewGroup.MarginLayoutParams ?: return
		val oldTop = layoutParams.topMargin
		val oldBottom = layoutParams.bottomMargin
		setLeftSplitAreaMargins(layoutParams)
		if (layoutParams.topMargin != oldTop || layoutParams.bottomMargin != oldBottom) {
			view.layoutParams = layoutParams
		}
	}

	private fun setHeight(view: View?, height: Int) {
		if (view == null) return
		val lp = view.layoutParams ?: return
		if (lp.height != height) {
			lp.height = height
			view.layoutParams = lp
		}
	}

	private fun setHeights() {
		if (mPhoneStatusbarView == null ||
			mNotificationContainerContainer == null ||
			mLeftExtraRowContainer == null ||
			mNotificationIconContainer == null
		) {
			return
		}

		applyPhoneStatusBarViewHeight()
		val statusbarHeight = getStatusbarContentHeight()
		if (statusbarHeight <= 0) return

		applyStatusBarContainerLayout()

		val splitHeight = Math.max(1, statusbarHeight / 2)
		val hasExtraRow = mLeftExtraRowContainer!!.visibility == View.VISIBLE
		var hasNotificationRowContent =
			mNotificationIconContainer!!.visibility == View.VISIBLE && mNotificationIconContainer!!.childCount > 0
		for (i in 0 until mNotificationContainerContainer!!.childCount) {
			if (hasNotificationRowContent) break
			val child = mNotificationContainerContainer!!.getChildAt(i)
			hasNotificationRowContent = child != mNotificationIconContainer &&
				child.visibility == View.VISIBLE &&
				Math.max(child.width, child.measuredWidth) > 0 &&
				Math.max(child.height, child.measuredHeight) > 0
		}
		val splitRows = isNotificationMultiRowActive() && hasExtraRow && hasNotificationRowContent

		val halfSpacing = Math.max(1, ResourceTools.dpToPx(mContext, 1))
		val defaultRowHeight = ResourceTools.dpToPx(mContext, 20)
		val iconSize = getDimensionPixelSize(mNotificationIconContainer, "status_bar_icon_size", 0).let {
			if (it > 0) it else getAndroidDimensionPixelSize(mContext, "status_bar_icon_size", defaultRowHeight)
		}
		val clockMeasuredHeight = maxOf(
			mClockView?.measuredHeight ?: 0,
			mJetpackClockView?.measuredHeight ?: 0,
			mClockContainer?.measuredHeight ?: 0,
		)
		val targetRowHeight = maxOf(iconSize, defaultRowHeight, clockMeasuredHeight)
		val maxRowHeight = Math.max(1, splitHeight - halfSpacing)
		val rowHeight = Math.min(maxRowHeight, targetRowHeight)

		val topRowContainer = if (!multiRowClockBottom) mLeftExtraRowContainer else mNotificationContainerContainer
		val bottomRowContainer = if (!multiRowClockBottom) mNotificationContainerContainer else mLeftExtraRowContainer

		if (splitRows) {
			(topRowContainer?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
				val targetGravity = Gravity.BOTTOM or Gravity.START
				val targetBottomMargin = splitHeight + halfSpacing
				if (lp.gravity != targetGravity || lp.bottomMargin != targetBottomMargin || lp.topMargin != 0 ||
					lp.height != rowHeight
				) {
					lp.gravity = targetGravity
					lp.bottomMargin = targetBottomMargin
					lp.topMargin = 0
					lp.height = rowHeight
					topRowContainer.layoutParams = lp
				}
			}
			(bottomRowContainer?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
				val targetGravity = Gravity.BOTTOM or Gravity.START
				val targetBottomMargin = 0
				if (lp.gravity != targetGravity || lp.bottomMargin != targetBottomMargin || lp.topMargin != 0 ||
					lp.height != splitHeight
				) {
					lp.gravity = targetGravity
					lp.bottomMargin = targetBottomMargin
					lp.topMargin = 0
					lp.height = splitHeight
					bottomRowContainer.layoutParams = lp
				}
			}
		} else {
			(mLeftExtraRowContainer?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
				if (lp.gravity != Gravity.CENTER_VERTICAL || lp.topMargin != 0 || lp.bottomMargin != 0 ||
					lp.height != ViewGroup.LayoutParams.MATCH_PARENT
				) {
					lp.gravity = Gravity.CENTER_VERTICAL
					lp.topMargin = 0
					lp.bottomMargin = 0
					lp.height = ViewGroup.LayoutParams.MATCH_PARENT
					mLeftExtraRowContainer!!.layoutParams = lp
				}
			}
			(mNotificationContainerContainer?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
				if (lp.gravity != Gravity.CENTER_VERTICAL || lp.topMargin != 0 || lp.bottomMargin != 0 ||
					lp.height != ViewGroup.LayoutParams.MATCH_PARENT
				) {
					lp.gravity = Gravity.CENTER_VERTICAL
					lp.topMargin = 0
					lp.bottomMargin = 0
					lp.height = ViewGroup.LayoutParams.MATCH_PARENT
					mNotificationContainerContainer!!.layoutParams = lp
				}
			}
		}

		val notificationVisibility = if (hasNotificationRowContent) View.VISIBLE else View.INVISIBLE
		if (mNotificationContainerContainer!!.visibility != notificationVisibility) {
			mNotificationContainerContainer!!.visibility = notificationVisibility
		}
		setHeight(mNotificationIconContainer, ViewGroup.LayoutParams.MATCH_PARENT)
		if (networkOnSBEnabled && networkTrafficSB != null) {
			val trafficHeight = if (networkTrafficPosition == POSITION_LEFT && splitRows) rowHeight else statusbarHeight
			setHeight(networkTrafficSB, trafficHeight)
		}
	}

	private fun getStatusbarContentHeight(): Int =
		Math.max(0, getPhoneStatusBarHeight() - getStatusBarContentsTopPadding() - getStatusBarContentTopInset())

	private fun getPhoneStatusBarHeight(): Int {
		var height = if (isLandscapeStatusBar()) {
			getTargetPhoneStatusBarHeight()
		} else {
			Math.max(getTargetPhoneStatusBarHeight(), getStatusBarInsetsHeight())
		}
		if (height <= 0) {
			val sbContents =
				mStatusBarContents
					?: mPhoneStatusbarView?.findViewById<View>(SystemUtils.idOf("status_bar_contents"))?.also {
						mStatusBarContents = it
					}
			height = Math.max(getViewHeight(mPhoneStatusbarView), getViewHeight(sbContents))
		}
		return height
	}

	private fun getStatusBarInsetsHeight(): Int = try {
		mPhoneStatusbarView?.rootWindowInsets
			?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())
			?.top ?: 0
	} catch (ignored: Throwable) {
		0
	}

	private fun getTargetPhoneStatusBarHeight(): Int = try {
		val context = mPhoneStatusbarView?.context ?: mContext
		val landscape = isLandscapeStatusBar()
		val config = android.content.res.Configuration(context.resources.configuration)
		config.orientation = if (landscape) {
			android.content.res.Configuration.ORIENTATION_LANDSCAPE
		} else {
			android.content.res.Configuration.ORIENTATION_PORTRAIT
		}
		val orientationContext = context.createConfigurationContext(config)
		val height = getAndroidDimensionPixelSize(
			orientationContext,
			if (landscape) "status_bar_height_landscape" else "status_bar_height_portrait",
			getAndroidDimensionPixelSize(orientationContext, "status_bar_height", 0),
		)
		Math.round(height * statusbarHeightFactor / 100f)
	} catch (ignored: Throwable) {
		0
	}

	private fun getViewHeight(view: View?): Int {
		if (view == null) return 0
		var height = view.height
		if (height <= 0) {
			height = view.measuredHeight
		}
		if (height <= 0 && view.layoutParams != null && view.layoutParams.height > 0) {
			height = view.layoutParams.height
		}
		return height
	}

	private fun getStatusBarContentsTopPadding(): Int {
		val sbContents =
			mStatusBarContents
				?: mPhoneStatusbarView?.findViewById<View>(SystemUtils.idOf("status_bar_contents"))?.also {
					mStatusBarContents = it
				}
		if (sbContents != null) {
			return sbContents.paddingTop
		}
		return try {
			mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_padding_top"))
		} catch (ignored: Throwable) {
			0
		}
	}

	private fun getStatusBarContentTopInset(): Int {
		val noCutout = Xprefs.getBoolean("noCutoutEnabled", false)
		if (!noCutout) return 0
		if (isNotificationMultiRowActive() || isLandscapeStatusBar()) return 0
		return ResourceTools.dpToPx(mContext, 6)
	}

	@SuppressLint("DiscouragedApi")
	private fun getAndroidDimensionPixelSize(context: Context, resourceName: String, defaultValue: Int): Int = try {
		val resId = context.resources.getIdentifier(resourceName, "dimen", "android")
		if (resId == 0) defaultValue else context.resources.getDimensionPixelSize(resId)
	} catch (ignored: Throwable) {
		defaultValue
	}

	private fun applyStatusBarContainerLayout() {
		val topInset = getStatusBarContentTopInset()
		val contentHeight = getStatusbarContentHeight()

		applyPhoneStatusBarViewHeight()
		applyMatchParentHeight(mStatusBarContents)
		applyMatchParentHeight(mStatusbarStartSide)
		applyMatchParentHeight(mNotificationIconArea)
		(mStatusbarStartSide?.parent as? View)?.let {
			applyMatchParentHeight(it)
		}

		applyVerticalContentBounds(mCenteredIconArea, 0, contentHeight)
		setStatusBarTranslationY(mCenteredIconArea, getSingleRowStatusBarTranslationY(mCenteredIconArea))
		if (mSystemIconArea != null) {
			val systemIconParent = mSystemIconArea!!.parent as? View
			if (systemIconParent != null) {
				applyVerticalContentBounds(systemIconParent, 0, contentHeight)
				setStatusBarTranslationY(systemIconParent, getSingleRowStatusBarTranslationY(systemIconParent))
				applyContentChildBounds(mSystemIconArea)
				setStatusBarTranslationY(mSystemIconArea, 0f)
			} else {
				applyVerticalContentBounds(mSystemIconArea, 0, contentHeight)
				setStatusBarTranslationY(mSystemIconArea, getSingleRowStatusBarTranslationY(mSystemIconArea))
			}
		}
		if (mLeftVerticalSplitContainer != null) {
			setLeftSplitAreaMargins(mLeftVerticalSplitContainer)
			var leftContentHeight = contentHeight
			val layoutParams = mLeftVerticalSplitContainer!!.layoutParams as? ViewGroup.MarginLayoutParams
			if (layoutParams != null) {
				leftContentHeight -= layoutParams.topMargin + layoutParams.bottomMargin
			}
			setHeight(mLeftVerticalSplitContainer, Math.max(1, leftContentHeight))
			setStatusBarTranslationY(mLeftVerticalSplitContainer, topInset.toFloat())
		}
		applyLinearLayoutGravity(mLeftExtraRowContainer, Gravity.CENTER_VERTICAL)
		applyLinearLayoutGravity(mNotificationContainerContainer, Gravity.CENTER_VERTICAL)
		applyLinearLayoutGravity(mSystemIconArea, Gravity.CENTER_VERTICAL)

		if (mClockView != null) {
			mClockView!!.gravity = Gravity.CENTER_VERTICAL
			applyLayoutGravity(mClockView, Gravity.CENTER_VERTICAL)
		}
		if (mJetpackClockView != null) {
			applyLayoutGravity(mJetpackClockView, Gravity.CENTER_VERTICAL)
		}
		if (mClockContainer != null) {
			applyLinearLayoutGravity(mClockContainer, Gravity.CENTER_VERTICAL)
			applyLayoutGravity(mClockContainer, Gravity.CENTER_VERTICAL)
		}
	}

	private fun getSingleRowStatusBarTranslationY(view: View?): Float {
		if (view == null) return 0f

		val contentHeight = getStatusbarContentHeight()
		val viewHeight = getViewHeight(view)
		if (contentHeight <= 0 || viewHeight <= 0) return getStatusBarContentTopInset().toFloat()

		return getStatusBarContentTopInset() + Math.max(0, (contentHeight - viewHeight) / 2).toFloat()
	}

	private fun setStatusBarTranslationY(view: View?, translationY: Float) {
		if (view == null) return

		if (view.translationY != translationY) {
			view.translationY = translationY
		}
	}

	private fun applyMatchParentHeight(view: View?) {
		if (view == null || view.layoutParams == null) return

		val layoutParams = view.layoutParams
		if (layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
			layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
			view.layoutParams = layoutParams
		}
		if (view is ViewGroup) {
			view.clipChildren = false
			view.clipToPadding = false
		}
	}

	private fun applyPhoneStatusBarViewHeight() {
		val targetHeight = getPhoneStatusBarHeight()
		if (mPhoneStatusbarView == null || targetHeight <= 0) return

		applyStatusBarHeight(mPhoneStatusbarView, targetHeight)
		applyStatusBarAncestorHeights(targetHeight, mLastAppliedStatusBarHeight)
		mLastAppliedStatusBarHeight = targetHeight
	}

	private fun applyStatusBarAncestorHeights(targetHeight: Int, previousHeight: Int) {
		val rootView = mPhoneStatusbarView?.rootView ?: return
		var parent: Any? = mPhoneStatusbarView?.parent
		while (parent is View && parent != rootView) {
			val layoutParams = parent.layoutParams
			val parentHeight = parent.height
			val ownsPreviousHeight = previousHeight > 0 &&
				(parentHeight == previousHeight || (layoutParams != null && layoutParams.height == previousHeight))
			if (parentHeight > 0 && (parentHeight < targetHeight || ownsPreviousHeight)) {
				applyStatusBarHeight(parent, targetHeight)
			}
			if (parent is ViewGroup) {
				parent.clipChildren = false
				parent.clipToPadding = false
			}
			parent = parent.parent
		}
	}

	private fun applyStatusBarHeight(view: View?, targetHeight: Int) {
		if (view == null) return
		val layoutParams = view.layoutParams
		if (layoutParams != null && layoutParams.height != targetHeight) {
			layoutParams.height = targetHeight
			view.layoutParams = layoutParams
			view.requestLayout()
		}
	}

	private fun applyVerticalContentBounds(view: View?, topMargin: Int, height: Int) {
		if (view == null || view.layoutParams == null || height <= 0) return

		val layoutParams = view.layoutParams
		var changed = false
		if (layoutParams.height != height) {
			layoutParams.height = height
			changed = true
		}
		if (layoutParams is ViewGroup.MarginLayoutParams) {
			if (layoutParams.topMargin != topMargin) {
				layoutParams.topMargin = topMargin
				changed = true
			}
			if (layoutParams.bottomMargin != 0) {
				layoutParams.bottomMargin = 0
				changed = true
			}
		}
		if (changed) {
			view.layoutParams = layoutParams
		}
		applyLayoutGravity(view, Gravity.CENTER_VERTICAL)
		if (view is ViewGroup) {
			view.clipChildren = false
			view.clipToPadding = false
		}
	}

	private fun applyContentChildBounds(view: View?) {
		if (view == null || view.layoutParams == null) return

		val layoutParams = view.layoutParams
		var changed = false
		if (layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
			layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
			changed = true
		}
		if (layoutParams is ViewGroup.MarginLayoutParams) {
			if (layoutParams.topMargin != 0) {
				layoutParams.topMargin = 0
				changed = true
			}
			if (layoutParams.bottomMargin != 0) {
				layoutParams.bottomMargin = 0
				changed = true
			}
		}
		if (changed) {
			view.layoutParams = layoutParams
		}
		applyLayoutGravity(view, Gravity.CENTER_VERTICAL)
	}

	private fun applyLayoutGravity(view: View?, gravity: Int) {
		if (view == null || view.layoutParams == null) return

		val layoutParams = view.layoutParams
		if (layoutParams is LinearLayout.LayoutParams) {
			val updatedGravity = mergeVerticalGravity(layoutParams.gravity, gravity)
			if (layoutParams.gravity != updatedGravity) {
				layoutParams.gravity = updatedGravity
				view.layoutParams = layoutParams
			}
		} else if (layoutParams is FrameLayout.LayoutParams) {
			val updatedGravity = mergeVerticalGravity(layoutParams.gravity, gravity)
			if (layoutParams.gravity != updatedGravity) {
				layoutParams.gravity = updatedGravity
				view.layoutParams = layoutParams
			}
		}
	}

	private fun applyLinearLayoutGravity(view: LinearLayout?, gravity: Int) {
		if (view == null) return

		val updatedGravity = mergeVerticalGravity(view.gravity, gravity)
		if (view.gravity != updatedGravity) {
			view.gravity = updatedGravity
		}
	}

	private fun mergeVerticalGravity(originalGravity: Int, verticalGravity: Int): Int {
		val base = if (originalGravity < 0) 0 else originalGravity
		return (base and Gravity.VERTICAL_GRAVITY_MASK.inv()) or verticalGravity
	}

	private fun applyNotificationIconLimit() {
		val notifIcons = mNotificationIconContainer ?: return

		try {
			callMethod(notifIcons, "setMaxIconsAmount", NotificationIconLimit)
			scheduleHeightsUpdate()
		} catch (ignored: Throwable) {
		}
	}
	//endregion

	//region battery bar related
	private fun refreshBatteryBar(instance: BatteryBarView) {
		BatteryBarView.setStaticColor(batteryLevels, batteryColors, indicateCharging, chargingColor, indicateFastCharging, fastChargingColor, indicatePowerSave, powerSaveColor, BBarTransitColors, BBAnimateCharging)
		instance.visibility = if (BBarEnabled) View.VISIBLE else View.GONE
		instance.setColorful(BBarColorful)
		instance.setOnlyWhileCharging(BBOnlyWhileCharging)
		instance.setOnTop(!BBOnBottom)
		instance.setAlphaPct(BBOpacity)
		instance.setBarHeight(Math.round(BBarHeight / 10f) + 5)
		instance.setCenterBased(BBSetCentered)
		instance.refreshLayout()
	}

	private fun placeBatteryBar() {
		try {
			val batteryBarView = BatteryBarView.getInstance(mContext)!!
			try {
				(batteryBarView.parent as ViewGroup).removeView(batteryBarView)
			} catch (ignored: Throwable) {
			}
			mPhoneStatusbarView!!.addView(batteryBarView)
			refreshBatteryBar(BatteryBarView.getInstance()!!)
		} catch (ignored: Throwable) {
		}
	}
	//endregion

	//region statusbar icon holder
	private fun getStatusbarIconFor(icon: Icon, slotName: String): Any? {
		try {
			val statusbarIcon = ObjenesisHelper.newInstance(StatusBarIconClass!!.clazz)

			setObjectField(statusbarIcon, "visible", true)

			setObjectField(
				statusbarIcon,
				"user",
				UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(0),
			)
			setObjectField(statusbarIcon, "pkg", BuildConfig.APPLICATION_ID)
			setObjectField(statusbarIcon, "icon", icon)
			setObjectField(statusbarIcon, "iconLevel", 0)
			setObjectField(statusbarIcon, "number", 0)
			setObjectField(statusbarIcon, "contentDescription", slotName)

			return statusbarIcon
		} catch (ignored: Throwable) {
			return null
		}
	}

	private fun getStatusbarIconHolderFor(statusbarIcon: Any?): Any? {
		if (statusbarIcon == null || StatusBarIconHolderClass?.clazz == null) return null
		val holder = ObjenesisHelper.newInstance(StatusBarIconHolderClass!!.clazz)
		val fieldName = StatusBarIconHolderClass!!.clazz!!.fields
			.firstOrNull { it.name.lowercase(Locale.getDefault()).contains("icon") }
			?.name ?: return null

		setObjectField(holder, fieldName, statusbarIcon)

		return holder
	}
	//endregion

	//region vo_data related
	private fun initVoData() {
		try {
			if (!telephonyCallbackRegistered) {
				val volteIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_volte)
				val volteStatusbarIcon = getStatusbarIconFor(volteIcon, VO_LTE_SLOT)
				this.volteStatusbarIcon = volteStatusbarIcon
				volteStatusbarIconHolder = getStatusbarIconHolderFor(volteStatusbarIcon)

				val vowifiIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_vowifi)
				val vowifiStatusbarIcon = getStatusbarIconFor(vowifiIcon, VO_WIFI_SLOT)
				this.vowifiStatusbarIcon = vowifiStatusbarIcon
				vowifiStatusbarIconHolder = getStatusbarIconHolderFor(vowifiStatusbarIcon)

				SystemUtils.TelephonyManager()!!.registerTelephonyCallback(voDataExec, voDataCallback)
				telephonyCallbackRegistered = true
			} else {
				if (volteStatusbarIcon == null) {
					val volteIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_volte)
					volteStatusbarIcon = getStatusbarIconFor(volteIcon, VO_LTE_SLOT)
					volteStatusbarIconHolder = getStatusbarIconHolderFor(volteStatusbarIcon)
				}
				if (vowifiStatusbarIcon == null) {
					val vowifiIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_vowifi)
					vowifiStatusbarIcon = getStatusbarIconFor(vowifiIcon, VO_WIFI_SLOT)
					vowifiStatusbarIconHolder = getStatusbarIconHolderFor(vowifiStatusbarIcon)
				}
			}
		} catch (ignored: Exception) {
		}

		updateVoData(true)
	}

	private fun removeVoDataCallback() {
		try {
			SystemUtils.TelephonyManager()!!.unregisterTelephonyCallback(voDataCallback)
			telephonyCallbackRegistered = false
		} catch (ignored: Exception) {
		}
		removeSBIconSlot(VO_LTE_SLOT)
		removeSBIconSlot(VO_WIFI_SLOT)
	}

	private inner class serverStateCallback :
		TelephonyCallback(),
		TelephonyCallback.ServiceStateListener {
		override fun onServiceStateChanged(serviceState: ServiceState) {
			updateVoData(false)
		}
	}

	private fun updateVoData(force: Boolean) {
		val voWifiAvailable = callMethod(SystemUtils.TelephonyManager(), "isWifiCallingAvailable") as Boolean
		val volteStateAvailable = callMethod(SystemUtils.TelephonyManager(), "isVolteAvailable") as Boolean

		if (lastVolteAvailable != volteStateAvailable || force) {
			lastVolteAvailable = volteStateAvailable
			if (volteStateAvailable && VolteIconEnabled) {
				setSBIcon(VO_LTE_SLOT, volteStatusbarIconHolder, volteStatusbarIcon)
			} else {
				removeSBIconSlot(VO_LTE_SLOT)
			}
		}

		if (lastVowifiAvailable != voWifiAvailable || force) {
			lastVowifiAvailable = voWifiAvailable
			if (voWifiAvailable && VowifiIconEnabled) {
				setSBIcon(VO_WIFI_SLOT, vowifiStatusbarIconHolder, vowifiStatusbarIcon)
			} else {
				removeSBIconSlot(VO_WIFI_SLOT)
			}
		}
	}

	private val mainHandler = Handler(Looper.getMainLooper())

	private fun runOnMainThread(action: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			action()
		} else {
			mainHandler.post(action)
		}
	}

	private fun setSBIcon(slot: String, holder: Any?, icon: Any?) {
		runOnMainThread {
			if (!isLegacyStatusBar && mCommandQueue != null && icon != null) {
				try {
					callMethod(mCommandQueue, "setIcon", slot, icon)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mStatusBarIconController != null && holder != null) {
				try {
					callMethod(mStatusBarIconController, "setIcon", slot, holder)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mCommandQueue != null && icon != null) {
				try {
					callMethod(mCommandQueue, "setIcon", slot, icon)
				} catch (ignored: Throwable) {
				}
			}
		}
	}

	private fun removeSBIconSlot(slot: String) {
		runOnMainThread {
			if (!isLegacyStatusBar && mCommandQueue != null) {
				try {
					callMethod(mCommandQueue, "removeIcon", slot)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mStatusBarIconController != null) {
				try {
					callMethod(mStatusBarIconController, "removeAllIconsForSlot", slot, false)
					return@runOnMainThread
				} catch (ignored: Throwable) {
				}
			}
			if (mCommandQueue != null) {
				try {
					callMethod(mCommandQueue, "removeIcon", slot)
				} catch (ignored: Throwable) {
				}
			}
		}
	}
	//endregion

	//region network traffic related
	private fun placeNTSB() {
		if (networkTrafficSB == null) {
			return
		}
		try {
			(networkTrafficSB!!.parent as ViewGroup).removeView(networkTrafficSB!!)
		} catch (ignored: Exception) {
		}
		if (!networkOnSBEnabled) return

		try {
			when (networkTrafficPosition) {
				POSITION_RIGHT -> {
					val systemIcons = mSystemIconArea!!.parent as ViewGroup
					val endSideContent = systemIcons.parent as ViewGroup
					var insertIndex = endSideContent.indexOfChild(systemIcons)
					if (insertIndex == -1) insertIndex = 0
					endSideContent.addView(networkTrafficSB!!, insertIndex)
					networkTrafficSB!!.setPadding(rightClockPadding, 0, leftClockPadding, 0)
				}

				POSITION_LEFT -> {
					if (isNotificationMultiRowActive()) {
						mLeftExtraRowContainer!!.addView(networkTrafficSB!!, mLeftExtraRowContainer!!.childCount)
					} else {
						mStatusbarStartSide!!.addView(networkTrafficSB!!, 1)
					}
					networkTrafficSB!!.setPadding(0, 0, leftClockPadding, 0)
				}

				POSITION_CENTER -> {
					mStatusbarStartSide!!.addView(networkTrafficSB!!)
					networkTrafficSB!!.setPadding(rightClockPadding, 0, rightClockPadding, 0)
				}
			}
			val ntsbLayoutP = networkTrafficSB!!.layoutParams as LinearLayout.LayoutParams
			ntsbLayoutP.gravity = Gravity.CENTER_VERTICAL
			networkTrafficSB!!.layoutParams = ntsbLayoutP
		} catch (ignored: Throwable) {
		}
	}
	//endregion

	//region icon tap related (Compose shade header)
	private var legacyTapListenerAttached = false
	private var composeTapView: WeakReference<View>? = null
	private var composeTapDownAt = 0L
	private var composeTapUpAt = 0L
	private var composeTapX = 0f
	private var composeTapY = 0f

	private fun recordComposeTap(view: View, event: MotionEvent) {
		composeTapView = WeakReference(view)
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> composeTapDownAt = System.currentTimeMillis()

			MotionEvent.ACTION_UP -> {
				composeTapX = event.x
				composeTapY = event.y
				composeTapUpAt = System.currentTimeMillis()
			}
		}
	}

	private fun interceptComposeHeaderTap(): Boolean {
		val view = composeTapView?.get() ?: return false
		val downAt = composeTapDownAt
		val upAt = composeTapUpAt
		composeTapDownAt = 0L
		composeTapUpAt = 0L
		if (legacyTapListenerAttached || downAt == 0L || upAt < downAt) return false
		if (System.currentTimeMillis() - upAt > 2000) return false

		val onClock = composeElementUnder(view, "com.android.systemui:id/expanded_header_clock", composeTapX, composeTapY)
		val onDate =
			composeElementUnder(view, "com.android.systemui:id/expanded_shade_header_day_date", composeTapX, composeTapY)
		if (!onClock && !onDate) return false

		return when {
			upAt - downAt >= ViewConfiguration.getLongPressTimeout() -> {
				val intent = Intent(Intent.ACTION_MAIN)
					.setClassName("com.android.settings", "com.android.settings.Settings\$DateTimeSettingsActivity")
				callMethod(mActivityStarter, "startActivity", intent, true)
				true
			}

			onDate -> {
				val today = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
				today.appendPath(System.currentTimeMillis().toString())
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", Intent(Intent.ACTION_VIEW, today.build()), 0)
				true
			}

			else -> false
		}
	}

	private fun composeElementUnder(view: View, tag: String, x: Float, y: Float): Boolean {
		try {
			val testTag = ReflectedClass.ofIfPossible("androidx.compose.ui.semantics.SemanticsProperties")
				.clazz?.getField("TestTag")?.get(null)
			val root = getObjectField(view, "semanticsOwner")
				?.let { callMethod(it, "getUnmergedRootSemanticsNode") } as? Any ?: return false

			val queue = ArrayDeque<Any>().apply { add(root) }
			var budget = 512
			while (queue.isNotEmpty() && budget-- > 0) {
				val node = queue.removeFirst()
				val nodeTag = (callMethod(node, "getConfig") as? Iterable<*>)
					?.mapNotNull { it as? Map.Entry<*, *> }
					?.firstOrNull { it.key === testTag }
					?.value
				if (nodeTag == tag) {
					val bounds = callMethod(node, "getBoundsInRoot")
					if (x >= getFloatField(bounds, "left") && x <= getFloatField(bounds, "right") &&
						y >= getFloatField(bounds, "top") && y <= getFloatField(bounds, "bottom")
					) {
						return true
					}
				}
				(callMethod(node, "getChildren\$ui", false, false) as? List<*>)
					?.forEach { if (it != null) queue.add(it) }
			}
		} catch (t: Throwable) {
		}
		return false
	}
	//endregion

	//region icon tap related
	internal inner class ClickListener :
		View.OnClickListener,
		View.OnLongClickListener {
		override fun onClick(v: View) {
			val name = mContext.resources.getResourceName(v.id)

			if (name.endsWith("clock")) {
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
			} else if (name.endsWith("date")) {
				val builder = CalendarContract.CONTENT_URI.buildUpon()
				builder.appendPath("time")
				builder.appendPath(System.currentTimeMillis().toString())
				val todayIntent = Intent(Intent.ACTION_VIEW, builder.build())
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", todayIntent, 0)
			}
		}

		override fun onLongClick(v: View): Boolean {
			val name = mContext.resources.getResourceName(v.id)

			if (name.endsWith("clock") || name.endsWith("date")) {
				val mIntent = Intent(Intent.ACTION_MAIN)
				mIntent.setClassName(
					"com.android.settings",
					"com.android.settings.Settings\$DateTimeSettingsActivity",
				)
				callMethod(mActivityStarter, "startActivity", mIntent, true)
				return true
			}
			return false
		}
	}
	//endregion

	//region clock and date related
	private fun companionTextSizePx(): Float {
		val fromClock = mClockView?.textSize ?: 0f
		if (fromClock > 0f) return fromClock

		val fromDimen = try {
			mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_clock_size")).toFloat()
		} catch (t: Throwable) {
			0f
		}
		return if (fromDimen > 0f) fromDimen else 42f
	}

	private fun clockTypeface(): Typeface {
		val composeClock = Typeface.create(COMPOSE_CLOCK_FAMILY, Typeface.NORMAL)
		val family = if (composeClock.getSystemFontFamilyName() == COMPOSE_CLOCK_FAMILY) {
			composeClock
		} else {
			mClockView?.typeface ?: Typeface.DEFAULT
		}
		return Typeface.create(family, CLOCK_FONT_WEIGHT, false)
	}

	private fun applyClockTextEffect(textView: TextView?) {
		when (clockTextEffect) {
			1 -> textView?.setShadowLayer(2.5f, 1.2f, 1.5f, 0x99000000.toInt())
			2 -> textView?.setShadowLayer(1.2f, 0f, 0f, Color.BLACK)
			else -> textView?.setShadowLayer(0f, 0f, 0f, 0)
		}
		textView?.invalidate()
	}

	private fun makeCompanionView(): TextView {
		val sizePx = companionTextSizePx()
		return TextView(mContext).apply {
			setSingleLine(true)
			gravity = Gravity.CENTER_VERTICAL
			includeFontPadding = false
			setPadding(0, 0, 0, 0)
			setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
			typeface = clockTypeface()
			setTextColor(currentClockColor)
			applyClockTextEffect(this)
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			).apply { gravity = Gravity.CENTER_VERTICAL }
		}
	}

	private fun ensureClockContainer(clock: View): LinearLayout {
		val existing = mClockContainer
		if (existing != null && clock.parent === existing && existing.parent != null) {
			return existing
		}

		(existing?.parent as? ViewGroup)?.removeView(existing)

		val container = LinearLayout(mContext).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			clipChildren = false
			clipToPadding = false
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			).apply { gravity = Gravity.CENTER_VERTICAL }
		}

		val before = makeCompanionView()
		val after = makeCompanionView()

		container.addView(before)

		isMovingClock = true
		try {
			(clock.parent as? ViewGroup)?.removeView(clock)
			clock.layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			).apply { gravity = Gravity.CENTER_VERTICAL }
			container.addView(clock)
		} finally {
			isMovingClock = false
		}

		container.addView(after)

		mClockContainer = container
		mBeforeClockView = before
		mAfterClockView = after

		registerCompanionFormatCallback()
		updateClockCompanionViews()
		scheduleCompanionTick()
		return container
	}

	private fun scheduleCompanionTick(restart: Boolean = false) {
		if (!isJetpackClock) return
		if (mCompanionTickScheduled) {
			if (!restart) return
			mainHandler.removeCallbacks(mCompanionTick)
		}

		mCompanionTickScheduled = true
		val period = if (mShowSeconds) 1000L else 60_000L
		mainHandler.postDelayed(mCompanionTick, period - System.currentTimeMillis() % period + 100L)
	}

	private fun registerCompanionFormatCallback() {
		if (mCompanionFormatCallback != null) return
		val callback = object : FormattedStringCallback {
			override fun onRefreshNeeded() {
				runOnMainThread { updateClockCompanionViews() }
			}
		}
		stringFormatter.registerCallback(callback)
		mCompanionFormatCallback = callback
	}

	private fun updateClockCompanionViews() {
		val effect: RenderEffect? = try {
			val foreground = clockColor?.let {
				RenderEffect.createColorFilterEffect(PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN))
			}
			when (clockTextEffect) {
				1 -> {
					val shadowFilter = PorterDuffColorFilter(0x99000000.toInt(), PorterDuff.Mode.SRC_IN)
					val shadow = RenderEffect.createColorFilterEffect(shadowFilter)
					val offsetShadow = RenderEffect.createOffsetEffect(1.5f, 2f, shadow)
					val fg = foreground ?: RenderEffect.createOffsetEffect(0f, 0f)
					RenderEffect.createBlendModeEffect(offsetShadow, fg, BlendMode.SRC_OVER)
				}

				2 -> {
					val outlineFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
					val blur = RenderEffect.createBlurEffect(1.2f, 1.2f, Shader.TileMode.DECAL)
					val outline = RenderEffect.createColorFilterEffect(outlineFilter, blur)
					val fg = foreground ?: RenderEffect.createOffsetEffect(0f, 0f)
					RenderEffect.createBlendModeEffect(outline, fg, BlendMode.SRC_OVER)
				}

				else -> foreground
			}
		} catch (_: Throwable) {
			clockColor?.let { RenderEffect.createColorFilterEffect(PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN)) }
		}
		mJetpackClockView?.setRenderEffect(effect)
		mJetpackClockView?.invalidate()

		applyClockTextEffect(mBeforeClockView)
		applyClockTextEffect(mAfterClockView)
		applyClockTextEffect(mClockView)

		val before = mBeforeClockView
		val after = mAfterClockView
		if (before == null || after == null) return

		val beforeContent = getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor)
		before.text = fullHeightLineBox(beforeContent)
		before.setTextColor(currentClockColor)

		val afterText = SpannableStringBuilder()
		if (mAmPmStyle != AM_PM_STYLE_GONE) {
			afterText.append(getFormattedString("\$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor))
		}
		afterText.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor))
		after.text = fullHeightLineBox(afterText)
		after.setTextColor(currentClockColor)
		before.invalidate()
		after.invalidate()
		mClockContainer?.invalidate()

		before.visibility = if (mStatusBarClockVisible && beforeContent.isNotEmpty()) View.VISIBLE else View.GONE
		after.visibility = if (mStatusBarClockVisible && afterText.isNotEmpty()) View.VISIBLE else View.GONE
		mClockContainer?.visibility = if (mStatusBarClockVisible) View.VISIBLE else View.GONE
		scheduleHeightsUpdate()
	}

	private fun placeClock() {
		var viewToMove: View? = mClockView
		if (isJetpackClock) {
			if (mJetpackClockView != null && mJetpackClockView!!.parent == null) {
				mJetpackClockView = null
			}
			if (mJetpackClockView == null) {
				mJetpackClockView = findComposeView(mStatusbarStartSide)
			}
			viewToMove = mJetpackClockView?.let { ensureClockContainer(it) }
		}
		if (viewToMove == null) return

		val parent = viewToMove!!.parent as ViewGroup?
		var targetArea: ViewGroup? = null
		var index: Int? = null

		when (clockPosition) {
			POSITION_LEFT -> {
				if (isNotificationMultiRowActive()) {
					targetArea = mLeftExtraRowContainer
					index = 0
					val startPadding = if (clockMultiRowStartOffset > 0) {
						ResourceTools.dpToPx(mContext, clockMultiRowStartOffset)
					} else {
						0
					}
					viewToMove!!.setPadding(startPadding, 0, leftClockPadding, 0)
				} else {
					targetArea = mStatusbarStartSide
					index = 1
					viewToMove!!.setPadding(0, 0, leftClockPadding, 0)
				}
			}

			POSITION_CENTER -> {
				targetArea = mCenteredIconArea as ViewGroup?
				viewToMove!!.setPadding(rightClockPadding, 0, rightClockPadding, 0)
			}

			POSITION_RIGHT -> {
				viewToMove!!.setPadding(rightClockPadding, 0, 0, 0)
				targetArea = mSystemIconArea!!.parent as ViewGroup?
			}
		}

		if (targetArea == null || targetArea == parent) return

		val movingCompose = viewToMove == mClockContainer
		if (movingCompose) isMovingClock = true
		try {
			if (parent != null) parent.removeView(viewToMove!!)

			if (movingCompose) {
				val lp = (viewToMove!!.layoutParams as? LinearLayout.LayoutParams)
					?: LinearLayout.LayoutParams(
						ViewGroup.LayoutParams.WRAP_CONTENT,
						ViewGroup.LayoutParams.MATCH_PARENT,
					)
				lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
				lp.height = ViewGroup.LayoutParams.MATCH_PARENT
				lp.gravity = Gravity.CENTER_VERTICAL
				viewToMove!!.layoutParams = lp
			}

			if (index != null) {
				targetArea.addView(viewToMove!!, index)
			} else {
				targetArea.addView(viewToMove!!)
			}
		} finally {
			if (movingCompose) isMovingClock = false
		}
	}

	private fun asDisableFlagsVisibilityModel(o: Any?): Any? =
		if (o != null && o.javaClass.name.endsWith("StatusBarDisableFlagsVisibilityModel")) o else null

	private val stringFormatter = StringFormatter()
	private val buildNumberStringFormatter = StringFormatter()

	private fun fullHeightLineBox(content: CharSequence): CharSequence =
		SpannableStringBuilder(ZERO_WIDTH_SPACE).append(content)

	private fun getFormattedString(dateFormat: String, small: Boolean, @Nullable @ColorInt textColor: Int?): CharSequence {
		if (dateFormat.isEmpty()) return ""

		val formatted = SpannableStringBuilder(stringFormatter.formatString(dateFormat))

		if (small) {
			val style: CharacterStyle = RelativeSizeSpan(0.7f)
			formatted.setSpan(style, 0, formatted.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		if (textColor != null) {
			formatted.setSpan(ForegroundColorSpan(textColor), 0, formatted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}

		return formatted
	}
	//endregion

	//region callbacks
	fun interface ClockVisibilityCallback : Callback {
		fun OnVisibilityChanged(isVisible: Boolean)
	}

	private fun wifiVisibleChanged() {
		try {
			var hideListString = Settings.Secure.getString(
				getObjectField(mTunerService, "mContentResolver") as ContentResolver,
				ICON_HIDE_LIST,
			)

			if (CombineSignalIcons && mWifiVisible) {
				if (hideListString == null || hideListString.isEmpty()) {
					hideListString = "mobile"
				} else if (!hideListString.contains("mobile")) {
					hideListString = "$hideListString,mobile"
				}
			}
			@Suppress("UNCHECKED_CAST")
			val tunables = callMethod(getObjectField(mTunerService, "mTunableLookup"), "get", ICON_HIDE_LIST) as Set<Any?>

			val finalHideListString = hideListString
			mPhoneStatusbarView!!.post {
				for (tunable in tunables) {
					callMethod(tunable, "onTuningChanged", ICON_HIDE_LIST, finalHideListString)
				}
			}
		} catch (ignored: Throwable) {
		}
	}
	//endregion

	override fun onUnloaded() {
		runCatching { mContext.unregisterReceiver(mAppProfileSwitchReceiver) }
		mainHandler.removeCallbacksAndMessages(null)
		mCompanionTickScheduled = false
		mCompanionFormatCallback = null
		mBuildNumberFormatCallback = null
		stringFormatter.release()
		buildNumberStringFormatter.release()
		buildNumberInteractor = null
		originalBuildNumberText = null
		removeVoDataCallback()
	}

	fun interface StatusbarTextColorCallback {
		fun onTextColorChanged(textColor: Int)
	}
}
