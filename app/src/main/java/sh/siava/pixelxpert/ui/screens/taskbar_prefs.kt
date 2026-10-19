package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val taskbar_prefs: PrefScreen = PrefScreen(
	key = "taskbar_prefs",
	nodes = listOf(
			Illustration(key = "system_nav_2_button_illustration", lottieRawRes = R.raw.lottie_system_nav_2_button, ignore = true),
			ListPref(key = "taskBarMode", title = R.string.taskbar_status_title, default = "0", entries = R.array.taskbar_option_names, entryValues = R.array.taskbar_option_value),
			SwitchPref(key = "TaskbarOnLauncher", title = R.string.taskbar_on_launcher_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "TaskbarAsRecents", title = R.string.taskbar_as_recents_title, default = "true", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SwitchPref(key = "EnableGoogleRecents", title = R.string.taskbar_enable_google_recents, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
			SliderPref(key = "taskbarHeightOverride", title = R.string.taskbar_height_title, default = "100", min = 50f, max = 300f, step = 10f),
			SliderPref(key = "TaskbarRadiusOverride", title = R.string.taskbar_radius_title, default = "1", min = 0f, max = 2f, step = 0.1f),
	),
)
