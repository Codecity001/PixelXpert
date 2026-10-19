package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val misc_prefs: PrefScreen = PrefScreen(
	key = "misc_prefs",
	nodes = listOf(
			Illustration(key = "misc_illustration", lottieRawRes = R.raw.android_device, ignore = true),
			Category(
	title = R.string.general_section,
	ignore = true,
	children = listOf(
				SwitchPref(key = "PXInSettings", title = R.string.px_in_settings_title, default = "true", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "pin_wireless_adb", title = R.string.pin_wireless_adb_title, summary = R.string.pin_wireless_adb_summary, default = "false"),
				TextPref(key = "pinned_adb_port", title = R.string.pinned_adb_port_title, summary = R.string.pinned_adb_port_summary, default = "5555", dependency = "pin_wireless_adb"),
				SwitchPref(key = "advancedPowerMenu", title = R.string.advanced_power_menu_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "disableScreenshotSound", title = R.string.screenshot_sound_disable_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "EnableClipboardSmartActions", title = R.string.clipboard_smart_actions_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "ScreenshotChordInsecure", title = R.string.screenshot_insecure, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "InsecureScreenRecord", title = R.string.screen_record_insecure, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "allScreenRotations", title = R.string.allow_all_screen_rotations, summary = R.string.allow_all_screen_rotations_summary, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "noCutoutEnabled", title = R.string.force_no_cutout, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "BrightnessRange", title = R.string.brightness_range_title, summary = R.string.brightness_range_not_linear, default = "0,100", min = 0f, max = 100f, step = 1f, valueCount = 2),
				SwitchPref(key = "displayOverrideEnabled", title = R.string.display_resolution_switch_title, summary = R.string.display_resolution_switch_summary, default = "false"),
				SliderPref(key = "displayOverride", title = R.string.display_resolution_title, default = "100", min = 50f, max = 200f, step = 5f, valueCount = 1),
				SliderPref(key = "FastChargingWattage", title = R.string.fast_charging_threshold_title, default = "5", min = 5f, max = 50f, step = 5f, valueCount = 1),
				SwitchPref(key = "sysui_tuner", titleText = "SystemUI Tuner", default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				NavRow(key = "remap_physical_buttons", title = R.string.remap_physical_buttons_title, route = "physical_button_remap"),
				SwitchPref(key = "controlFlashWithVolKeys", title = R.string.control_flashlight_with_volume_keys, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "enable_high_brightness_flashlight", title = R.string.enable_high_brightness_flashlight_title, summary = R.string.enable_high_brightness_flashlight_summary, default = "false"),
				SwitchPref(key = "AnimateFlashlight", title = R.string.flashlight_fade_effect, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "SleepOnFlatScreen", title = R.string.sleep_on_flat_screen_title, default = "false", prominent = true, summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
			Category(
	title = R.string.launcher_section,
	ignore = true,
	children = listOf(
				SwitchPref(key = "RecentClearAllReposition", title = R.string.clear_all_reposition_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
			Category(
	title = R.string.volume_panel_category,
	ignore = true,
	children = listOf(
				SliderPref(key = "volumeStps", title = R.string.volume_button_step_title, default = "10", min = 10f, max = 50f, continuous = true),
	),
			),
			Category(
	title = R.string.work_clone_profile_header,
	ignore = true,
	children = listOf(
				SwitchPref(key = "StatusbarLongpressAppSwitch", title = R.string.statusbar_long_press_app_switch, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "StatusbarAppSwitchIconEnabled", title = R.string.statusbar_app_switch_icon, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
			Category(
	title = R.string.doze_header,
	ignore = true,
	children = listOf(
				TextPref(key = "NotificationAODIconLimit", title = R.string.sb_notification_limit),
				SwitchPref(key = "fingerprintWhileDozing", title = R.string.so_fingerprint_sensor_enabled, default = "true"),
				SwitchPref(key = "doubleTapToWake", title = R.string.so_double_tap_wake_title, summary = R.string.so_double_tap_wake_summary, default = "false"),
				SwitchPref(key = "TapToShowAmbient", title = R.string.so_tap_to_show_ambient_title, summary = R.string.so_tap_to_show_ambient_summary, default = "false"),
				SwitchPref(key = "PickToShowAmbient", title = R.string.so_pick_to_show_ambient_title, summary = R.string.so_pick_to_show_ambient_summary, default = "false"),
				SwitchPref(key = "ForceAODwCharging", title = R.string.force_aod_while_charging_title, summary = R.string.force_aod_while_charging_summary, default = "false"),
	),
			),
			Category(
	title = R.string.notifications_header,
	ignore = true,
	children = listOf(
				SwitchPref(key = "notificationExpandallEnabled", title = R.string.expand_notifications_icons_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "DisableOngoingNotifDismiss", title = R.string.disbale_ongoing_notif_dismiss_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "HeadupAutoDismissNotificationDecay", title = R.string.heads_up_notification_duration, default = "5000", min = 1000f, max = 10000f, step = 500f, valueCount = 1),
				ListPref(key = "notificationDefaultExpansion", title = R.string.notif_default_expansion_title, default = "0", entries = R.array.notif_default_expansion_entries, entryValues = R.array.notif_default_expansion_values),
	),
			),
			Category(
	title = R.string.monitoring_category_title,
	ignore = true,
	children = listOf(
				NavRow(key = "netstat_header", title = R.string.netstat_header, route = "network_stat"),
	),
			),
			Category(
	title = R.string.system_time_title,
	ignore = true,
	children = listOf(
				SwitchPref(key = "SyncNTPTime", title = R.string.sync_ntp_time_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "TimeSyncInterval", title = R.string.ntp_time_interval_title, default = "24", min = 12f, max = 120f, step = 12f, valueCount = 1),
				TextPref(key = "NTPServers", title = R.string.ntp_servers_title, summary = R.string.ntp_servers_summary, default = "0.pool.ntp.org;1.pool.ntp.org"),
				ActionRow(key = "SyncNTPTimeNow", title = R.string.sync_ntp_time_now_title),
	),
			),
	),
)
