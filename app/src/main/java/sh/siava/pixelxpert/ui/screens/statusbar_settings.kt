package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val statusbar_settings: PrefScreen = PrefScreen(
	key = "statusbar_settings",
	nodes = listOf(
			Illustration(key = "statusbar_illustration", lottieRawRes = R.raw.statusbar, ignore = true),
			Category(
	title = R.string.statusbar_general_options,
	ignore = true,
	children = listOf(
				SwitchPref(key = "DoubleTapSleep", title = R.string.double_tap_sleep_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				NavRow(key = "sbc_header", title = R.string.sbc_header, route = "sbc"),
				SwitchPref(key = "BBarEnabled", title = R.string.BB_enable_title, default = "false", prominent = true),
				SliderPref(key = "statusbarHeightFactor", title = R.string.status_bar_height, default = "100", min = 50f, max = 200f, continuous = true),
				TextPref(key = "NotificationIconLimit", title = R.string.sb_notification_limit),
				SliderPref(key = "statusbarPaddings", title = R.string.sb_padding_title, summary = R.string.sb_padding_summary, default = "-.5,100.5", min = -0.5f, max = 100.5f, step = 0.5f, valueCount = 2),
				SwitchPref(key = "combinedSignalEnabled", title = R.string.combined_signal_icon_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "HideRoamingState", title = R.string.hide_roaming_title, summary = R.string.caution_mobile_costs, default = "false"),
				SwitchPref(key = "VolteIconEnabled", title = R.string.volte_icon_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "VowifiIconEnabled", title = R.string.vowifi_icon_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "HidePrivacyChip", title = R.string.hide_privacychip_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ListPref(key = "LTE4GIconMod", title = R.string.lte_4g_icon_title, default = "0", entries = R.array.lte4g_icon_names, entryValues = R.array.lte4g_icon_codes),
				Category(
	title = R.string.sb_double_row_category,
	ignore = true,
	children = listOf(
					SwitchPref(key = "systemIconsMultiRow", title = R.string.sb_multi_row_system_icons, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
					ListPref(key = "systemIconSortPlan", title = R.string.sb_icon_sort_mode, default = "0", entries = R.array.sb_icon_sort_names, entryValues = R.array.sb_icon_sort_codes),
					SwitchPref(key = "notificationAreaMultiRow", title = R.string.sb_multi_row_notification_icons, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
					SwitchPref(key = "multiRowClockBottom", title = R.string.sb_multi_row_clock_bottom, default = "false", dependency = "notificationAreaMultiRow", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
					SliderPref(key = "clockMultiRowStartOffset", title = R.string.sb_multi_row_clock_start_offset, default = "2", min = 0f, max = 40f, step = 1f, continuous = true),
	),
				),
				Category(
	title = R.string.ntsb_category_title,
	ignore = true,
	children = listOf(
					SwitchPref(key = "networkOnSBEnabled", title = R.string.ntsb_enabled_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
					ListPref(key = "networkTrafficPosition", title = R.string.ntsb_position_title, default = "2", entries = R.array.network_sb_locations, entryValues = R.array.sbc_position_codes),
					NavRow(key = "network_settings_header", title = R.string.network_settings_pref, route = "network"),
	),
				),
	),
			),
			Category(
	title = R.string.sb_ignored_icons_category,
	children = listOf(
				ListPref(key = "SBIgnoredIcons", title = R.string.sb_ignored_icons, summary = R.string.sb_ignored_icons_statusbar, entries = R.array.ignored_icons_entries, entryValues = R.array.ignored_icons_values, multiSelect = true),
				ListPref(key = "KGIgnoredIcons", title = R.string.sb_kg_ignored_icons, summary = R.string.sb_ignored_icons_kg, entries = R.array.ignored_icons_entries, entryValues = R.array.ignored_icons_values, multiSelect = true),
				ListPref(key = "QSIgnoredIcons", title = R.string.sb_qs_ignored_icons, summary = R.string.sb_ignored_icons_qs, entries = R.array.ignored_icons_entries, entryValues = R.array.ignored_icons_values, multiSelect = true),
	),
			),
	),
)
