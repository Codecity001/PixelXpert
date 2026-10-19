package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.getBooleanField
import de.robv.android.xposed.XposedHelpers.getFloatField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import java.lang.ref.WeakReference
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Executor
import javax.security.auth.callback.Callback
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
class StatusbarMods @SuppressLint("DiscouragedApi") constructor(context: Context) : XposedModPack(context) {

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
		private var HidePrivacyChip = false //works
		//endregion

		//region general use
		private const val PADDING_DEFAULT = -0.5f
		private val clockVisibilityCallbacks = ArrayList<ClockVisibilityCallback>()
		private var notificationAreaMultiRow = false
		private var multiRowClockBottom = false
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

		private var VolteIconEnabled = false //works

		private var VowifiIconEnabled = false
		//endregion

		//region combined signal icons
		private var CombineSignalIcons = false
		private var HideRoamingState = false
		const val ICON_HIDE_LIST = "icon_blacklist"
		//endregion

		//region app profile switch
		const val APP_SWITCH_SLOT = "app_switch"

		private var StatusbarAppSwitchIconEnabled = false //works
		//endregion

		@JvmStatic
		@ColorInt
		fun getCurrentClockColor(): Int {
			return currentClockColor
		}

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
	private var mBeforeClockColor: Int? = null
	private var mAfterClockColor: Int? = null
	private var clockColor: Int? = null

	// The Compose clock renders the whole time string itself,
	// it is re-parented between these two TextViews.
	private var mClockContainer: LinearLayout? = null
	private var mBeforeClockView: TextView? = null
	private var mAfterClockView: TextView? = null
	// One callback for the whole container
	private var mCompanionFormatCallback: FormattedStringCallback? = null
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
		rightClockPadding = mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_clock_starting_padding"))
		leftClockPadding = mContext.resources.getDimensionPixelSize(SystemUtils.dimenIdOf("status_bar_left_clock_end_padding"))
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
	internal var mNotificationContainerContainer: LinearLayout? = null
	private var mLeftVerticalSplitContainer: LinearLayout? = null
	private var mLeftExtraRowContainer: LinearLayout? = null
	private var mPhoneStatusbarView: FrameLayout? = null
	private var mStatusBarContents: View? = null
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

		CombineSignalIcons = Xprefs.getBoolean("combinedSignalEnabled", false)
		wifiVisibleChanged()

		if (key.isNotEmpty() && key[0] == "notificationAreaMultiRow") { //WHY we check the old value? because if prefs is empty it will fill it up and count an unwanted change
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
			// Nothing parseable is stored: the row's reset icon clears the key
			// outright rather than writing a default back. Falling through to
			// the 0f the reader declares would leave the last applied padding
			// in force, so both handles go back to PADDING_DEFAULT - the pair
			// the hook below already treats as "apply nothing".
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
		val networkTrafficModeStr = Xprefs.getString("networkTrafficMode", "0")
		val networkTrafficMode = Integer.parseInt(networkTrafficModeStr)

		val networkTrafficRXTop = Xprefs.getBoolean("networkTrafficRXTop", true)
		val networkTrafficColorful = Xprefs.getBoolean("networkTrafficColorful", false)
		val networkTrafficDLColor = Xprefs.getInt("networkTrafficDLColor", Color.GREEN)
		val networkTrafficULColor = Xprefs.getInt("networkTrafficULColor", Color.RED)
		val networkTrafficOpacity = Xprefs.getSliderInt("networkTrafficOpacity", 100)
		val networkTrafficInterval = Xprefs.getSliderInt("networkTrafficInterval", 1)
		val networkTrafficShowIcons = Xprefs.getBoolean("networkTrafficShowIcons", true)
		val networkTrafficShowInBits = Xprefs.getBoolean("networkTrafficShowInBits", false)

		if (networkOnSBEnabled) {
			networkTrafficPosition = Integer.parseInt(Xprefs.getString("networkTrafficPosition", POSITION_RIGHT.toString()))
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
				networkTrafficShowInBits
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

		// "Make it colorful" and the three colour rows behind it no longer
		// exist, so the clock keeps the system's own colouring - which is
		// exactly what the old else branch left it with. The fields stay: the
		// span builders further down still take them, and null is the value
		// that tells them to paint nothing.
		mAfterClockColor = null
		mBeforeClockColor = null
		clockColor = null

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
				"qsStatusbarPaddings" -> {
					updateShadeHeaderPadding()
					updateStatusbarHeight()
				}
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

	@SuppressLint("DiscouragedApi")
	private fun getIntegerResource(resourceName: String, defaultValue: Int): Int {
		try {
			return mContext.resources.getInteger(SystemUtils.resourceIdOf(resourceName, "integer"))
		} catch (ignored: Throwable) {
			return defaultValue
		}
	}

	private fun updateClock() {
		if (isJetpackClock) {
			// Only the companion views need refreshing, ClockKt handles itself
			runOnMainThread {
				updateClockCompanionViews()
				scheduleCompanionTick(restart = true)
			}
			return
		}
		try {
			mClockView!!.post { //the builtin update method doesn't care about the format. Just the text sadly
				callMethod(getObjectField(mClockView, "mCalendar"), "setTimeInMillis", System.currentTimeMillis())

				mClockView!!.text = callMethod(mClockView, "getSmallTime") as CharSequence
			}
		} catch (ignored: Throwable) {
		}
	}

	@SuppressLint("DiscouragedApi")
	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		StatusBarIconClass = ReflectedClass.of("com.android.internal.statusbar.StatusBarIcon")
		StatusBarIconHolderClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconHolder")
		isLegacyStatusBar = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder").clazz != null

		onPreferenceUpdated()
		val filter = IntentFilter()
		filter.addAction(Constants.ACTION_PROFILE_SWITCH_AVAILABLE)
		mContext.registerReceiver(mAppProfileSwitchReceiver, filter, Context.RECEIVER_EXPORTED)

		//region needed classes
		val ClockClass = ReflectedClass.of("com.android.systemui.statusbar.policy.Clock")
		val PhoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")
		val NotificationIconContainerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.NotificationIconContainer")
		val TunerServiceImplClass = ReflectedClass.of("com.android.systemui.tuner.TunerServiceImpl")

		try {
			val AbstractComposeViewClass = ReflectedClass.of("androidx.compose.ui.platform.AbstractComposeView")
			AbstractComposeViewClass.before("disposeComposition").run { param ->
				if (isMovingClock) {
					param.setResult(null)
				}
			}
		} catch (ignored: Throwable) {
		}

		val ConnectivityCallbackHandlerClass = ReflectedClass.of("com.android.systemui.statusbar.connectivity.CallbackHandler")
		val NotificationIconContainerAlwaysOnDisplayViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel")
		val NotificationIconContainerStatusBarViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel")
		val PrivacyItemClass = ReflectedClass.of("com.android.systemui.privacy.PrivacyItem")
		val PhoneStatusBarViewControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarViewController")
		val KeyguardStateControllerImplClass = ReflectedClass.of("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl")
		val StatusBarIconControllerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl").let {
			if (it.clazz != null) it else ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconControllerImpl")
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

		// The Compose clock is faded out with a Compose modifier, mirror the decision
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
							// Combine takes (allowed, suppressed, disableFlags, cont) on some
							// (allowed, disableFlags, cont) on others.
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

		if (NotificationIconContainerAlwaysOnDisplayViewModelClass.clazz != null) //Viewbinder implementation of the notification icon container
		{
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

		//forcing a refresh on statusbar once the charging chip goes away to avoid layout issues
		//only needed if chip is shown on lockscreen and device is unlocked quickly afterwards


		//region combined signal icons
		TunerServiceImplClass
			.afterConstruction()
			.run { param ->
				mTunerService = param.thisObject
				ReflectedClass.of(getObjectField(param.thisObject, "mObserver").javaClass)
					.after("onChange")
					.run { param2 -> wifiVisibleChanged() }
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
		//endregion

		//region privacy chip
		PrivacyItemClass //A16 //qpr2b2 has removed the constructor, but it does do a distinct thing before using them. (Sunglass + roll emoji goes here)
			.before("hashCode")
			.run { param ->
				if (HidePrivacyChip) {
					setObjectField(param.thisObject, "paused", true)
				}
			}
		//endregion

		//region SB Padding
		PhoneStatusBarViewClass
			.afterConstruction()
			.run { param -> mPhoneStatusbarView = param.thisObject as FrameLayout }

		PhoneStatusBarViewClass
			.after("updateStatusBarHeight")
			.run { param ->
				@SuppressLint("DiscouragedApi")
				val sbContentsView = (param.thisObject as View).findViewById<View>(SystemUtils.idOf("status_bar_contents"))
				mStatusBarContents = sbContentsView
				applyStatusBarContentPadding(sbContentsView)
			}
		//endregion

		//region multi row statusbar
		//bypassing the max icon limit during measurement
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
					setHeights()
				}
			}
		//endregion

		//update statusbar
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
					setHeights()
					val sbContents = mStatusBarContents ?: mPhoneStatusbarView?.findViewById<View>(SystemUtils.idOf("status_bar_contents"))?.also { mStatusBarContents = it }
					sbContents?.let { applyStatusBarContentPadding(it) }
					updateShadeHeaderPadding()
				}
				mainHandler.postDelayed({
					if (BatteryBarView.hasInstance()) {
						BatteryBarView.getInstance()?.let { refreshBatteryBar(it) }
					}
				}, 2000L)
			}

		//stealing a working activity starter
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

				mView.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
					applyShadeHeaderPaddingOnly(v)
				}

				mView.findViewById<View>(SystemUtils.idOf("clock")).setOnClickListener(clickListener)
				mView.findViewById<View>(SystemUtils.idOf("clock")).setOnLongClickListener(clickListener)

				mView.findViewById<View>(SystemUtils.idOf("date")).setOnClickListener(clickListener)
				mView.findViewById<View>(SystemUtils.idOf("date")).setOnLongClickListener(clickListener)

				// The build controls the gestures now - the Compose path below must leave them alone.
				legacyTapListenerAttached = true
			}

		// Compose shade header
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

		//modding clock, adding additional objects,
		PhoneStatusBarViewControllerClass
			.after("onViewAttached")
			.run { param ->
				mClockView = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("clock"))
				updateClockColor()

				mPhoneStatusbarView!!.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
					setHeights()
				}

				mStatusBarContents = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_contents"))

				mStatusbarStartSide = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_start_side_except_heads_up"))

				mSystemIconArea = mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("statusIcons"))

				if (isJetpackClock) {
					mJetpackClockView = findComposeView(mStatusbarStartSide)
				}

				createCenterIconArea()

				makeLeftSplitArea()

				if (BBarEnabled) //in case we got the config but view wasn't ready yet
				{
					placeBatteryBar()
				}

				if (VolteIconEnabled || VowifiIconEnabled) //in case we got the config but context wasn't ready yet
				{
					initVoData()
				}

				if (networkOnSBEnabled) {
					networkTrafficSB = NetworkTraffic.getInstance(mContext, true)
					placeNTSB()
				}

				if (mNotificationIconContainer!!.childCount == 0) {
					mNotificationContainerContainer!!.visibility = View.GONE
				}
				setHeights()

				placeClock()
			}

		//clock mods
		// ClockKt is what says the status-bar clock lives in a ComposeView.
		val hasComposeClock = ReflectedClass.ofIfPossible("com.android.systemui.clock.ui.composable.ClockKt").clazz != null
		if (hasComposeClock) {
			try {
				val clockInteractorClass = ReflectedClass.of("com.android.systemui.clock.domain.interactor.ClockInteractor")
				clockInteractorClass.after("getClockTextFormatString").run { param ->
					val orig: String = param.getResult()

					// Force leading zero for 24-hour format (e.g. 00:23 instead of 0:23).
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
				// leave the legacy one with SystemUI's own (time-only) text on compose clock
				if (isJetpackClock) return@run

				setObjectField(param.thisObject, "mAmPmStyle", AM_PM_STYLE_GONE)
				setObjectField(param.thisObject, "mShowSeconds", mShowSeconds)
			}

		ClockClass
			.after("getSmallTime")
			.run { param ->
				if (param.thisObject != mClockView)
					return@run //We don't want custom format in QS header. do we?
				if (isJetpackClock) return@run

				val result = SpannableStringBuilder()
				result.append(getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor)) //before clock
				val clockText = SpannableStringBuilder.valueOf(param.getResult<CharSequence>()) //THE clock
				if (clockColor != null) {
					clockText.setSpan(NetworkTraffic.TrafficStyle(clockColor!!), 0, clockText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
				}
				result.append(clockText)
				if (mAmPmStyle != AM_PM_STYLE_GONE) {
					result.append(getFormattedString("\$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor))
				}
				result.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor)) //after clock

				if (getAdditionalInstanceField(param.thisObject, "stringFormatCallBack") == null) {
					val callback = object : FormattedStringCallback {
						override fun onRefreshNeeded() {
							if (!mShowSeconds) //don't update again if it's going to do it every second anyway
								updateClock()
						}
					}

					stringFormatter.registerCallback(callback)
					setAdditionalInstanceField(param.thisObject, "stringFormatCallBack", callback)
				}
				param.setResult(result)
			}

		//using clock colors for network traffic and battery bar
		ClockClass
			.after("onDarkChanged")
			.run { param ->
				if (param.thisObject != mClockView)
					return@run //We don't want colors of QS header. only statusbar

				updateClockColor()
				if (BatteryBarView.hasInstance()) {
					refreshBatteryBar(BatteryBarView.getInstance()!!)
				}
			}

		//region mobile roaming
		val MobileIconsInteractorImplClass = ReflectedClass.of("com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl")

		//we must use the classes defined in the apk. using our own will fail
		val ReadonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow")

		MobileIconsInteractorImplClass
			.after("getMobileConnectionInteractorForSubId")
			.run { param ->
				if (HideRoamingState) {
					setObjectField(param.getResult<Any?>(), "isRoaming", ReadonlyStateFlowClass.clazz!!.constructors[0].newInstance(getStateFlowImplOf(false)))
				}
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

		// Keep the TextViews on the same light/dark colour as compose clock
		mBeforeClockView?.setTextColor(currentClockColor)
		mAfterClockView?.setTextColor(currentClockColor)

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

	private fun getDimensionPixelSize(view: View?, resName: String, defaultValue: Int): Int {
		return try {
			(view?.resources ?: mContext?.resources)?.getDimensionPixelSize(SystemUtils.dimenIdOf(resName)) ?: defaultValue
		} catch (ignored: Throwable) {
			defaultValue
		}
	}

	private fun getDisplayCutoutSafeInset(view: View?, isStart: Boolean): Int {
		return try {
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
	}

	private fun applyStatusBarContentPadding(sbContentsView: View) {
		val rotation = getDisplayRotation(sbContentsView)
		val screenWidth = sbContentsView.context.resources.displayMetrics.widthPixels
		val isLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)

		val defaultStart = getDimensionPixelSize(sbContentsView, "status_bar_padding_start", 0)
		val defaultEnd = getDimensionPixelSize(sbContentsView, "status_bar_padding_end", 0)

		val cutoutStart = getDisplayCutoutSafeInset(sbContentsView, true)
		val cutoutEnd = getDisplayCutoutSafeInset(sbContentsView, false)

		val parentPaddingStart = mPhoneStatusbarView?.paddingStart ?: 0
		val parentPaddingEnd = mPhoneStatusbarView?.paddingEnd ?: 0

		val stockStart = if (parentPaddingStart >= cutoutStart) defaultStart else Math.max(cutoutStart - parentPaddingStart, defaultStart)
		val stockEnd = if (parentPaddingEnd >= cutoutEnd) defaultEnd else Math.max(cutoutEnd - parentPaddingEnd, defaultEnd)

		val paddingStart: Int
		val paddingEnd: Int

		if (isLandscape) {
			val cutoutOnStart = isCutoutOnStart(cutoutStart, cutoutEnd, rotation)
			if (cutoutOnStart) {
				paddingStart = stockStart
				paddingEnd = if (LandscapeSBPaddingEnd != PADDING_DEFAULT) Math.round(LandscapeSBPaddingEnd * screenWidth / 100f) else stockEnd
			} else {
				paddingStart = if (LandscapeSBPaddingStart != PADDING_DEFAULT) Math.round(LandscapeSBPaddingStart * screenWidth / 100f) else stockStart
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
				if (LandscapeSBPaddingStart != PADDING_DEFAULT) Math.round(LandscapeSBPaddingStart * 2.0f * screenWidth / 100f) else defaultLeft
			}
			val paddingEnd = if (cutoutOnStart) {
				if (LandscapeSBPaddingEnd != PADDING_DEFAULT) Math.round(LandscapeSBPaddingEnd * 2.0f * screenWidth / 100f) else defaultRight
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

		mNotificationContainerContainer = LinearLayout(mContext)
		mNotificationContainerContainer!!.clipChildren = false //allowing headsup icon to go beyond

		if (mLeftVerticalSplitContainer == null) {
			mLeftVerticalSplitContainer = LinearLayout(mContext)
			mLeftVerticalSplitContainer!!.clipChildren = false //allowing headsup icon to go beyond
		} else {
			mLeftVerticalSplitContainer!!.removeAllViews()
			if (mLeftVerticalSplitContainer!!.parent != null)
				(mLeftVerticalSplitContainer!!.parent as ViewGroup).removeView(mLeftVerticalSplitContainer!!)
		}

		mLeftVerticalSplitContainer!!.orientation = LinearLayout.VERTICAL
		val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		setLeftSplitAreaMargins(lp)

		mLeftVerticalSplitContainer!!.layoutParams = lp
		mLeftVerticalSplitContainer!!.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
			setHeights()
		}

		val layoutTransition = LayoutTransition()
		layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
		layoutTransition.setDuration(200)
		mLeftVerticalSplitContainer!!.layoutTransition = layoutTransition

		mLeftExtraRowContainer = ShyLinearLayout(mContext)
		if (!multiRowClockBottom) {
			mLeftVerticalSplitContainer!!.addView(mLeftExtraRowContainer!!, 0)
		}

		val parent = mNotificationIconContainer!!.parent as ViewGroup

		parent.addView(mLeftVerticalSplitContainer!!, parent.indexOfChild(mNotificationIconContainer!!))
		parent.removeView(mNotificationIconContainer!!)
		mLeftVerticalSplitContainer!!.addView(mNotificationContainerContainer!!)

		if (multiRowClockBottom) {
			mLeftVerticalSplitContainer!!.addView(mLeftExtraRowContainer!!)
		}

		repositionOngoingChip()

		mNotificationContainerContainer!!.addView(mNotificationIconContainer!!)

		(mNotificationIconContainer!!.layoutParams as LinearLayout.LayoutParams).weight = 100f
		mNotificationIconContainer!!.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
			override fun onChildViewAdded(parent: View, child: View) {
				mNotificationContainerContainer!!.visibility = View.VISIBLE
				setHeights()
			}

			override fun onChildViewRemoved(parent: View, child: View) {
				if (mNotificationIconContainer!!.childCount == 0) {
					mNotificationContainerContainer!!.visibility = View.GONE
					setHeights()
				}
			}
		})

		(mStatusbarStartSide!!.parent as View).layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		mStatusbarStartSide!!.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
		mLeftVerticalSplitContainer!!.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
	}

	private fun repositionOngoingChip() {
		if (isJetpackClock) return // In Android 17, the ComposeView contains the Clock, so we can't move it!
		val ongoingChipComposeView = findComposeView(mPhoneStatusbarView!!.findViewById(SystemUtils.idOf("status_bar_start_side_except_heads_up")))
		reAddView(mNotificationContainerContainer!!, ongoingChipComposeView)
	}

	private fun findComposeView(parent: ViewGroup?): View? {
		if (parent == null) return null
		for (i in 0 until parent.childCount) {
			val child = parent.getChildAt(i)
			if (child != null && child.javaClass.name.endsWith("ComposeView"))
				return child
		}
		return null
	}

	private fun isNotificationMultiRowActive(): Boolean {
		return notificationAreaMultiRow && !isLandscapeStatusBar()
	}

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
		val notifContainer = mNotificationContainerContainer ?: return
		val extraRow = mLeftExtraRowContainer ?: return
		val notifIcons = mNotificationIconContainer ?: return

		val statusbarHeight = getStatusbarContentHeight()
		if (statusbarHeight <= 0) return

		setLeftSplitAreaMargins(mLeftVerticalSplitContainer)

		val splitHeight = Math.max(1, statusbarHeight / 2)
		val hasExtraRow = extraRow.visibility == View.VISIBLE
		var hasNotificationRowContent = notifIcons.visibility == View.VISIBLE && notifIcons.childCount > 0
		for (i in 0 until notifContainer.childCount) {
			if (hasNotificationRowContent) break
			val child = notifContainer.getChildAt(i)
			hasNotificationRowContent = child != notifIcons && child.visibility == View.VISIBLE &&
				Math.max(child.width, child.measuredWidth) > 0 &&
				Math.max(child.height, child.measuredHeight) > 0
		}
		val splitRows = isNotificationMultiRowActive() && hasExtraRow && hasNotificationRowContent

		val notificationVisibility = if (hasNotificationRowContent) View.VISIBLE else View.INVISIBLE
		if (notifContainer.visibility != notificationVisibility) {
			notifContainer.visibility = notificationVisibility
		}
		setHeight(notifContainer, if (splitRows) splitHeight else ViewGroup.LayoutParams.MATCH_PARENT)
		setHeight(extraRow, if (splitRows) splitHeight else ViewGroup.LayoutParams.MATCH_PARENT)
		setHeight(notifIcons, ViewGroup.LayoutParams.MATCH_PARENT)
		if (networkOnSBEnabled && networkTrafficSB != null) {
			setHeight(networkTrafficSB, statusbarHeight / (if (networkTrafficPosition == POSITION_LEFT && splitRows) 2 else 1))
		}
	}

	private fun getStatusbarContentHeight(): Int {
		return Math.max(0, getPhoneStatusBarHeight() - getStatusBarContentsTopPadding() - getStatusBarContentTopInset())
	}

	private fun getPhoneStatusBarHeight(): Int {
		var height = if (isLandscapeStatusBar()) {
			getTargetPhoneStatusBarHeight()
		} else {
			Math.max(getTargetPhoneStatusBarHeight(), getStatusBarInsetsHeight())
		}
		if (height <= 0) {
			val sbContents = mStatusBarContents ?: mPhoneStatusbarView?.findViewById<View>(SystemUtils.idOf("status_bar_contents"))?.also { mStatusBarContents = it }
			height = Math.max(getViewHeight(mPhoneStatusbarView), getViewHeight(sbContents))
		}
		return height
	}

	private fun getStatusBarInsetsHeight(): Int {
		return try {
			mPhoneStatusbarView?.rootWindowInsets
				?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars())
				?.top ?: 0
		} catch (ignored: Throwable) {
			0
		}
	}

	private fun getTargetPhoneStatusBarHeight(): Int {
		return try {
			val context = mPhoneStatusbarView?.context ?: mContext
			val landscape = isLandscapeStatusBar()
			val config = android.content.res.Configuration(context.resources.configuration)
			config.orientation = if (landscape) {
				android.content.res.Configuration.ORIENTATION_LANDSCAPE
			} else {
				android.content.res.Configuration.ORIENTATION_PORTRAIT
			}
			val orientationContext = context.createConfigurationContext(config)
			getAndroidDimensionPixelSize(
				orientationContext,
				if (landscape) "status_bar_height_landscape" else "status_bar_height_portrait",
				getAndroidDimensionPixelSize(orientationContext, "status_bar_height", 0)
			)
		} catch (ignored: Throwable) {
			0
		}
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
		val sbContents = mStatusBarContents ?: mPhoneStatusbarView?.findViewById<View>(SystemUtils.idOf("status_bar_contents"))?.also { mStatusBarContents = it }
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

	private fun getAndroidDimensionPixelSize(context: Context, resourceName: String, defaultValue: Int): Int {
		return try {
			val resId = context.resources.getIdentifier(resourceName, "dimen", "android")
			if (resId == 0) defaultValue else context.resources.getDimensionPixelSize(resId)
		} catch (ignored: Throwable) {
			defaultValue
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

			//noinspection JavaReflectionMemberAccess
			setObjectField(statusbarIcon, "user", UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(0))
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
		val iconFiled = arrayOfNulls<String>(1)
		StatusBarIconHolderClass!!.clazz!!.fields.forEach { field ->
			if (field.name.lowercase(Locale.getDefault()).contains("icon"))
				iconFiled[0] = field.name
		}

		setObjectField(holder, iconFiled[0], statusbarIcon)

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

				//noinspection DataFlowIssue
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
			//noinspection DataFlowIssue
			SystemUtils.TelephonyManager()!!.unregisterTelephonyCallback(voDataCallback)
			telephonyCallbackRegistered = false
		} catch (ignored: Exception) {
		}
		removeSBIconSlot(VO_LTE_SLOT)
		removeSBIconSlot(VO_WIFI_SLOT)
	}

	private inner class serverStateCallback : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
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
					networkTrafficSB!!.setPadding(rightClockPadding, 0, leftClockPadding, 0)
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
	// true when xml header controls the gestures
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

	/** True when this gesture was handled here - skips SystemUI's own clock launch. */
	private fun interceptComposeHeaderTap(): Boolean {
		val view = composeTapView?.get() ?: return false
		val downAt = composeTapDownAt
		val upAt = composeTapUpAt
		composeTapDownAt = 0L // Consume it - later click cannot reuse it.
		composeTapUpAt = 0L
		// Leave SystemUI alone when the XML header above controls the gestures
		if (legacyTapListenerAttached || downAt == 0L || upAt < downAt) return false
		if (System.currentTimeMillis() - upAt > 2000) return false

		val onClock = composeElementUnder(view, "com.android.systemui:id/expanded_header_clock", composeTapX, composeTapY)
		val onDate = composeElementUnder(view, "com.android.systemui:id/expanded_shade_header_day_date", composeTapX, composeTapY)
		if (!onClock && !onDate) return false

		return when {
			// Long press on either element opens Date & time settings.
			upAt - downAt >= ViewConfiguration.getLongPressTimeout() -> {
				val intent = Intent(Intent.ACTION_MAIN)
					.setClassName("com.android.settings", "com.android.settings.Settings\$DateTimeSettingsActivity")
				callMethod(mActivityStarter, "startActivity", intent, true /* dismissShade */)
				true
			}
			// Date tap: calendar; a clock tap: alarm clock.
			onDate -> {
				val today = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
				today.appendPath(System.currentTimeMillis().toString())
				callMethod(mActivityStarter, "postStartActivityDismissingKeyguard", Intent(Intent.ACTION_VIEW, today.build()), 0)
				true
			}
			else -> false
		}
	}

	// True - (x, y) is inside the Compose header element
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
	internal inner class ClickListener : View.OnClickListener, View.OnLongClickListener {
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
				callMethod(mActivityStarter, "startActivity", mIntent, true /* dismissShade */)
				return true
			}
			return false
		}
	}
	//endregion

	//region clock and date related
	/**
	 * The status-bar clock text size, in px. The Compose clock and the legacy
	 * Clock TextView share the same metric. Falls back to dimension.
	 */
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

	// The clock's typeface, built the way Compose builds it for [COMPOSE_CLOCK_FAMILY].
	// Falls back to legacy typeface where [COMPOSE_CLOCK_FAMILY] is not declared
	private fun clockTypeface(): Typeface {
		val composeClock = Typeface.create(COMPOSE_CLOCK_FAMILY, Typeface.NORMAL)
		val family = if (composeClock.getSystemFontFamilyName() == COMPOSE_CLOCK_FAMILY) {
			composeClock
		} else {
			mClockView?.typeface ?: Typeface.DEFAULT
		}
		return Typeface.create(family, CLOCK_FONT_WEIGHT, false)
	}

	// One flanking TextView. Height is MATCH_PARENT with CENTER_VERTICAL, like the Compose clock
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
			layoutParams = LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			).apply { gravity = Gravity.CENTER_VERTICAL }
		}
	}

	/**
	 * Re-parents the Compose clock into a horizontal container with
	 * [mBeforeClockView] and [mAfterClockView]. Returns the container, which
	 * is what [placeClock] moves.
	 */
	private fun ensureClockContainer(clock: View): LinearLayout {
		val existing = mClockContainer
		if (existing != null && clock.parent === existing && existing.parent != null) {
			return existing
		}

		// Drop any previous container so it does not linger in the bar.
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

		// AbstractComposeView hook in onPackageLoaded watches isMovingClock.
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

	/**
	 * wake up just after each flip: every second when seconds are shown, otherwise every minute.
	 *
	 * [restart] drops the pending wake up and re-anchors it to [now], which a preference
	 * change needs since it may have just changed how long a period is.
	 */
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

	// Recomputes the before/after strings (variables resolved by [StringFormatter])
	// AM/PM belongs to the after view
	private fun updateClockCompanionViews() {
		val before = mBeforeClockView
		val after = mAfterClockView
		if (before == null || after == null) return

		// the text always carries the zero-width pad and is never empty.
		val beforeContent = getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor)
		before.text = fullHeightLineBox(beforeContent)
		before.setTextColor(currentClockColor)

		val afterText = SpannableStringBuilder()
		if (mAmPmStyle != AM_PM_STYLE_GONE) {
			// No separator: the marker sits flush against the time ("3:04PM").
			afterText.append(getFormattedString("\$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor))
		}
		afterText.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor))
		after.text = fullHeightLineBox(afterText)
		after.setTextColor(currentClockColor)

		// Also hide when SystemUI hides the clock itself
		before.visibility = if (mStatusBarClockVisible && beforeContent.isNotEmpty()) View.VISIBLE else View.GONE
		after.visibility = if (mStatusBarClockVisible && afterText.isNotEmpty()) View.VISIBLE else View.GONE
	}

	private fun placeClock() {
		var viewToMove: View? = mClockView
		if (isJetpackClock) {
			if (mJetpackClockView != null && mJetpackClockView!!.parent == null) {
				mJetpackClockView = null // View is detached or destroyed, find it again
			}
			if (mJetpackClockView == null) {
				// The clock is natively created in mStatusbarStartSide, search there.
				// If it's already moved, mJetpackClockView would not be null.
				// We don't search the right side to avoid accidentally matching the battery ComposeView.
				mJetpackClockView = findComposeView(mStatusbarStartSide)
			}
			// Move the container rather than the bare clock
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
					} else 0
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
				// Leaving index as null appends it to the very end (right-most element)
			}
		}

		if (targetArea == null || targetArea == parent) return

		// Moving the container detaches its Compose child from the window
		// isMovingClock is the flag the AbstractComposeView hook watches to stop that.
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

	/**
	 * Returns [o] if it is SystemUI's `StatusBarDisableFlagsVisibilityModel`,
	 * else null. Picks the model out of either shape of the isClockVisible
	 * combine without depending on argument order.
	 */
	private fun asDisableFlagsVisibilityModel(o: Any?): Any? =
		if (o != null && o.javaClass.name.endsWith("StatusBarDisableFlagsVisibilityModel")) o else null

	private val stringFormatter = StringFormatter()

	// Pads [content] with a zero-width space at the TextView's own (full) size.
	private fun fullHeightLineBox(content: CharSequence): CharSequence =
		SpannableStringBuilder(ZERO_WIDTH_SPACE).append(content)

	private fun getFormattedString(dateFormat: String, small: Boolean, @Nullable @ColorInt textColor: Int?): CharSequence {
		if (dateFormat.isEmpty()) return ""

		//There's some format to work on
		val formatted = SpannableStringBuilder(stringFormatter.formatString(dateFormat))

		if (small) {
			//small size requested
			val style: CharacterStyle = RelativeSizeSpan(0.7f)
			formatted.setSpan(style, 0, formatted.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		if (textColor != null) {
			formatted.setSpan(NetworkTraffic.TrafficStyle(textColor), 0, formatted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}

		return formatted
	}

	//endregion
	//region callbacks
	fun interface ClockVisibilityCallback : Callback {
		fun OnVisibilityChanged(isVisible: Boolean)
	}

	private fun wifiVisibleChanged() {
		try { //don't crash the system if failed
			//inspired from from TunerServiceImpl#reloadAll
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

	fun interface StatusbarTextColorCallback {
		fun onTextColorChanged(textColor: Int)
	}
}
