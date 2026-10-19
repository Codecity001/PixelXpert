package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val sbqs_network_prefs: PrefScreen = PrefScreen(
	key = "sbqs_network_prefs",
	nodes = listOf(
			Illustration(key = "network_speed_illustration", lottieRawRes = R.raw.lottie_network_speed, ignore = true),
			ListPref(key = "networkTrafficMode", title = R.string.network_mode_title, default = "0", entries = R.array.network_mode_names, entryValues = R.array.network_mode_codes),
			SwitchPref(key = "networkTrafficRXTop", title = R.string.network_download_top, default = "true", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "networkTrafficShowIcons", title = R.string.network_show_icons_title, default = "true", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "networkTrafficShowInBits", title = R.string.nt_unit_show_bits, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SliderPref(key = "networkTrafficInterval", title = R.string.network_interval_title, default = "1", min = 1f, max = 5f, continuous = true),
			TextPref(key = "networkTrafficThreshold", title = R.string.ntsb_threshold_title, summary = R.string.ntsb_threshold_summary, default = "10"),
			SwitchPref(key = "networkTrafficColorful", title = R.string.network_colorful_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			ColorPref(key = "networkTrafficDLColor", title = R.string.network_download_color_title, default = "-7617718", dependency = "networkTrafficColorful"),
			ColorPref(key = "networkTrafficULColor", title = R.string.network_upload_color_title, default = "-351845", dependency = "networkTrafficColorful"),
			SliderPref(key = "networkTrafficOpacity", title = R.string.network_opacity_title, default = "100", continuous = true),
	),
)
