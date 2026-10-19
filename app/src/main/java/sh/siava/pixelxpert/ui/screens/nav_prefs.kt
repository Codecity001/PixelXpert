package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val nav_prefs: PrefScreen = PrefScreen(
	key = "nav_prefs",
	nodes = listOf(
			Illustration(key = "system_nav_2_button_illustration", lottieRawRes = R.raw.lottie_system_nav_2_button, ignore = true),
			NavRow(key = "gesturenav_header", title = R.string.gesturenav_header, route = "gesture_nav"),
			NavRow(key = "threebutton_header", title = R.string.threebutton_header_title, route = "three_button_nav"),
			NavRow(key = "taskbar_header", title = R.string.taskbar_header_title, route = "taskbar_nav"),
			SwitchPref(key = "HideNavbar", title = R.string.hide_navbar_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
)
