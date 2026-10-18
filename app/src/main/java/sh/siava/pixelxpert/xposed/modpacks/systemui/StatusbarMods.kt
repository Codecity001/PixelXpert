package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
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
import android.view.Display
import android.view.DisplayCutout
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewParent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import io.github.libxposed.api.XposedModuleInterface
import org.objenesis.ObjenesisHelper
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.Constants
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.xposed.XPrefs.Xprefs
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.modpacks.android.StatusbarSize
import sh.siava.pixelxpert.xposed.utils.NetworkTraffic
import sh.siava.pixelxpert.xposed.utils.ShyLinearLayout
import sh.siava.pixelxpert.xposed.utils.StringFormatter
import sh.siava.pixelxpert.xposed.utils.StringFormatter.FormattedStringCallback
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.dimenIdOf
import sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf
import sh.siava.pixelxpert.xposed.utils.SystemUtils.resourceIdOf
import sh.siava.pixelxpert.xposed.utils.batteryStyles.BatteryBarView
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getBooleanField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setAdditionalInstanceField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectionTools.reAddView
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger.log
import sh.siava.pixelxpert.xposed.utils.toolkit.ObjectTools.getStateFlowImplOf
import sh.siava.pixelxpert.xposed.utils.toolkit.ResourceTools
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.Executor
import java.util.regex.Pattern
import javax.security.auth.callback.Callback
import kotlin.math.roundToInt

@SystemUIModPack
class StatusbarMods(context: Context) : XposedModPack(context) {

    private val leftClockPadding: Int
    private val rightClockPadding: Int

    private var mBeforeClockColor: Int? = null
    private var mAfterClockColor: Int? = null
    private var clockColor: Int? = null

    private var networkTrafficSB: NetworkTraffic? = null

    private var mActivityStarter: Any? = null
    private var aodnic: Any? = null
    private var sbnic: Any? = null

    private var mStatusbarStartSide: ViewGroup? = null
    private var mCenteredIconArea: View? = null
    private var mSystemIconArea: LinearLayout? = null
    private var mClockView: TextView? = null
    private var mJetpackClockView: View? = null

    private var mNotificationIconContainer: ViewGroup? = null
    private var mNotificationIconArea: ViewGroup? = null
    private var mNotificationContainerContainer: LinearLayout? = null
    private var mLeftVerticalSplitContainer: FrameLayout? = null
    private var mLeftExtraRowContainer: LinearLayout? = null
    private var mOngoingChipComposeView: View? = null

    private var mShadeHeaderController: Any? = null
    private var mShadeHeaderView: View? = null
    private var mPhoneStatusbarView: FrameLayout? = null
    private var mStatusBarContents: View? = null
    private var mLastAppliedStatusBarHeight: Int = 0
    private val mSetHeightsRunnable = Runnable { setHeights() }

    private val voDataExec: Executor = Executor { it.run() }
    private var isLegacyStatusBar = false
    private var mCommandQueue: Any? = null
    private var mStatusBarIconController: Any? = null
    private var statusBarIconClass: ReflectedClass? = null
    private var statusBarIconHolderClass: ReflectedClass? = null
    private var volteStatusbarIcon: Any? = null
    private var vowifiStatusbarIcon: Any? = null
    private var volteStatusbarIconHolder: Any? = null
    private var vowifiStatusbarIconHolder: Any? = null
    private var mAppSwitchStatusbarIconHolder: Any? = null

    private var telephonyCallbackRegistered = false
    private var lastVolteAvailable = false
    private var lastVowifiAvailable = false
    private val voDataCallback = ServerStateCallback()

    private var mWifiVisible = false
    private var mTunerService: Any? = null

    private val stringFormatter = StringFormatter()

    private val mAppProfileSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Constants.ACTION_PROFILE_SWITCH_AVAILABLE == intent.action) {
                val isAvailable = intent.getBooleanExtra("available", false)
                if (isAvailable && statusbarAppSwitchIconEnabled) {
                    setSBIcon(APP_SWITCH_SLOT, mAppSwitchStatusbarIconHolder, null)
                } else {
                    removeSBIconSlot(APP_SWITCH_SLOT)
                }
            }
        }
    }

    companion object {
        const val POSITION_LEFT: Int = 0
        const val POSITION_CENTER: Int = 1
        const val POSITION_RIGHT: Int = 2
        const val POSITION_LEFT_EXTRA_LEVEL: Int = 3

        private const val AM_PM_STYLE_SMALL = 1
        private const val AM_PM_STYLE_GONE = 2

        private val GREGORIAN_PATTERN = Pattern.compile("\\$G([A-Za-z]+)")

        const val ICON_HIDE_LIST: String = "icon_blacklist"
        const val APP_SWITCH_SLOT: String = "app_switch"

        private const val VO_LTE_SLOT = "volte"
        private const val VO_WIFI_SLOT = "vowifi"

        private const val PADDING_DEFAULT = -0.5f

        @JvmField
        var isMovingClock: Boolean = false

        private var isJetpackClock = false
        private var clockPosition = POSITION_LEFT
        private var clockMultiRowStartOffset = 2
        private var mAmPmStyle = AM_PM_STYLE_GONE
        private var mShowSeconds = false
        private var mStringFormatBefore = ""
        private var mStringFormatAfter = ""
        private var mBeforeSmall = true
        private var mAfterSmall = true

        private var networkOnSBEnabled = false
        private var networkTrafficPosition = POSITION_LEFT

        private var bBarEnabled = false
        private var bBarColorful = false
        private var bbOnlyWhileCharging = false
        private var bbOnBottom = false
        private var bbSetCentered = false
        private var bbOpacity = 100
        private var bBarHeight = 10
        private var batteryLevels: List<Float> = listOf(20f, 40f)
        private var batteryColors = intArrayOf(Color.RED, Color.YELLOW)
        private var chargingColor = Color.WHITE
        private var fastChargingColor = Color.WHITE
        private var powerSaveColor = Color.parseColor("#FFBF00")
        private var indicateCharging = false
        private var indicateFastCharging = false
        private var indicatePowerSave = false
        private var bBarTransitColors = false
        private var bbAnimateCharging = false

        private var hidePrivacyChip = false
        private var notificationAreaMultiRow = false
        private var statusbarHeightFactor = 100
        private var notificationAODIconLimit = 3
        private var notificationIconLimit = 4

        private var currentClockColor = 0
        private val clockVisibilityCallbacks = ArrayList<ClockVisibilityCallback>()
        private val mTextColorCallbacks = ArrayList<StatusbarTextColorCallback>()

        private var sbPaddingStart = PADDING_DEFAULT
        private var sbPaddingEnd = PADDING_DEFAULT
        private var landscapeSBPaddingStart = PADDING_DEFAULT
        private var landscapeSBPaddingEnd = PADDING_DEFAULT

        private var volteIconEnabled = false
        private var vowifiIconEnabled = false

        private var combineSignalIcons = false
        private var hideRoamingState = false
        private var statusbarAppSwitchIconEnabled = false

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

    init {
        rightClockPadding = mContext.resources.getDimensionPixelSize(dimenIdOf("status_bar_clock_starting_padding"))
        leftClockPadding = mContext.resources.getDimensionPixelSize(dimenIdOf("status_bar_left_clock_end_padding"))
    }

    private fun initSwitchIcon() {
        try {
            val appSwitchIcon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_app_switch)
            val appSwitchStatusbarIcon = getStatusbarIconFor(appSwitchIcon, APP_SWITCH_SLOT)
            mAppSwitchStatusbarIconHolder = getStatusbarIconHolderFor(appSwitchStatusbarIcon)
        } catch (_: Throwable) {}
    }

    override fun onPreferenceUpdated(vararg key: String) {
        statusbarAppSwitchIconEnabled = Xprefs.getBoolean("StatusbarAppSwitchIconEnabled", false)
        hidePrivacyChip = Xprefs.getBoolean("HidePrivacyChip", false)
        hideRoamingState = Xprefs.getBoolean("HideRoamingState", false)

        combineSignalIcons = Xprefs.getBoolean("combinedSignalEnabled", false)
        wifiVisibleChanged()

        if (key.isNotEmpty() && key[0] == "notificationAreaMultiRow") {
            val newNotificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false)
            if (newNotificationAreaMultiRow != notificationAreaMultiRow) {
                SystemUtils.killSelf()
            }
        }
        notificationAreaMultiRow = Xprefs.getBoolean("notificationAreaMultiRow", false)
        statusbarHeightFactor = Xprefs.getSliderInt("statusbarHeightFactor", 100)

        notificationIconLimit = try {
            Xprefs.getString("NotificationIconLimit", "")?.trim()?.toInt() ?: getIntegerResource("max_notif_static_icons", 4)
        } catch (_: Throwable) {
            getIntegerResource("max_notif_static_icons", 4)
        }

        notificationAODIconLimit = try {
            Xprefs.getString("NotificationAODIconLimit", "")?.trim()?.toInt() ?: getIntegerResource("max_notif_icons_on_aod", 3)
        } catch (_: Throwable) {
            getIntegerResource("max_notif_icons_on_aod", 3)
        }

        aodnic?.let { setObjectField(it, "maxIcons", notificationAODIconLimit) }
        sbnic?.let { setObjectField(it, "maxIcons", notificationIconLimit) }

        val paddings = Xprefs.getSliderValues("statusbarPaddings", 0)
        if (paddings.size > 1) {
            sbPaddingStart = paddings[0]
            sbPaddingEnd = 100f - paddings[1]
        }

        val qsPaddings = Xprefs.getSliderValues("qsStatusbarPaddings", 0)
        if (qsPaddings.size > 1) {
            landscapeSBPaddingStart = qsPaddings[0]
            landscapeSBPaddingEnd = 100f - qsPaddings[1]
        }

        // BatteryBar Settings
        bBarEnabled = Xprefs.getBoolean("BBarEnabled", false)
        bBarColorful = Xprefs.getBoolean("BBarColorful", false)
        bbOnlyWhileCharging = Xprefs.getBoolean("BBOnlyWhileCharging", false)
        bbOnBottom = Xprefs.getBoolean("BBOnBottom", false)
        bbSetCentered = Xprefs.getBoolean("BBSetCentered", false)
        bbOpacity = Xprefs.getSliderInt("BBOpacity", 100)
        bBarHeight = Xprefs.getSliderInt("BBarHeight", 50)
        bBarTransitColors = Xprefs.getBoolean("BBarTransitColors", false)
        bbAnimateCharging = Xprefs.getBoolean("BBAnimateCharging", false)

        batteryLevels = Xprefs.getSliderValues("batteryWarningRange", 0)
        batteryColors = intArrayOf(
            Xprefs.getInt("batteryCriticalColor", Color.RED),
            Xprefs.getInt("batteryWarningColor", Color.YELLOW)
        )

        indicateFastCharging = Xprefs.getBoolean("indicateFastCharging", false)
        indicatePowerSave = Xprefs.getBoolean("indicatePowerSave", false)
        indicateCharging = Xprefs.getBoolean("indicateCharging", true)

        chargingColor = Xprefs.getInt("batteryChargingColor", Color.GREEN)
        fastChargingColor = Xprefs.getInt("batteryFastChargingColor", Color.BLUE)
        powerSaveColor = Xprefs.getInt("batteryPowerSaveColor", Color.parseColor("#FFBF00"))

        if (bBarEnabled) {
            placeBatteryBar()
        }

        if (BatteryBarView.hasInstance()) {
            refreshBatteryBar(BatteryBarView.getInstance())
        }

        // Network Traffic Settings
        networkOnSBEnabled = Xprefs.getBoolean("networkOnSBEnabled", false)
        val networkTrafficMode = Xprefs.getString("networkTrafficMode", "0")?.toIntOrNull() ?: 0
        val networkTrafficRXTop = Xprefs.getBoolean("networkTrafficRXTop", true)
        val networkTrafficDLColor = Xprefs.getInt("networkTrafficDLColor", Color.GREEN)
        val networkTrafficULColor = Xprefs.getInt("networkTrafficULColor", Color.RED)
        val networkTrafficOpacity = Xprefs.getSliderInt("networkTrafficOpacity", 100)
        val networkTrafficInterval = Xprefs.getSliderInt("networkTrafficInterval", 1)
        val networkTrafficColorful = Xprefs.getBoolean("networkTrafficColorful", false)
        val networkTrafficShowIcons = Xprefs.getBoolean("networkTrafficShowIcons", true)
        val networkTrafficShowInBits = Xprefs.getBoolean("networkTrafficShowInBits", false)

        if (networkOnSBEnabled) {
            networkTrafficPosition = Xprefs.getString("networkTrafficPosition", POSITION_RIGHT.toString())?.toIntOrNull() ?: POSITION_RIGHT
            if (networkTrafficPosition == POSITION_LEFT_EXTRA_LEVEL) {
                Xprefs.edit().putString("networkTrafficPosition", POSITION_LEFT.toString()).apply()
                networkTrafficPosition = POSITION_LEFT
            }

            val thresholdText = Xprefs.getString("networkTrafficThreshold", "10") ?: "10"
            val networkTrafficThreshold = thresholdText.toFloatOrNull()?.roundToInt() ?: 10

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
            networkTrafficSB?.update()
        }
        placeNTSB()

        // Clock Settings
        clockPosition = Xprefs.getString("SBClockLoc", POSITION_LEFT.toString())?.toIntOrNull() ?: POSITION_LEFT
        if (clockPosition == POSITION_LEFT_EXTRA_LEVEL) {
            Xprefs.edit().putString("SBClockLoc", POSITION_LEFT.toString()).apply()
            clockPosition = POSITION_LEFT
        }

        mShowSeconds = Xprefs.getBoolean("SBCShowSeconds", false)
        mAmPmStyle = Xprefs.getString("SBCAmPmStyle", AM_PM_STYLE_GONE.toString())?.toIntOrNull() ?: AM_PM_STYLE_GONE

        mStringFormatBefore = Xprefs.getString("DateFormatBeforeSBC", "") ?: ""
        mStringFormatAfter = Xprefs.getString("DateFormatAfterSBC", "") ?: ""
        mBeforeSmall = Xprefs.getBoolean("BeforeSBCSmall", true)
        mAfterSmall = Xprefs.getBoolean("AfterSBCSmall", true)

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
            val sbcDayOfWeekMode = Xprefs.getString("SBCDayOfWeekMode", "0")?.toIntOrNull() ?: 0
            when (sbcDayOfWeekMode) {
                0 -> {
                    mStringFormatBefore = ""
                    mStringFormatAfter = ""
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

        clockMultiRowStartOffset = Xprefs.getSliderInt("clockMultiRowStartOffset", 2)
        syncJetpackClockSeconds()

        try {
            placeClock()
            updateClock()
        } catch (_: Throwable) {}

        volteIconEnabled = Xprefs.getBoolean("VolteIconEnabled", false)
        vowifiIconEnabled = Xprefs.getBoolean("VowifiIconEnabled", false)

        if (key.isNotEmpty()) {
            when (key[0]) {
                "statusbarPaddings" -> updateStatusbarHeight()
                "qsStatusbarPaddings" -> {
                    updateShadeHeaderPadding()
                    updateStatusbarHeight()
                }
                "NotificationIconLimit" -> applyNotificationIconLimit()
                "VolteIconEnabled", "VowifiIconEnabled" -> {
                    if (volteIconEnabled || vowifiIconEnabled) {
                        initVoData()
                        if (!volteIconEnabled) removeSBIconSlot(VO_LTE_SLOT)
                        if (!vowifiIconEnabled) removeSBIconSlot(VO_WIFI_SLOT)
                    } else {
                        removeVoDataCallback()
                    }
                }
            }
        }
    }

    private fun getIntegerResource(resourceName: String, defaultValue: Int): Int {
        return try {
            mContext.resources.getInteger(resourceIdOf(resourceName, "integer"))
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun updateClock() {
        try {
            mClockView?.let { clock ->
                clock.post {
                    val calendar = getObjectField<Any>(clock, "mCalendar")
                    if (calendar != null) {
                        callMethod<Any>(calendar, "setTimeInMillis", System.currentTimeMillis())
                    }
                    clock.text = callMethod<CharSequence>(clock, "getSmallTime")
                }
            }
        } catch (_: Throwable) {}
    }

    private fun syncJetpackClockSeconds() {
        if (isJetpackClock) {
            try {
                val current = Settings.Secure.getInt(mContext.contentResolver, "clock_seconds", 0)
                val desired = if (mShowSeconds) 1 else 0
                if (current != desired) {
                    Settings.Secure.putInt(mContext.contentResolver, "clock_seconds", desired)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun getDisplayRotation(view: View?): Int {
        try {
            if (view?.display != null) {
                return view.display.rotation
            }
        } catch (_: Throwable) {}
        try {
            val dm = mContext.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                return display.rotation
            }
        } catch (_: Throwable) {}
        try {
            val context = view?.context ?: mPhoneStatusbarView?.context ?: mContext
            if (context.display != null) {
                return context.display.rotation
            }
        } catch (_: Throwable) {}
        return Surface.ROTATION_0
    }

    private fun getDisplayCutoutSafeInset(view: View?, isStart: Boolean): Int {
        try {
            val insets = view?.rootWindowInsets ?: mPhoneStatusbarView?.rootWindowInsets
            var cutout: DisplayCutout? = insets?.displayCutout

            if (cutout == null) {
                var display = view?.display ?: mPhoneStatusbarView?.display
                if (display == null) {
                    val dm = mContext.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
                    display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                }
                cutout = display?.cutout
            }

            if (cutout != null) {
                val isRtl = view?.layoutDirection == View.LAYOUT_DIRECTION_RTL
                return if (isStart) {
                    if (isRtl) cutout.safeInsetRight else cutout.safeInsetLeft
                } else {
                    if (isRtl) cutout.safeInsetLeft else cutout.safeInsetRight
                }
            }
        } catch (_: Throwable) {}
        return 0
    }

    private fun isCutoutOnStart(cutoutStart: Int, cutoutEnd: Int, rotation: Int): Boolean {
        if (cutoutStart > cutoutEnd) return true
        if (cutoutEnd > cutoutStart) return false
        return rotation == Surface.ROTATION_90
    }

    private fun getDimensionPixelSize(view: View?, resName: String, defaultValue: Int): Int {
        return try {
            (view?.resources ?: mContext.resources).getDimensionPixelSize(dimenIdOf(resName))
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun applyStatusBarContentPadding(sbContentsView: View?) {
        if (sbContentsView == null) return

        val rotation = getDisplayRotation(sbContentsView)
        val screenWidth = sbContentsView.context.resources.displayMetrics.widthPixels
        val isLandscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

        val defaultStart = getDimensionPixelSize(sbContentsView, "status_bar_padding_start", 0)
        val defaultEnd = getDimensionPixelSize(sbContentsView, "status_bar_padding_end", 0)

        val cutoutStart = getDisplayCutoutSafeInset(sbContentsView, true)
        val cutoutEnd = getDisplayCutoutSafeInset(sbContentsView, false)

        val parentPaddingStart = mPhoneStatusbarView?.paddingStart ?: 0
        val parentPaddingEnd = mPhoneStatusbarView?.paddingEnd ?: 0

        val stockStart = if (parentPaddingStart >= cutoutStart) {
            defaultStart
        } else {
            maxOf(cutoutStart - parentPaddingStart, defaultStart)
        }
        val stockEnd = if (parentPaddingEnd >= cutoutEnd) {
            defaultEnd
        } else {
            maxOf(cutoutEnd - parentPaddingEnd, defaultEnd)
        }

        val paddingStart: Int
        val paddingEnd: Int

        if (isLandscape) {
            val cutoutOnStart = isCutoutOnStart(cutoutStart, cutoutEnd, rotation)
            if (cutoutOnStart) {
                paddingStart = stockStart
                paddingEnd = if (landscapeSBPaddingEnd >= 0) {
                    (landscapeSBPaddingEnd * screenWidth / 100f).roundToInt()
                } else {
                    stockEnd
                }
            } else {
                paddingStart = if (landscapeSBPaddingStart >= 0) {
                    (landscapeSBPaddingStart * screenWidth / 100f).roundToInt()
                } else {
                    stockStart
                }
                paddingEnd = stockEnd
            }
        } else {
            paddingStart = if (sbPaddingStart != PADDING_DEFAULT) {
                (sbPaddingStart * screenWidth / 100f).roundToInt()
            } else {
                stockStart
            }
            paddingEnd = if (sbPaddingEnd != PADDING_DEFAULT) {
                (sbPaddingEnd * screenWidth / 100f).roundToInt()
            } else {
                stockEnd
            }
        }

        if (sbContentsView.paddingStart != paddingStart || sbContentsView.paddingEnd != paddingEnd) {
            sbContentsView.setPaddingRelative(
                paddingStart,
                sbContentsView.paddingTop,
                paddingEnd,
                sbContentsView.paddingBottom
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
                        view.paddingBottom
                    )
                }
                return
            }

            val cutoutStart = getDisplayCutoutSafeInset(view, true)
            val cutoutEnd = getDisplayCutoutSafeInset(view, false)

            val defaultLeft = maxOf(cutoutStart, getDimensionPixelSize(view, "large_screen_shade_header_left_padding", 0))
            val defaultRight = maxOf(cutoutEnd, getDimensionPixelSize(view, "qs_panel_padding", 0))
            val screenWidth = view.context.resources.displayMetrics.widthPixels

            val cutoutOnStart = isCutoutOnStart(cutoutStart, cutoutEnd, rotation)

            val paddingStart: Int
            val paddingEnd: Int

            if (cutoutOnStart) {
                paddingStart = defaultLeft
                paddingEnd = if (landscapeSBPaddingEnd >= 0) {
                    (landscapeSBPaddingEnd * 2.0f * screenWidth / 100f).roundToInt()
                } else {
                    defaultRight
                }
            } else {
                paddingStart = if (landscapeSBPaddingStart >= 0) {
                    (landscapeSBPaddingStart * 2.0f * screenWidth / 100f).roundToInt()
                } else {
                    defaultLeft
                }
                paddingEnd = defaultRight
            }

            if (view.paddingStart != paddingStart || view.paddingEnd != paddingEnd) {
                view.setPaddingRelative(
                    paddingStart,
                    view.paddingTop,
                    paddingEnd,
                    view.paddingBottom
                )
            }
        } catch (_: Throwable) {}
    }

    private fun updateShadeHeaderPadding() {
        val view = mShadeHeaderView ?: return
        view.post {
            try {
                applyShadeHeaderPaddingOnly(view)

                val controller = mShadeHeaderController
                if (controller != null) {
                    val insets = view.rootWindowInsets
                        ?: mPhoneStatusbarView?.rootWindowInsets
                        ?: getObjectField<WindowInsets>(controller, "lastInsets")
                    if (insets != null) {
                        callMethod<Any>(controller, "updateConstraintsForInsets", view, insets)
                    }
                }
                view.requestLayout()
                view.invalidate()
            } catch (_: Throwable) {}
        }
    }

    private fun getStatusBarContentTopInset(): Int {
        val context = mPhoneStatusbarView?.context ?: mContext
        if (!StatusbarSize.isNoCutoutLayoutActive(context)) return 0
        if (isNotificationMultiRowActive() || isLandscapeStatusBar()) return 0

        return ResourceTools.dpToPx(mContext, 6)
    }

    private fun isNotificationMultiRowActive(): Boolean {
        return notificationAreaMultiRow && !isLandscapeStatusBar()
    }

    private fun isLandscapeStatusBar(): Boolean {
        val context = mPhoneStatusbarView?.context ?: mContext
        return context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    @SuppressLint("DiscouragedApi")
    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        isLegacyStatusBar = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder").clazz != null
        onPreferenceUpdated()
        val filter = IntentFilter(Constants.ACTION_PROFILE_SWITCH_AVAILABLE)
        mContext.registerReceiver(mAppProfileSwitchReceiver, filter, Context.RECEIVER_EXPORTED)

        val clockClass = ReflectedClass.of("com.android.systemui.statusbar.policy.Clock")
        val phoneStatusBarViewClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarView")
        val notificationIconContainerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.NotificationIconContainer")
        val tunerServiceImplClass = ReflectedClass.of("com.android.systemui.tuner.TunerServiceImpl")

        try {
            val abstractComposeViewClass = ReflectedClass.of("androidx.compose.ui.platform.AbstractComposeView")
            abstractComposeViewClass.before("disposeComposition").run { runParam ->
                if (isMovingClock) {
                    runParam.result = null
                }
            }
        } catch (_: Throwable) {}

        val connectivityCallbackHandlerClass = ReflectedClass.of("com.android.systemui.statusbar.connectivity.CallbackHandler")
        val notificationIconContainerAlwaysOnDisplayViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel")
        val notificationIconContainerStatusBarViewModelClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel")
        statusBarIconClass = ReflectedClass.of("com.android.internal.statusbar.StatusBarIcon")
        statusBarIconHolderClass = ReflectedClass.of("com.android.systemui.statusbar.phone.StatusBarIconHolder")
        val privacyItemClass = ReflectedClass.of("com.android.systemui.privacy.PrivacyItem")
        val phoneStatusBarViewControllerClass = ReflectedClass.of("com.android.systemui.statusbar.phone.PhoneStatusBarViewController")
        val keyguardStateControllerImplClass = ReflectedClass.of("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl")
        val statusBarIconControllerImplClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl").let {
            if (it.clazz != null) it else ReflectedClass.ofIfPossible("com.android.systemui.statusbar.phone.StatusBarIconControllerImpl")
        }
        val commandQueueClass = ReflectedClass.ofIfPossible("com.android.systemui.statusbar.CommandQueue")
        val shadeHeaderControllerClass = ReflectedClass.of("com.android.systemui.shade.ShadeHeaderController")
        val activityStarterImplClass = ReflectedClass.of("com.android.systemui.statusbar.phone.ActivityStarterImpl")

        keyguardStateControllerImplClass
            .after("notifyKeyguardState")
            .run { runParam ->
                val mKeyguardUpdateMonitor = getObjectField<Any>(runParam.thisObject, "mKeyguardUpdateMonitor")
                val keyguardShowing = getBooleanField(mKeyguardUpdateMonitor, "mKeyguardShowing")
                for (c in clockVisibilityCallbacks) {
                    try {
                        c.OnVisibilityChanged(!keyguardShowing)
                    } catch (_: Throwable) {}
                }
            }

        commandQueueClass
            ?.afterConstruction()
            ?.run { runParam ->
                mCommandQueue = runParam.thisObject
                if (volteIconEnabled || vowifiIconEnabled) {
                    initVoData()
                }
            }

        statusBarIconControllerImplClass
            ?.afterConstruction()
            ?.run { runParam ->
                mStatusBarIconController = runParam.thisObject
                if (mCommandQueue == null) {
                    for (arg in runParam.args) {
                        if (arg != null && arg.javaClass.name.endsWith("CommandQueue")) {
                            mCommandQueue = arg
                            break
                        }
                    }
                }
                if (volteIconEnabled || vowifiIconEnabled) {
                    initVoData()
                }
            }

        if (notificationIconContainerAlwaysOnDisplayViewModelClass?.clazz != null) {
            notificationIconContainerAlwaysOnDisplayViewModelClass
                .afterConstruction()
                .run { runParam ->
                    aodnic = runParam.thisObject
                    setObjectField(runParam.thisObject, "maxIcons", notificationAODIconLimit)
                }

            notificationIconContainerStatusBarViewModelClass
                ?.afterConstruction()
                ?.run { runParam ->
                    sbnic = runParam.thisObject
                    setObjectField(runParam.thisObject, "maxIcons", notificationIconLimit)
                }
        }

        initSwitchIcon()

        tunerServiceImplClass
            .afterConstruction()
            .run { runParam ->
                mTunerService = runParam.thisObject
                val observer = getObjectField<Any>(runParam.thisObject, "mObserver")
                if (observer != null) {
                    ReflectedClass.of(observer.javaClass)
                        .after("onChange")
                        .run { wifiVisibleChanged() }
                }
            }

        tunerServiceImplClass
            .after("addTunable")
            .run { runParam ->
                val arg = runParam.args.getOrNull(1)
                if (arg is Array<*> && arg.isArrayOf<String>() && (arg as? Array<String>)?.contains(ICON_HIDE_LIST) == true) {
                    wifiVisibleChanged()
                } else if (ICON_HIDE_LIST == arg) {
                    wifiVisibleChanged()
                }
            }

        connectivityCallbackHandlerClass
            .after("setWifiIndicators")
            .run { runParam ->
                val arg0 = runParam.args.getOrNull(0) ?: return@run
                val statusIcon = getObjectField<Any>(arg0, "statusIcon") ?: return@run
                val wifiVisible = getBooleanField(statusIcon, "visible")
                if (wifiVisible != mWifiVisible) {
                    mWifiVisible = wifiVisible
                    if (combineSignalIcons) {
                        wifiVisibleChanged()
                    }
                }
            }

        privacyItemClass
            .before("hashCode")
            .run { runParam ->
                if (hidePrivacyChip) {
                    val thisObj = runParam.thisObject ?: return@run
                    setObjectField(thisObj, "paused", true)
                }
            }

        phoneStatusBarViewClass
            .afterConstruction()
            .run { runParam -> mPhoneStatusbarView = runParam.thisObject as? FrameLayout }

        phoneStatusBarViewClass
            .after("onFinishInflate")
            .run { runParam ->
                mPhoneStatusbarView = runParam.thisObject as? FrameLayout
                scheduleHeightsUpdate()
            }

        phoneStatusBarViewClass
            .after("onAttachedToWindow")
            .run { runParam ->
                mPhoneStatusbarView = runParam.thisObject as? FrameLayout
                scheduleHeightsUpdate()
            }

        phoneStatusBarViewClass
            .after("updateStatusBarHeight")
            .run { runParam ->
                val view = runParam.thisObject as? View ?: return@run
                val sbContentsView = view.findViewById<View>(idOf("status_bar_contents"))
                mStatusBarContents = sbContentsView

                applyPhoneStatusBarViewHeight()
                applyStatusBarContentPadding(sbContentsView)
                applyStatusBarContainerLayout()
            }

        notificationIconContainerClass
            .before("onMeasure")
            .run { runParam ->
                if (runParam.thisObject == mNotificationIconContainer) {
                    setObjectField(runParam.thisObject, "mMaxIcons", notificationIconLimit)
                }
                setObjectField(runParam.thisObject, "mIsStaticLayout", false)
            }

        notificationIconContainerClass
            .after("onMeasure")
            .run { runParam ->
                setObjectField(runParam.thisObject, "mIsStaticLayout", true)
                val view = runParam.thisObject as? View
                if (runParam.thisObject != mNotificationIconContainer && view?.id == idOf("notificationIcons")) {
                    mNotificationIconContainer = runParam.thisObject as? ViewGroup
                }
            }

        notificationIconContainerClass
            .before("setMaxIconsAmount")
            .run { runParam ->
                if (runParam.thisObject == mNotificationIconContainer && runParam.args.isNotEmpty()) {
                    runParam.args[0] = notificationIconLimit
                }
            }

        notificationIconContainerClass
            .after("setMaxIconsAmount")
            .run { runParam ->
                if (runParam.thisObject == mNotificationIconContainer) {
                    scheduleHeightsUpdate()
                }
            }

        phoneStatusBarViewClass
            .after("onConfigurationChanged")
            .run { runParam ->
                val view = runParam.thisObject as? FrameLayout ?: return@run
                mPhoneStatusbarView = view
                view.post {
                    try {
                        placeNTSB()
                    } catch (_: Throwable) {}
                    try {
                        placeClock()
                    } catch (_: Throwable) {}
                    scheduleHeightsUpdate()
                    mStatusBarContents?.let { applyStatusBarContentPadding(it) }
                    updateShadeHeaderPadding()
                }
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        if (BatteryBarView.hasInstance()) {
                            BatteryBarView.getInstance().post { refreshBatteryBar(BatteryBarView.getInstance()) }
                        }
                    }
                }, 2000)
            }

        activityStarterImplClass
            .afterConstruction()
            .run { runParam ->
                if (mActivityStarter == null) {
                    mActivityStarter = runParam.thisObject
                }
            }

        val clickListener = ClickListener()

        shadeHeaderControllerClass
            .after("onInit")
            .run { runParam ->
                mShadeHeaderController = runParam.thisObject
                val mView = getObjectField<View>(runParam.thisObject, "mView") ?: return@run
                mShadeHeaderView = mView
                applyShadeHeaderPaddingOnly(mView)

                mView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> applyShadeHeaderPaddingOnly(v) }

                mView.findViewById<View>(idOf("clock"))?.apply {
                    setOnClickListener(clickListener)
                    setOnLongClickListener(clickListener)
                }

                mView.findViewById<View>(idOf("date"))?.apply {
                    setOnClickListener(clickListener)
                    setOnLongClickListener(clickListener)
                }
            }

        shadeHeaderControllerClass
            .before("updateConstraintsForInsets")
            .run { runParam ->
                mShadeHeaderController = runParam.thisObject
                val view = runParam.args.firstOrNull() as? View
                if (view != null) {
                    mShadeHeaderView = view
                    applyShadeHeaderPaddingOnly(view)
                }
            }

        shadeHeaderControllerClass
            .after("updateVisibility")
            .run {
                val header = mShadeHeaderView
                if (header != null && header.visibility == View.VISIBLE) {
                    applyShadeHeaderPaddingOnly(header)
                }
            }

        phoneStatusBarViewControllerClass
            .after("onViewAttached")
            .run {
                val sbView = mPhoneStatusbarView ?: return@run
                mClockView = sbView.findViewById(idOf("clock"))
                updateClockColor()

                sbView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleHeightsUpdate() }
                applyPhoneStatusBarViewHeight()

                mStatusbarStartSide = sbView.findViewById(idOf("status_bar_start_side_except_heads_up"))
                mStatusbarStartSide?.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                    override fun onChildViewAdded(parent: View, child: View) {
                        if (isNotificationMultiRowActive() && child != mClockView && child != mJetpackClockView && child.javaClass.name.endsWith("ComposeView")) {
                            repositionOngoingChip()
                            scheduleHeightsUpdate()
                        }
                    }

                    override fun onChildViewRemoved(parent: View, child: View) {}
                })

                mStatusBarContents = sbView.findViewById(idOf("status_bar_contents"))
                applyStatusBarContentPadding(mStatusBarContents)

                mSystemIconArea = sbView.findViewById(idOf("statusIcons"))

                createCenterIconArea()
                makeLeftSplitArea()

                if (bBarEnabled) {
                    placeBatteryBar()
                }

                if (volteIconEnabled || vowifiIconEnabled) {
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

        try {
            val clockInteractorClass = ReflectedClass.of("com.android.systemui.clock.domain.interactor.ClockInteractor")
            val hooks = clockInteractorClass.after("getClockTextFormatString").run { runParam ->
                val orig = runParam.result as? String ?: return@run
                var customFormat = orig.replace(Regex("(?<!H)H(?!H)"), "HH")

                if (mAmPmStyle != AM_PM_STYLE_GONE && !customFormat.contains("a")) {
                    customFormat = "$customFormat\u202fa"
                } else if (mAmPmStyle == AM_PM_STYLE_GONE && customFormat.contains("a")) {
                    customFormat = customFormat.replace("\u202fa", "").replace(" a", "").replace("a", "")
                }

                customFormat = customFormat.trim()

                fun convertToPattern(input: String?): String {
                    if (input.isNullOrEmpty()) return ""
                    val pat = StringBuilder()
                    val m = GREGORIAN_PATTERN.matcher(input)
                    var lastEnd = 0
                    while (m.find()) {
                        val literal = input.substring(lastEnd, m.start())
                        if (literal.isNotEmpty()) {
                            pat.append("'").append(literal.replace("'", "''")).append("'")
                        }
                        pat.append(m.group(1))
                        lastEnd = m.end()
                    }
                    val literal = input.substring(lastEnd)
                    if (literal.isNotEmpty()) {
                        pat.append("'").append(literal.replace("'", "''")).append("'")
                    }
                    return pat.toString()
                }

                if (mStringFormatBefore.isNotEmpty()) {
                    customFormat = convertToPattern(mStringFormatBefore.trim()) + "\u202f" + customFormat
                }
                if (mStringFormatAfter.isNotEmpty()) {
                    customFormat = customFormat + "\u202f" + convertToPattern(mStringFormatAfter.trim())
                }
                runParam.result = customFormat
            }
            isJetpackClock = hooks.isNotEmpty()
            syncJetpackClockSeconds()
        } catch (_: Throwable) {
            isJetpackClock = false
            log("ClockInteractor not found, skipping Android 17 Compose hook.")
        }

        clockClass
            .before("getSmallTime")
            .run { runParam ->
                setObjectField(runParam.thisObject, "mAmPmStyle", AM_PM_STYLE_GONE)
                setObjectField(runParam.thisObject, "mShowSeconds", mShowSeconds)
            }

        clockClass
            .after("getSmallTime")
            .run { runParam ->
                if (runParam.thisObject != mClockView) return@run

                val result = SpannableStringBuilder()
                result.append(getFormattedString(mStringFormatBefore, mBeforeSmall, mBeforeClockColor))
                val rawText = runParam.result as? CharSequence ?: return@run
                val clockText = SpannableStringBuilder.valueOf(rawText)
                clockColor?.let { color ->
                    clockText.setSpan(
                        NetworkTraffic.TrafficStyle(color),
                        0,
                        clockText.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                result.append(clockText)
                if (mAmPmStyle != AM_PM_STYLE_GONE) {
                    result.append(getFormattedString("\$Ga", mAmPmStyle == AM_PM_STYLE_SMALL, clockColor))
                }
                result.append(getFormattedString(mStringFormatAfter, mAfterSmall, mAfterClockColor))

                if (getAdditionalInstanceField(runParam.thisObject, "stringFormatCallBack") == null) {
                    val callback = FormattedStringCallback {
                        if (!mShowSeconds) {
                            updateClock()
                        }
                    }
                    stringFormatter.registerCallback(callback)
                    setAdditionalInstanceField(runParam.thisObject, "stringFormatCallBack", callback)
                }
                runParam.result = result
            }

        clockClass
            .after("onDarkChanged")
            .run { runParam ->
                if (runParam.thisObject != mClockView) return@run

                updateClockColor()
                if (BatteryBarView.hasInstance()) {
                    refreshBatteryBar(BatteryBarView.getInstance())
                }
            }

        val mobileIconsInteractorImplClass = ReflectedClass.of("com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl")
        val readonlyStateFlowClass = ReflectedClass.of("kotlinx.coroutines.flow.ReadonlyStateFlow")

        mobileIconsInteractorImplClass
            .after("getMobileConnectionInteractorForSubId")
            .run { runParam ->
                if (hideRoamingState) {
                    val constructor = readonlyStateFlowClass.clazz?.constructors?.firstOrNull()
                    val flow = constructor?.newInstance(getStateFlowImplOf(false))
                    if (flow != null) {
                        setObjectField(runParam.result, "isRoaming", flow)
                    }
                }
            }
    }

    private fun createCenterIconArea() {
        mCenteredIconArea = LinearLayout(mContext).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        }
        mPhoneStatusbarView?.addView(mCenteredIconArea)
    }

    private fun updateClockColor() {
        val clock = mClockView ?: return
        currentClockColor = clock.textColors.defaultColor

        for (callback in mTextColorCallbacks) {
            callback.onTextColorChanged(currentClockColor)
        }
    }

    private fun updateStatusbarHeight() {
        try {
            mPhoneStatusbarView?.let { callMethod<Any>(it, "updateStatusBarHeight") }
        } catch (_: Throwable) {}
    }

    private fun applyNotificationIconLimit() {
        val container = mNotificationIconContainer ?: return
        try {
            callMethod<Any>(container, "setMaxIconsAmount", notificationIconLimit)
            scheduleHeightsUpdate()
        } catch (_: Throwable) {}
    }

    @SuppressLint("DiscouragedApi")
    private fun makeLeftSplitArea() {
        val sbView = mPhoneStatusbarView ?: return
        mNotificationIconContainer = sbView.findViewById(idOf("notificationIcons"))
        val iconContainer = mNotificationIconContainer ?: return

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
            mLeftVerticalSplitContainer?.removeAllViews()
            (mLeftVerticalSplitContainer?.parent as? ViewGroup)?.removeView(mLeftVerticalSplitContainer)
        }

        mLeftVerticalSplitContainer?.layoutTransition = null

        val parent = iconContainer.parent as? ViewGroup ?: return
        val originalParams = iconContainer.layoutParams
        val lp: ViewGroup.LayoutParams = when {
            parent is LinearLayout -> {
                if (originalParams is LinearLayout.LayoutParams) LinearLayout.LayoutParams(originalParams) else LinearLayout.LayoutParams(originalParams)
            }
            parent is FrameLayout -> {
                if (originalParams is FrameLayout.LayoutParams) FrameLayout.LayoutParams(originalParams) else FrameLayout.LayoutParams(originalParams)
            }
            originalParams is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(originalParams)
            else -> ViewGroup.LayoutParams(originalParams)
        }
        lp.height = MATCH_PARENT
        if (lp is ViewGroup.MarginLayoutParams) {
            setLeftSplitAreaMargins(lp)
        }

        mLeftVerticalSplitContainer?.layoutParams = lp
        mLeftVerticalSplitContainer?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleHeightsUpdate() }

        mLeftExtraRowContainer = ShyLinearLayout(mContext).apply {
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val extraRowParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.TOP)
        mLeftVerticalSplitContainer?.addView(mLeftExtraRowContainer, 0, extraRowParams)

        mNotificationIconArea = parent
        parent.clipChildren = false
        parent.clipToPadding = false

        val insertIndex = parent.indexOfChild(iconContainer)
        parent.addView(mLeftVerticalSplitContainer, insertIndex)
        parent.removeView(iconContainer)

        val notificationRowParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.BOTTOM)
        mLeftVerticalSplitContainer?.addView(mNotificationContainerContainer, notificationRowParams)

        repositionOngoingChip()

        mNotificationContainerContainer?.addView(iconContainer)

        val notificationIconParams = iconContainer.layoutParams as LinearLayout.LayoutParams
        notificationIconParams.weight = 100f
        notificationIconParams.gravity = Gravity.CENTER_VERTICAL
        iconContainer.layoutParams = notificationIconParams
        applyNotificationIconLimit()

        iconContainer.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                scheduleHeightsUpdate()
            }
            override fun onChildViewRemoved(parent: View, child: View) {
                scheduleHeightsUpdate()
            }
        })

        (mStatusbarStartSide?.parent as? View)?.layoutParams?.height = MATCH_PARENT
        mStatusbarStartSide?.layoutParams?.height = MATCH_PARENT
        mLeftVerticalSplitContainer?.layoutParams?.height = MATCH_PARENT
        applyStatusBarContainerLayout()
    }

    private fun repositionOngoingChip() {
        if (mOngoingChipComposeView == null && mPhoneStatusbarView != null) {
            mOngoingChipComposeView = findComposeView(mPhoneStatusbarView?.findViewById(idOf("status_bar_start_side_except_heads_up")))
        }
        val chip = mOngoingChipComposeView
        val container = mNotificationContainerContainer
        if (chip != null && container != null) {
            if (chip.parent != container) {
                reAddView(container, chip, 0)
            }
            chip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleHeightsUpdate() }
        }
    }

    private fun findComposeView(parent: ViewGroup?): View? {
        if (parent == null) return null
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child != null && child != mClockView && child.javaClass.name.endsWith("ComposeView")) {
                return child
            }
        }
        return null
    }

    private fun setHeights() {
        if (mPhoneStatusbarView == null ||
            mNotificationContainerContainer == null ||
            mLeftExtraRowContainer == null ||
            mNotificationIconContainer == null
        ) return

        applyPhoneStatusBarViewHeight()
        val statusbarHeight = getStatusbarContentHeight()
        if (statusbarHeight <= 0) return

        applyStatusBarContainerLayout()

        val splitHeight = maxOf(1, statusbarHeight / 2)
        val hasExtraRow = mLeftExtraRowContainer?.visibility == VISIBLE
        var hasNotificationRowContent = mNotificationIconContainer?.visibility == VISIBLE &&
                (mNotificationIconContainer?.childCount ?: 0) > 0

        val container = mNotificationContainerContainer
        if (container != null && !hasNotificationRowContent) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child != mNotificationIconContainer &&
                    child.visibility == VISIBLE &&
                    maxOf(child.width, child.measuredWidth) > 0 &&
                    maxOf(child.height, child.measuredHeight) > 0
                ) {
                    hasNotificationRowContent = true
                    break
                }
            }
        }

        val splitRows = isNotificationMultiRowActive() && hasExtraRow && hasNotificationRowContent

        val notificationVisibility = if (hasNotificationRowContent) VISIBLE else INVISIBLE
        if (container?.visibility != notificationVisibility) {
            container?.visibility = notificationVisibility
        }

        setHeight(mNotificationContainerContainer, if (splitRows) splitHeight else MATCH_PARENT)
        setHeight(mLeftExtraRowContainer, if (splitRows) splitHeight else MATCH_PARENT)
        setHeight(mNotificationIconContainer, MATCH_PARENT)

        if (networkOnSBEnabled) {
            val divisor = if (networkTrafficPosition == POSITION_LEFT && splitRows) 2 else 1
            setHeight(networkTrafficSB, statusbarHeight / divisor)
        }
    }

    private fun scheduleHeightsUpdate() {
        val view = mPhoneStatusbarView ?: return
        view.removeCallbacks(mSetHeightsRunnable)
        view.post(mSetHeightsRunnable)
    }

    private fun getStatusbarContentHeight(): Int {
        return maxOf(0, getPhoneStatusBarHeight() - getStatusBarContentsTopPadding() - getStatusBarContentTopInset())
    }

    private fun getPhoneStatusBarHeight(): Int {
        var height = if (isLandscapeStatusBar()) {
            getTargetPhoneStatusBarHeight()
        } else {
            maxOf(getTargetPhoneStatusBarHeight(), getStatusBarInsetsHeight())
        }
        if (height <= 0) {
            height = maxOf(getViewHeight(mPhoneStatusbarView), getViewHeight(mStatusBarContents))
        }
        return height
    }

    private fun getStatusBarInsetsHeight(): Int {
        return try {
            mPhoneStatusbarView?.rootWindowInsets?.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars()
            )?.top ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun getTargetPhoneStatusBarHeight(): Int {
        return try {
            val context = mPhoneStatusbarView?.context ?: mContext
            val landscape = isLandscapeStatusBar()
            val config = android.content.res.Configuration(context.resources.configuration).apply {
                orientation = if (landscape) {
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                } else {
                    android.content.res.Configuration.ORIENTATION_PORTRAIT
                }
            }
            val orientationContext = context.createConfigurationContext(config)
            val resName = if (landscape) "status_bar_height_landscape" else "status_bar_height_portrait"
            val fallback = getAndroidDimensionPixelSize(orientationContext, "status_bar_height", 0)
            val height = getAndroidDimensionPixelSize(orientationContext, resName, fallback)
            (height * statusbarHeightFactor / 100f).roundToInt()
        } catch (_: Throwable) {
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
        mStatusBarContents?.let { return it.paddingTop }
        return try {
            mContext.resources.getDimensionPixelSize(dimenIdOf("status_bar_padding_top"))
        } catch (_: Throwable) {
            0
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun getAndroidDimensionPixelSize(context: Context, resourceName: String, defaultValue: Int): Int {
        return try {
            val resId = context.resources.getIdentifier(resourceName, "dimen", "android")
            if (resId == 0) defaultValue else context.resources.getDimensionPixelSize(resId)
        } catch (_: Throwable) {
            defaultValue
        }
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

    private fun setHeight(view: View?, height: Int) {
        if (view == null) return
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != height) {
            layoutParams.height = height
            view.layoutParams = layoutParams
        }
    }

    private fun applyStatusBarContainerLayout() {
        val topInset = getStatusBarContentTopInset()
        val contentHeight = getStatusbarContentHeight()

        applyPhoneStatusBarViewHeight()
        applyMatchParentHeight(mStatusBarContents)
        applyMatchParentHeight(mStatusbarStartSide)
        applyMatchParentHeight(mNotificationIconArea)
        (mStatusbarStartSide?.parent as? View)?.let { applyMatchParentHeight(it) }

        applyVerticalContentBounds(mCenteredIconArea, 0, contentHeight)
        setStatusBarTranslationY(mCenteredIconArea, getSingleRowStatusBarTranslationY(mCenteredIconArea))

        val sysIcons = mSystemIconArea
        if (sysIcons != null) {
            val sysIconParent = sysIcons.parent as? View
            if (sysIconParent != null) {
                applyVerticalContentBounds(sysIconParent, 0, contentHeight)
                setStatusBarTranslationY(sysIconParent, getSingleRowStatusBarTranslationY(sysIconParent))
                applyContentChildBounds(sysIcons)
                setStatusBarTranslationY(sysIcons, 0f)
            } else {
                applyVerticalContentBounds(sysIcons, 0, contentHeight)
                setStatusBarTranslationY(sysIcons, getSingleRowStatusBarTranslationY(sysIcons))
            }
        }

        val splitContainer = mLeftVerticalSplitContainer
        if (splitContainer != null) {
            (splitContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.let { setLeftSplitAreaMargins(it) }
            var leftContentHeight = contentHeight
            (splitContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                leftContentHeight -= it.topMargin + it.bottomMargin
            }
            setHeight(splitContainer, maxOf(1, leftContentHeight))
            setStatusBarTranslationY(splitContainer, topInset.toFloat())
        }

        applyLinearLayoutGravity(mLeftExtraRowContainer, Gravity.CENTER_VERTICAL)
        applyLinearLayoutGravity(mNotificationContainerContainer, Gravity.CENTER_VERTICAL)
        applyLinearLayoutGravity(mSystemIconArea, Gravity.CENTER_VERTICAL)

        mClockView?.let {
            it.gravity = Gravity.CENTER_VERTICAL
            applyLayoutGravity(it, Gravity.CENTER_VERTICAL)
        }
        mJetpackClockView?.let {
            applyLayoutGravity(it, Gravity.CENTER_VERTICAL)
        }
    }

    private fun getSingleRowStatusBarTranslationY(view: View?): Float {
        if (view == null) return 0f
        val contentHeight = getStatusbarContentHeight()
        val viewHeight = getViewHeight(view)
        if (contentHeight <= 0 || viewHeight <= 0) return getStatusBarContentTopInset().toFloat()

        return getStatusBarContentTopInset() + maxOf(0f, (contentHeight - viewHeight) / 2f)
    }

    private fun setStatusBarTranslationY(view: View?, translationY: Float) {
        if (view != null && view.translationY != translationY) {
            view.translationY = translationY
        }
    }

    private fun applyMatchParentHeight(view: View?) {
        if (view == null || view.layoutParams == null) return
        val layoutParams = view.layoutParams
        if (layoutParams.height != MATCH_PARENT) {
            layoutParams.height = MATCH_PARENT
            view.layoutParams = layoutParams
        }
        (view as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
    }

    private fun applyPhoneStatusBarViewHeight() {
        val targetHeight = getPhoneStatusBarHeight()
        val sbView = mPhoneStatusbarView
        if (sbView == null || targetHeight <= 0) return

        applyStatusBarHeight(sbView, targetHeight)
        applyStatusBarAncestorHeights(targetHeight, mLastAppliedStatusBarHeight)
        mLastAppliedStatusBarHeight = targetHeight
    }

    private fun applyStatusBarAncestorHeights(targetHeight: Int, previousHeight: Int) {
        val sbView = mPhoneStatusbarView ?: return
        val rootView = sbView.rootView
        var parent: ViewParent? = sbView.parent
        while (parent is View && parent != rootView) {
            val parentView = parent as View
            val layoutParams = parentView.layoutParams
            val parentHeight = parentView.height
            val ownsPreviousHeight = previousHeight > 0 &&
                    (parentHeight == previousHeight || (layoutParams != null && layoutParams.height == previousHeight))

            if (parentHeight > 0 && (parentHeight < targetHeight || ownsPreviousHeight)) {
                applyStatusBarHeight(parentView, targetHeight)
            }
            (parentView as? ViewGroup)?.apply {
                clipChildren = false
                clipToPadding = false
            }
            parent = parentView.parent
        }
    }

    private fun applyStatusBarHeight(view: View, targetHeight: Int) {
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
        (view as? ViewGroup)?.apply {
            clipChildren = false
            clipToPadding = false
        }
    }

    private fun applyContentChildBounds(view: View?) {
        if (view == null || view.layoutParams == null) return
        val layoutParams = view.layoutParams
        var changed = false
        if (layoutParams.height != MATCH_PARENT) {
            layoutParams.height = MATCH_PARENT
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
            val updated = mergeVerticalGravity(layoutParams.gravity, gravity)
            if (layoutParams.gravity != updated) {
                layoutParams.gravity = updated
                view.layoutParams = layoutParams
            }
        } else if (layoutParams is FrameLayout.LayoutParams) {
            val updated = mergeVerticalGravity(layoutParams.gravity, gravity)
            if (layoutParams.gravity != updated) {
                layoutParams.gravity = updated
                view.layoutParams = layoutParams
            }
        }
    }

    private fun applyLinearLayoutGravity(view: LinearLayout?, gravity: Int) {
        if (view == null) return
        val updated = mergeVerticalGravity(view.gravity, gravity)
        if (view.gravity != updated) {
            view.gravity = updated
        }
    }

    private fun mergeVerticalGravity(originalGravity: Int, verticalGravity: Int): Int {
        val nonNegative = if (originalGravity < 0) 0 else originalGravity
        return (nonNegative and Gravity.VERTICAL_GRAVITY_MASK.inv()) or verticalGravity
    }

    private fun refreshBatteryBar(instance: BatteryBarView) {
        BatteryBarView.setStaticColor(
            batteryLevels,
            batteryColors,
            indicateCharging,
            chargingColor,
            indicateFastCharging,
            fastChargingColor,
            indicatePowerSave,
            powerSaveColor,
            bBarTransitColors,
            bbAnimateCharging
        )
        instance.visibility = if (bBarEnabled) VISIBLE else GONE
        instance.setColorful(bBarColorful)
        instance.setOnlyWhileCharging(bbOnlyWhileCharging)
        instance.setOnTop(!bbOnBottom)
        instance.setAlphaPct(bbOpacity)
        instance.setBarHeight((bBarHeight / 10f).roundToInt() + 5)
        instance.setCenterBased(bbSetCentered)
        instance.refreshLayout()
    }

    private fun placeBatteryBar() {
        try {
            val batteryBarView = BatteryBarView.getInstance(mContext)
            try {
                (batteryBarView.parent as? ViewGroup)?.removeView(batteryBarView)
            } catch (_: Throwable) {}
            mPhoneStatusbarView?.addView(batteryBarView)
            refreshBatteryBar(BatteryBarView.getInstance())
        } catch (_: Throwable) {}
    }

    private fun getStatusbarIconFor(icon: Icon, slotName: String): Any? {
        return try {
            val clazz = statusBarIconClass?.clazz ?: return null
            val statusbarIcon = ObjenesisHelper.newInstance(clazz)

            setObjectField(statusbarIcon, "visible", true)
            val userConstructor = UserHandle::class.java.getDeclaredConstructor(java.lang.Integer.TYPE)
            setObjectField(statusbarIcon, "user", userConstructor.newInstance(0))
            setObjectField(statusbarIcon, "pkg", BuildConfig.APPLICATION_ID)
            setObjectField(statusbarIcon, "icon", icon)
            setObjectField(statusbarIcon, "iconLevel", 0)
            setObjectField(statusbarIcon, "number", 0)
            setObjectField(statusbarIcon, "contentDescription", slotName)

            statusbarIcon
        } catch (_: Throwable) {
            null
        }
    }

    private fun getStatusbarIconHolderFor(statusbarIcon: Any?): Any? {
        val clazz = statusBarIconHolderClass?.clazz ?: return null
        val holder = ObjenesisHelper.newInstance(clazz)
        var iconFieldName = ""
        for (field in clazz.fields) {
            if (field.name.lowercase().contains("icon")) {
                iconFieldName = field.name
                break
            }
        }
        setObjectField(holder, iconFieldName, statusbarIcon)
        return holder
    }

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

                SystemUtils.TelephonyManager()?.registerTelephonyCallback(voDataExec, voDataCallback)
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
        } catch (_: Exception) {}

        updateVoData(true)
    }

    private fun removeVoDataCallback() {
        try {
            SystemUtils.TelephonyManager()?.unregisterTelephonyCallback(voDataCallback)
            telephonyCallbackRegistered = false
        } catch (_: Exception) {}
        removeSBIconSlot(VO_LTE_SLOT)
        removeSBIconSlot(VO_WIFI_SLOT)
    }

    private inner class ServerStateCallback : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
        override fun onServiceStateChanged(serviceState: ServiceState) {
            updateVoData(false)
        }
    }

    private fun updateVoData(force: Boolean) {
        val tm = SystemUtils.TelephonyManager() ?: return
        val voWifiAvailable = callMethod<Boolean>(tm, "isWifiCallingAvailable")
        val volteStateAvailable = callMethod<Boolean>(tm, "isVolteAvailable")

        if (lastVolteAvailable != volteStateAvailable || force) {
            lastVolteAvailable = volteStateAvailable
            if (volteStateAvailable && volteIconEnabled) {
                setSBIcon(VO_LTE_SLOT, volteStatusbarIconHolder, volteStatusbarIcon)
            } else {
                removeSBIconSlot(VO_LTE_SLOT)
            }
        }

        if (lastVowifiAvailable != voWifiAvailable || force) {
            lastVowifiAvailable = voWifiAvailable
            if (voWifiAvailable && vowifiIconEnabled) {
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
                    callMethod<Any>(mCommandQueue!!, "setIcon", slot, icon)
                    return@runOnMainThread
                } catch (_: Throwable) {}
            }
            if (mStatusBarIconController != null && holder != null) {
                try {
                    callMethod<Any>(mStatusBarIconController!!, "setIcon", slot, holder)
                    return@runOnMainThread
                } catch (_: Throwable) {}
            }
            if (mCommandQueue != null && icon != null) {
                try {
                    callMethod<Any>(mCommandQueue!!, "setIcon", slot, icon)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun removeSBIconSlot(slot: String) {
        runOnMainThread {
            if (!isLegacyStatusBar && mCommandQueue != null) {
                try {
                    callMethod<Any>(mCommandQueue!!, "removeIcon", slot)
                    return@runOnMainThread
                } catch (_: Throwable) {}
            }
            if (mStatusBarIconController != null) {
                try {
                    callMethod<Any>(mStatusBarIconController!!, "removeAllIconsForSlot", slot, false)
                    return@runOnMainThread
                } catch (_: Throwable) {}
            }
            if (mCommandQueue != null) {
                try {
                    callMethod<Any>(mCommandQueue!!, "removeIcon", slot)
                } catch (_: Throwable) {}
            }
        }
    }

    private fun placeNTSB() {
        val nt = networkTrafficSB ?: return
        try {
            (nt.parent as? ViewGroup)?.removeView(nt)
        } catch (_: Exception) {}

        if (!networkOnSBEnabled) return

        try {
            when (networkTrafficPosition) {
                POSITION_RIGHT -> {
                    val systemIcons = mSystemIconArea?.parent as? ViewGroup
                    val endSideContent = systemIcons?.parent as? ViewGroup
                    var insertIndex = endSideContent?.indexOfChild(systemIcons) ?: 0
                    if (insertIndex == -1) insertIndex = 0
                    endSideContent?.addView(nt, insertIndex)
                    nt.setPadding(rightClockPadding, 0, leftClockPadding, 0)
                }
                POSITION_LEFT -> {
                    if (isNotificationMultiRowActive()) {
                        mLeftExtraRowContainer?.let { it.addView(nt, it.childCount) }
                    } else {
                        mStatusbarStartSide?.addView(nt, 1)
                    }
                    nt.setPadding(0, 0, leftClockPadding, 0)
                }
                POSITION_CENTER -> {
                    mStatusbarStartSide?.addView(nt)
                    nt.setPadding(rightClockPadding, 0, leftClockPadding, 0)
                }
            }
            val ntsbLayoutP = nt.layoutParams as LinearLayout.LayoutParams
            ntsbLayoutP.gravity = Gravity.CENTER_VERTICAL
            nt.layoutParams = ntsbLayoutP
        } catch (_: Throwable) {}
    }

    inner class ClickListener : View.OnClickListener, View.OnLongClickListener {
        override fun onClick(v: View) {
            val name = mContext.resources.getResourceName(v.id)
            val starter = mActivityStarter ?: return

            if (name.endsWith("clock")) {
                callMethod<Any>(starter, "postStartActivityDismissingKeyguard", Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
            } else if (name.endsWith("date")) {
                val builder = CalendarContract.CONTENT_URI.buildUpon().apply {
                    appendPath("time")
                    appendPath(System.currentTimeMillis().toString())
                }
                val todayIntent = Intent(Intent.ACTION_VIEW, builder.build())
                callMethod<Any>(starter, "postStartActivityDismissingKeyguard", todayIntent, 0)
            }
        }

        override fun onLongClick(v: View): Boolean {
            val name = mContext.resources.getResourceName(v.id)
            val starter = mActivityStarter ?: return false

            if (name.endsWith("clock") || name.endsWith("date")) {
                val mIntent = Intent(Intent.ACTION_MAIN).apply {
                    setClassName("com.android.settings", "com.android.settings.Settings\$DateTimeSettingsActivity")
                }
                callMethod<Any>(starter, "startActivity", mIntent, true)
                return true
            }
            return false
        }
    }

    private fun placeClock() {
        var viewToMove: View? = mClockView
        if (isJetpackClock) {
            if (mJetpackClockView?.parent == null) {
                mJetpackClockView = null
            }
            if (mJetpackClockView == null) {
                mJetpackClockView = findComposeView(mStatusbarStartSide)
            }
            if (mJetpackClockView != null) {
                viewToMove = mJetpackClockView
            }
        }

        if (viewToMove == null) return

        val parent = viewToMove.parent as? ViewGroup
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
                    viewToMove.setPadding(startPadding, 0, leftClockPadding, 0)
                } else {
                    targetArea = mStatusbarStartSide
                    index = 1
                    viewToMove.setPadding(0, 0, leftClockPadding, 0)
                }
            }
            POSITION_CENTER -> {
                targetArea = mCenteredIconArea as? ViewGroup
                viewToMove.setPadding(rightClockPadding, 0, rightClockPadding, 0)
            }
            POSITION_RIGHT -> {
                viewToMove.setPadding(rightClockPadding, 0, 0, 0)
                targetArea = mSystemIconArea?.parent as? ViewGroup
            }
        }

        if (targetArea == null || targetArea == parent) return

        if (isJetpackClock) {
            isMovingClock = true
        }
        try {
            parent?.removeView(viewToMove)

            if (isJetpackClock && viewToMove == mJetpackClockView) {
                val lp = viewToMove.layoutParams
                if (lp != null) {
                    lp.width = if (clockPosition == POSITION_RIGHT) WRAP_CONTENT else MATCH_PARENT
                    viewToMove.layoutParams = lp
                }
            }

            if (index != null) {
                targetArea.addView(viewToMove, index)
            } else {
                targetArea.addView(viewToMove)
            }
        } finally {
            isMovingClock = false
        }
    }

    private fun getFormattedString(dateFormat: String, small: Boolean, @ColorInt textColor: Int?): CharSequence {
        if (dateFormat.isEmpty()) return ""

        val formatted = SpannableStringBuilder(stringFormatter.formatString(dateFormat))

        if (small) {
            val style: CharacterStyle = RelativeSizeSpan(0.7f)
            formatted.setSpan(style, 0, formatted.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (textColor != null) {
            formatted.setSpan(NetworkTraffic.TrafficStyle(textColor), 0, formatted.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return formatted
    }

    private fun wifiVisibleChanged() {
        try {
            val resolver = getObjectField<ContentResolver>(mTunerService ?: return, "mContentResolver")
            var hideListString = Settings.Secure.getString(resolver, ICON_HIDE_LIST)

            if (combineSignalIcons && mWifiVisible) {
                if (hideListString.isNullOrEmpty()) {
                    hideListString = "mobile"
                } else if (!hideListString.contains("mobile")) {
                    hideListString = "$hideListString,mobile"
                }
            }

            val lookup = getObjectField<Any>(mTunerService ?: return, "mTunableLookup") ?: return
            val tunables = callMethod<Set<Any>>(lookup, "get", ICON_HIDE_LIST) ?: return

            val finalHideListString = hideListString
            mPhoneStatusbarView?.post {
                for (tunable in tunables) {
                    callMethod<Any>(tunable, "onTuningChanged", ICON_HIDE_LIST, finalHideListString)
                }
            }
        } catch (_: Throwable) {}
    }

    fun interface ClockVisibilityCallback : Callback {
        fun OnVisibilityChanged(isVisible: Boolean)
    }

    fun interface StatusbarTextColorCallback {
        fun onTextColorChanged(textColor: Int)
    }
}
