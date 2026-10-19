package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val statusbar_batterybar_prefs: PrefScreen = PrefScreen(
	key = "statusbar_batterybar_prefs",
	nodes = listOf(
			Illustration(key = "battery_bar_illustration", lottieRawRes = R.raw.battery_bar, ignore = true),
			SwitchPref(key = "BBarEnabled", title = R.string.BB_enable_title, default = "false", mainSwitch = true),
			SwitchPref(key = "BBOnlyWhileCharging", title = R.string.BB_onlycharging_title, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "BBOnBottom", title = R.string.BB_move_SB_bottom_title, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "BBarColorful", title = R.string.BB_colorful_title, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SliderPref(key = "BBOpacity", title = R.string.BB_Opacity_title, default = "100", dependency = "BBarEnabled", continuous = true),
			SliderPref(key = "BBarHeight", title = R.string.BB_Thickness_title, default = "50", dependency = "BBarEnabled", continuous = true),
			SwitchPref(key = "BBSetCentered", title = R.string.BB_center_align_title, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "BBAnimateCharging", title = R.string.BB_animate_charging, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "indicateCharging", title = R.string.BB_indicate_charging, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			ColorPref(key = "batteryChargingColor", title = R.string.BB_batteryChargingColor, default = "-16711936", dependency = "BBarEnabled"),
			SwitchPref(key = "indicateFastCharging", title = R.string.BB_indicate_fast_charging, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			ColorPref(key = "batteryFastChargingColor", title = R.string.BB_batteryFastChargingColor, default = "-16776961", dependency = "BBarEnabled"),
			SwitchPref(key = "indicatePowerSave", title = R.string.BB_indicate_power_save, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			ColorPref(key = "batteryPowerSaveColor", title = R.string.BB_batteryPowerSaveColor, default = "0xFFFFBF00", dependency = "BBarEnabled"),
			SliderPref(key = "batteryWarningRange", title = R.string.BB_warning_level, summary = R.string.BB_warning_tip, default = "15,40", dependency = "BBarEnabled", min = 0f, max = 100f, step = 1f, valueCount = 2),
			SwitchPref(key = "BBarTransitColors", title = R.string.BB_transit_colors, default = "false", dependency = "BBarEnabled", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			ColorPref(key = "batteryCriticalColor", title = R.string.BB_batteryCriticalColor, default = "-65536", dependency = "BBarEnabled"),
			ColorPref(key = "batteryWarningColor", title = R.string.BB_batteryWarningColor, default = "-256", dependency = "BBarEnabled"),
	),
)
