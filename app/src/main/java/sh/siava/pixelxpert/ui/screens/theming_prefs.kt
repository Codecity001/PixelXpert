package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val theming_prefs: PrefScreen = PrefScreen(
	key = "theming_prefs",
	nodes = listOf(
			Illustration(key = "theme_illustration", lottieRawRes = R.raw.theme, ignore = true),
			NavRow(key = "icon_packs", title = R.string.icon_packs_title, route = "icon_pack"),
			SwitchPref(key = "ForceThemedLauncherIcons", title = R.string.force_themed_icons_title, summary = R.string.force_themed_icons_summary, default = "false"),
	),
)
