package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val three_button_prefs: PrefScreen = PrefScreen(
	key = "three_button_prefs",
	nodes = listOf(
			Illustration(key = "system_nav_3_button_illustration", lottieRawRes = R.raw.lottie_system_nav_3_button, ignore = true),
			Category(
	title = R.string.modify_3button_title,
	ignore = true,
	children = listOf(
				SwitchPref(key = "ThreeButtonLayoutMod", title = R.string.modify_3button_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ListPref(key = "ThreeButtonLeft", title = R.string.three_button_left_title, default = "back", entries = R.array.three_button_nav_buttons, entryValues = R.array.three_button_nav_buttons),
				ListPref(key = "ThreeButtonCenter", title = R.string.three_button_center_title, default = "home", entries = R.array.three_button_nav_buttons, entryValues = R.array.three_button_nav_buttons),
				ListPref(key = "ThreeButtonRight", title = R.string.three_button_right_title, default = "recent", entries = R.array.three_button_nav_buttons, entryValues = R.array.three_button_nav_buttons),
	),
			),
	),
)
