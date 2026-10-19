package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val physical_buttons_prefs: PrefScreen = PrefScreen(
	key = "physical_buttons_prefs",
	nodes = listOf(
			Illustration(key = "button_illustration", lottieRawRes = R.raw.lottie_button, ignore = true),
			Category(
	title = R.string.while_screen_is_off_title,
	ignore = true,
	children = listOf(
				ListPref(key = "longPressPowerButtonScreenOff", title = R.string.remap_long_press_power_button_title, default = "0", entries = R.array.physical_remap_action_names, entryValues = R.array.physical_remap_action_values),
				ListPref(key = "doublePressPowerButtonScreenOff", title = R.string.remap_double_press_power_button_title, default = "0", entries = R.array.physical_remap_action_names, entryValues = R.array.physical_remap_action_values),
				ListPref(key = "longPressVolumeUpButtonScreenOff", title = R.string.remap_long_press_volume_up_button_title, default = "0", entries = R.array.physical_remap_action_names, entryValues = R.array.physical_remap_action_values),
				ListPref(key = "longPressVolumeDownButtonScreenOff", title = R.string.remap_long_press_volume_down_button_title, default = "0", entries = R.array.physical_remap_action_names, entryValues = R.array.physical_remap_action_values),
	),
			),
			Category(
	title = R.string.while_screen_is_on_title,
	ignore = true,
	children = listOf(
				ListPref(key = "longPressPowerButtonScreenOn", title = R.string.remap_long_press_power_button_title, default = "0", entries = R.array.physical_remap_action_names, entryValues = R.array.physical_remap_action_values),
				ListPref(key = "doublePressPowerButtonScreenOn", title = R.string.remap_double_press_power_button_title, default = "0", entries = R.array.physical_remap_action_names, entryValues = R.array.physical_remap_action_values),
	),
			),
	),
)
