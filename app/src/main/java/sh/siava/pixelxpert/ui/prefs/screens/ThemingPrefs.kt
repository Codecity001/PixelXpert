package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object ThemingPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("Launcher & App Icons", "Launcher & App Icons"),
        PrefNode.SwitchPref(
            key = "force_themed_icons_all_apps",
            title = "Enforce Themed Icons for All Apps",
            summary = "Generate monochrome silhouettes for apps missing official themed icons",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "pitch_black_theme",
            title = "Pure AMOLED Pitch Black",
            summary = "Force solid #000000 background for dark theme surfaces and QS",
            defaultValue = false,
            requiresSystemUIRestart = true
        ),
        PrefNode.SwitchPref(
            key = "GoogleSettingsDesign",
            title = "Google Settings Design",
            summary = "Use grouped category bubbles and colorful tinted icon badges",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Typography", "Typography"),
        PrefNode.ListPref(
            key = "system_custom_font",
            title = "System Interface Font",
            summary = "Choose system-wide font family replacement",
            entries = listOf("System Default", "Google Sans", "Inter", "Roboto Flex", "Ubuntu"),
            entryValues = listOf("default", "google_sans", "inter", "roboto_flex", "ubuntu"),
            defaultValue = "default",
            requiresSystemUIRestart = true
        )
    )
}
