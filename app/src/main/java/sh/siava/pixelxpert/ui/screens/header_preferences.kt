package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val header_preferences: PrefScreen = PrefScreen(
	key = "header_preferences",
	nodes = listOf(
			Category(
	title = 0,
	children = listOf(
				SearchBar(key = "searchPreference"),
	),
			),
			Category(
	title = 0,
	accent = 0xFFA1C9FF,
	children = listOf(
				MainTile(key = "quicksettings_header", title = R.string.qs_panel_category_title, summary = R.string.qs_panel_category_summary, route = "quick_settings", icon = R.drawable.ic_settings_quicksettings, ignore = true),
				MainTile(key = "statusbar_header", title = R.string.statusbar_header, summary = R.string.statusbar_header_summary, route = "statusbar", icon = R.drawable.ic_settings_statusbar, ignore = true),
				MainTile(key = "lockscreen_header", title = R.string.lockscreen_header_title, summary = R.string.lockscreen_header_summary, route = "lock_screen", icon = R.drawable.ic_settings_lockscreen, ignore = true),
				MainTile(key = "nav_header", title = R.string.nav_header, summary = R.string.nav_header_summary, route = "nav", icon = R.drawable.ic_settings_navigation, ignore = true),
	),
			),
			Category(
	title = 0,
	accent = 0xFFFFAEE4,
	children = listOf(
				MainTile(key = "theming_header", title = R.string.theme_customization_category, summary = R.string.theme_customization_summary, route = "theming", icon = R.drawable.ic_settings_themes, ignore = true),
	),
			),
			Category(
	title = 0,
	accent = 0xFF81DA88,
	children = listOf(
				MainTile(key = "dialer_header", title = R.string.dialer_header, summary = R.string.dialer_header_summary, route = "dialer", icon = R.drawable.ic_phone, ignore = true),
				MainTile(key = "hotspot_header", title = R.string.hotspot_header, summary = R.string.hotspot_header_summary, route = "hot_spot", icon = R.drawable.ic_settings_wifi_hotspot, ignore = true),
	),
			),
			Category(
	title = 0,
	accent = 0xFFFFB683,
	children = listOf(
				MainTile(key = "pm_header", title = R.string.pm_header, summary = R.string.pm_header_summary, route = "package_manager", icon = R.drawable.ic_settings_package_manager, ignore = true),
				MainTile(key = "misc_header", title = R.string.misc_header, summary = R.string.misc_header_summary, route = "misc", icon = R.drawable.ic_settings_misc, ignore = true),
	),
			),
	),
)
