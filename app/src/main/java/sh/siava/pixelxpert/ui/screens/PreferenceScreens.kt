package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.ui.prefs.PrefScreen

val preferenceScreens: Map<String, PrefScreen> = mapOf(
	"dialer_prefs" to dialer_prefs,
	"gesture_nav_prefs" to gesture_nav_prefs,
	"header_preferences" to header_preferences,
	"hotspot_prefs" to hotspot_prefs,
	"lock_screen_prefs" to lock_screen_prefs,
	"lsqs_custom_text" to lsqs_custom_text,
	"misc_prefs" to misc_prefs,
	"nav_prefs" to nav_prefs,
	"own_prefs_header" to own_prefs_header,
	"packagemanger_prefs" to packagemanger_prefs,
	"physical_buttons_prefs" to physical_buttons_prefs,
	"qs_tile_qty_prefs" to qs_tile_qty_prefs,
	"quicksettings_prefs" to quicksettings_prefs,
	"sbqs_network_prefs" to sbqs_network_prefs,
	"sleep_on_flat_prefs" to sleep_on_flat_prefs,
	"statusbar_batterybar_prefs" to statusbar_batterybar_prefs,
	"statusbar_clock_prefs" to statusbar_clock_prefs,
	"statusbar_settings" to statusbar_settings,
	"taskbar_prefs" to taskbar_prefs,
	"theming_prefs" to theming_prefs,
	"three_button_prefs" to three_button_prefs,
)
