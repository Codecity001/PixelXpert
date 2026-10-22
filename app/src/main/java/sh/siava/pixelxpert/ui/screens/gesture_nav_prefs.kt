package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val gesture_nav_prefs: PrefScreen = PrefScreen(
	key = "gesture_nav_prefs",
	nodes = listOf(
			Illustration(key = "system_nav_fully_gestural_illustration", lottieRawRes = R.raw.lottie_system_nav_fully_gestural, ignore = true),
			Category(
	title = R.string.back_gesture_category_title,
	ignore = true,
	children = listOf(
				SwitchPref(key = "BackFromLeft", title = R.string.back_from_left_title, default = "true"),
				SliderPref(key = "BackLeftHeight", title = R.string.back_height_title, default = "100", min = 10f, max = 100f, continuous = true),
				SwitchPref(key = "BackFromRight", title = R.string.back_from_right_title, default = "true"),
				SliderPref(key = "BackRightHeight", title = R.string.back_height_title, default = "100", min = 10f, max = 100f, continuous = true),
	),
			),
			Category(
	title = R.string.gesture_nav_extra_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "FCLongSwipeEnabled", title = R.string.gesture_nav_long_swipe_fc_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				ListPref(key = "leftSwipeUpAction", title = R.string.gesture_nav_left_swipe_up_title, default = "-1", entries = R.array.custom_action_titles, entryValues = R.array.custom_action_codes),
				SliderPref(key = "leftSwipeUpPercentage", title = R.string.gesture_nav_left_swipe_up_percent, default = "25", min = 5f, max = 40f, step = 1f, valueCount = 1),
				ListPref(key = "rightSwipeUpAction", title = R.string.gesture_nav_right_swipe_up_title, default = "-1", entries = R.array.custom_action_titles, entryValues = R.array.custom_action_codes),
				SliderPref(key = "rightSwipeUpPercentage", title = R.string.gesture_nav_right_swipe_up_percent, default = "25", min = 5f, max = 40f, step = 1f, valueCount = 1),
				ListPref(key = "twoFingerSwipeUpAction", title = R.string.gesture_nav_two_finger_title, default = "None", entries = R.array.custom_action_titles, entryValues = R.array.custom_action_codes),
				SliderPref(key = "swipeUpPercentage", title = R.string.gesture_nav_swipe_up_height, default = "20", min = 5f, max = 50f, step = 5f, valueCount = 1),
	),
			),
			Category(
	title = R.string.gesture_nav_pill_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "navPillColorAccent", title = R.string.colorpill, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SliderPref(key = "GesPillWidthModPos", title = R.string.gesture_nav_pill_width_title, default = "50"),
				SliderPref(key = "GesPillHeightFactor", title = R.string.gesture_nav_pill_height_title, default = "100", min = 50f, max = 300f),
	),
			),
			Category(
	title = R.string.keyboard_space_cat,
	ignore = true,
	children = listOf(
				SwitchPref(key = "KeyboardSpaceHideButtons", title = R.string.keyboard_space_hide_buttons_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
			SwitchPref(key = "HideNavbarInsets", title = R.string.hide_navbar_insets_title, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
)
