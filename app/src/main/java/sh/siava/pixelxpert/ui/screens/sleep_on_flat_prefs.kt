package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val sleep_on_flat_prefs: PrefScreen = PrefScreen(
	key = "sleep_on_flat_prefs",
	nodes = listOf(
			Illustration(key = "phone_sleep_illustration", lottieRawRes = R.raw.phone_sleep, ignore = true),
			SwitchPref(key = "SleepOnFlatScreen", title = R.string.sleep_on_flat_screen_title, mainSwitch = true),
			Category(
	title = R.string.sleep_on_flat_screen_tile_title,
	ignore = true,
	children = listOf(
				SliderPref(key = "FlatStandbyTime", title = R.string.flat_screen_timeout_title, default = "5", dependency = "SleepOnFlatScreen", min = 5f, max = 100f, step = 5f, valueCount = 1),
				SwitchPref(key = "SleepOnFlatRespectWakeLock", title = R.string.flat_screen_timeout_respect_wakelock_title, default = "true", dependency = "SleepOnFlatScreen", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
	),
)
