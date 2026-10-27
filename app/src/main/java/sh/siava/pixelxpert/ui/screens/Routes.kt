// A NavRow names a route id; Navigation3 needs to turn that id into a
// preference screen. The two are one-to-one, so the mapping is written out
// rather than derived.
// Routes listed in [unportedRoutes] have neither a preference screen nor a
// bespoke Compose screen (the icon pack builders) and render as
// not-yet-ported stubs.
package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R

val routeToScreenKey: Map<String, String> = mapOf(
	"dialer" to "dialer_prefs",
	"gesture_nav" to "gesture_nav_prefs",
	"hot_spot" to "hotspot_prefs",
	"lock_screen" to "lock_screen_prefs",
	"misc" to "misc_prefs",
	"nav" to "nav_prefs",
	"network" to "sbqs_network_prefs",
	"network_stat" to "lsqs_custom_text",
	"package_manager" to "packagemanger_prefs",
	"physical_button_remap" to "physical_buttons_prefs",
	"qstile_qty" to "qs_tile_qty_prefs",
	"quick_settings" to "quicksettings_prefs",
	"sbb" to "statusbar_batterybar_prefs",
	"sbc" to "statusbar_clock_prefs",
	"statusbar" to "statusbar_settings",
	"taskbar_nav" to "taskbar_prefs",
	"theming" to "theming_prefs",
	"three_button_nav" to "three_button_prefs",
)

val unportedRoutes: Set<String> = setOf()

val searchScreenTitles: Map<String, Int> = mapOf(
	"dialer_prefs" to R.string.dialer_header,
	"gesture_nav_prefs" to R.string.gesturenav_header,
	"hotspot_prefs" to R.string.hotspot_header,
	"lock_screen_prefs" to R.string.lockscreen_header_title,
	"lsqs_custom_text" to R.string.netstat_header,
	"misc_prefs" to R.string.misc_header,
	"nav_prefs" to R.string.nav_header,
	"own_prefs_header" to R.string.own_prefs_header,
	"packagemanger_prefs" to R.string.pm_header,
	"physical_buttons_prefs" to R.string.remap_physical_buttons_title,
	"qs_tile_qty_prefs" to R.string.qs_tile_qty_title,
	"quicksettings_prefs" to R.string.qs_panel_category_title,
	"sbqs_network_prefs" to R.string.ntsb_category_title,
	"sleep_on_flat_prefs" to R.string.sleep_on_flat_screen_title,
	"statusbar_batterybar_prefs" to R.string.sbbb_header,
	"statusbar_clock_prefs" to R.string.sbc_header,
	"statusbar_settings" to R.string.statusbar_header,
	"taskbar_prefs" to R.string.taskbar_header_title,
	"theming_prefs" to R.string.theme_customization_category,
	"three_button_prefs" to R.string.threebutton_header_title,
)
