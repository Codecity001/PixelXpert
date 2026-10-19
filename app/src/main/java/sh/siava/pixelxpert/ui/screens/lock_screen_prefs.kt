package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val lock_screen_prefs: PrefScreen = PrefScreen(
	key = "lock_screen_prefs",
	nodes = listOf(
			Illustration(key = "lock_screen_illustration", lottieRawRes = R.raw.lock, ignore = true),
			Category(
	title = R.string.depth_wallpaper_options,
	ignore = true,
	children = listOf(
				SwitchPref(key = "DWallpaperEnabled", title = R.string.depth_wallpaper_title, summary = R.string.depth_wallpaper_summary, default = "false"),
				ListPref(key = "SegmentorAI", title = R.string.depth_wallpaper_ai_model_title, default = "0", entries = R.array.segmentor_ai_model_names, entryValues = R.array.segmentor_ai_model_codes),
				SliderPref(key = "DWOpacity", title = R.string.depth_wallpaper_opacity_title, default = "192", min = 25f, max = 255f, step = 1f, valueCount = 1),
				SwitchPref(key = "DWonAOD", title = R.string.depth_wallpaper_aod_title, default = "false"),
	),
			),
			Category(
	title = R.string.fingerprint_options,
	ignore = true,
	children = listOf(
				SwitchPref(key = "fingerprint_circle_hide", title = R.string.transparent_fingerprint_circle, default = "false", summaryOn = R.string.transparent_fingerprint_circle_on, summaryOff = R.string.transparent_fingerprint_circle_off),
				SwitchPref(key = "fingerprint_icon_hide", title = R.string.fingerprint_icon, default = "false", summaryOn = R.string.fingerprint_icon_on, summaryOff = R.string.fingerprint_icon_off),
	),
			),
			Category(
	title = R.string.ls_general_options,
	ignore = true,
	children = listOf(
				SwitchPref(key = "DoubleTapSleepLockscreen", title = R.string.double_tap_sleep_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "holdScreenTorchEnabled", title = R.string.so_taptaptorch_title, summary = R.string.so_taptaptorch_summary, default = "false"),
				SwitchPref(key = "HideLockScreenUserAvatar", title = R.string.hide_avatar_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "KeyGuardDimAmount", title = R.string.keyguard_dim_amount_title, default = "-1", min = -1f, max = 100f, step = 1f, valueCount = 1),
				SwitchPref(key = "WakeUpToSecurityInput", title = R.string.wakeup_to_security_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "shufflePinEnabled", title = R.string.shufflePin_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "easyUnlockEnabled", title = R.string.easy_unlock_title, summary = R.string.easy_unlock_summary, default = "false"),
				SwitchPref(key = "ShowChargingInfo", title = R.string.charging_info_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "DisableLockScreenPill", title = R.string.disable_lockscreen_pill_title, summary = R.string.sysui_restart_needed, default = "false"),
				SwitchPref(key = "BlockQSPowerOnLockscreen", title = R.string.block_qs_power_on_lockscreen_title, summary = R.string.block_qs_power_on_lockscreen_summary, default = "false"),
	),
			),
			Category(
	title = R.string.keyguard_bottom_category,
	ignore = true,
	children = listOf(
				SwitchPref(key = "KeyguardBottomButtonsTransparent", title = R.string.keyguard_bottom_buttons_transparent_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
			Category(
	title = R.string.custom_text_category,
	ignore = true,
	children = listOf(
				SwitchPref(key = "carrierTextMod", title = R.string.configure_carrier_text, default = "false", summaryOn = R.string.carrier_text_mod_enabled, summaryOff = R.string.carrier_text_mod_disabled),
				TextPref(key = "carrierTextValue", title = R.string.carrier_text_value, manual = true),
				TextPref(key = "KGMiddleCustomText", title = R.string.lockscreen_center_text_value, manual = true),
	),
			),
	),
)
