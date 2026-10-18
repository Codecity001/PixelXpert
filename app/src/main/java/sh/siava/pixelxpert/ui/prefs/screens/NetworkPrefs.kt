package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object NetworkPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("Wi-Fi Hotspot", "Wi-Fi Hotspot"),
        PrefNode.ListPref(
            key = "hotspot_auto_turn_off_timeout",
            title = "Hotspot Inactivity Timeout",
            summary = "Automatic shutdown when no devices are connected",
            entries = listOf("Never", "5 Minutes", "10 Minutes", "15 Minutes", "30 Minutes"),
            entryValues = listOf("0", "300", "600", "900", "1800"),
            defaultValue = "600",
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Developer Tools", "Developer Tools"),
        PrefNode.SwitchPref(
            key = "pin_wireless_adb_port",
            title = "Fixed Wireless ADB Port",
            summary = "Pin wireless debugging port to 5555 across reboots",
            defaultValue = true,
            requiresSystemUIRestart = true
        )
    )
}
