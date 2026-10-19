package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val own_prefs_header: PrefScreen = PrefScreen(
	key = "own_prefs_header",
	nodes = listOf(
			Category(
	title = R.string.settings_general_section_title,
	ignore = true,
	children = listOf(
				ListPref(key = "appLanguage", title = R.string.language_title, icon = R.drawable.ic_language, entries = R.array.languages_names, entryValues = R.array.languages_values),
				SwitchPref(key = "AlternativeThemedAppIcon", title = R.string.app_themed_icon_title, default = "false", icon = R.drawable.ic_alternative_icon, summaryOn = R.string.general_on, summaryOff = R.string.general_off),
				SwitchPref(key = "GoogleSettingsDesign", title = R.string.google_settings_design_title, summary = R.string.google_settings_design_summary, default = "false", icon = R.drawable.ic_settings_themes),
	),
			),
			Category(
	title = R.string.settings_update_section_title,
	ignore = true,
	children = listOf(
				NavRow(key = "CheckForUpdate", title = R.string.settings_check_for_update_title, summary = R.string.current_version, route = "update", icon = R.drawable.ic_check_update),
				SwitchPref(key = "AutoUpdate", title = R.string.auto_update_title, summary = R.string.auto_update_summary, default = "false", icon = R.drawable.ic_auto_update),
				SwitchPref(key = "UpdateWifiOnly", title = R.string.update_over_wifi_title, summary = R.string.update_over_wifi_summary, default = "false", icon = R.drawable.ic_update_over_wifi),
	),
			),
			Category(
	title = R.string.settings_misc_section_title,
	ignore = true,
	children = listOf(
				SwitchPref(key = "TemperatureUnitF", title = R.string.imperial_units_title, default = "false", icon = R.drawable.ic_imperial_unit, summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
			),
			Category(
	title = R.string.settings_about_section_title,
	ignore = true,
	children = listOf(
				ActionRow(key = "GitHubRepo", title = R.string.github_repo_title, summary = R.string.github_repo_summary, icon = R.drawable.ic_github),
				ActionRow(key = "TelegramGroup", title = R.string.telegram_group_title, summary = R.string.telegram_group_summary, icon = R.drawable.ic_telegram),
				ActionRow(key = "CrowdinProject", title = R.string.crowdin_title, summary = R.string.crowdin_summary, icon = R.drawable.ic_crowdin),
				ActionRow(key = "UsageWiki", title = R.string.usage_title, summary = R.string.usage_summary, icon = R.drawable.ic_usage),
	),
			),
	),
)
