package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val statusbar_clock_prefs: PrefScreen = PrefScreen(
	key = "statusbar_clock_prefs",
	nodes = listOf(
			Illustration(key = "clock_illustration", lottieRawRes = R.raw.clock, ignore = true),
			ListPref(key = "SBClockLoc", title = R.string.sbc_position_title, default = "0", entries = R.array.sbc_position_names, entryValues = R.array.sbc_position_codes),
			ListPref(key = "SBCAmPmStyle", title = R.string.sbc_ampm_style, default = "2", entries = R.array.sbc_ampm_style_names, entryValues = R.array.sbc_ampm_style_codes),
			SwitchPref(key = "SBCShowSeconds", title = R.string.sbc_show_secs, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			Category(
	title = R.string.sbc_date_cat,
	ignore = true,
	children = listOf(
				ListPref(key = "SBCDayOfWeekMode", title = R.string.sbc_day_of_week_options, default = "0", entries = R.array.sbc_day_week_names, entryValues = R.array.sbc_day_week_codes),
				Category(
	title = R.string.sbc_advanced_cat,
	ignore = true,
	children = listOf(
					TextPref(key = "DateFormatBeforeSBC", title = R.string.sbc_custom_date_before, default = "", manual = true),
					SwitchPref(key = "BeforeSBCSmall", title = R.string.sbc_date_small, default = "true", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
					TextPref(key = "DateFormatAfterSBC", title = R.string.sbc_custom_date_after, default = "", manual = true),
					SwitchPref(key = "AfterSBCSmall", title = R.string.sbc_date_small, default = "true", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
				),
	),
			),
	),
)
