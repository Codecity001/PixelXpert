package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val quicksettings_prefs: PrefScreen = PrefScreen(
	key = "quicksettings_prefs",
	nodes = listOf(
		Illustration(key = "quick_settings_illustration", lottieRawRes = R.raw.lottie_quick_settings, ignore = true),
		Category(
			title = R.string.quick_settings_tiles_category,
			ignore = true,
			children = listOf(
				SliderPref(key = "UnMuteVolumePCT", title = R.string.qs_volume_unmute_title, default = "50", continuous = true),
				NavRow(key = "qs_tile_qty", title = R.string.qs_tile_qty_title, route = "qstile_qty"),
				SliderPref(
					key = "QSLabelScaleFactor",
					title = R.string.qs_tile_label_size,
					default = "0",
					min = -50f,
					max = 50f,
					step = 5f,
					continuous = true,
				),
				SliderPref(
					key = "QSSecondaryLabelScaleFactor",
					title = R.string.qs_tile_secondary_label_size,
					default = "0",
					min = -50f,
					max = 50f,
					step = 5f,
					continuous = true,
				),
			),
		),
		Category(
			title = R.string.qs_pulldown_category,
			ignore = true,
			children = listOf(
				SwitchPref(
					key = "QSPullodwnEnabled",
					title = R.string.qs_pulldown_switch_title,
					default = "false",
					summaryOn = R.string.general_on,
					summaryOff = R.string.general_off,
				),
				SliderPref(key = "QSPulldownPercent", title = R.string.qs_pulldown_size, default = "25", continuous = true),
				ListPref(
					key = "QSPulldownSide",
					title = R.string.qs_pulldown_side_title,
					default = "1",
					entries = R.array.qs_pulldown_side_names,
					entryValues = R.array.qs_pulldown_side_left,
				),
				SwitchPref(
					key = "oneFingerPullupEnabled",
					title = R.string.qs_pullup_switch_title,
					default = "false",
					summaryOn = R.string.general_on,
					summaryOff = R.string.general_off,
				),
			),
		),
		Category(
			title = R.string.qs_layout_category_title,
			ignore = true,
			children = listOf(
				QsLayoutOrderPref(
					key = "qs_layout_order",
					title = R.string.qs_layout_category_title,
					default = "brightness,qs,media",
				),
				SwitchPref(
					key = "QSAutoBrightnessToggle",
					title = R.string.QS_auto_brightness_title,
					summary = R.string.QS_auto_brightness_desc,
					default = "false",
				),
			),
		),
		Category(
			title = R.string.qs_footer_category,
			ignore = true,
			children = listOf(
				SwitchPref(
					key = "QSFooterBuildNumberEnabled",
					title = R.string.qs_build_number_enabled_title,
					default = "false",
					summaryOn = R.string.general_on,
					summaryOff = R.string.general_off,
				),
				TextPref(
					key = "QSFooterBuildNumberText",
					title = R.string.qs_build_number_text_title,
					summary = R.string.qs_build_number_text_summary,
					manual = true,
				),
			),
		),
	),
)
