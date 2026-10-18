package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object StatusbarPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("Clock & Date", "Clock & Date"),
        PrefNode.ListPref(
            key = "clock_position",
            title = "Clock Position",
            summary = "Choose status bar clock alignment",
            entries = listOf("Left", "Center", "Right", "Hide"),
            entryValues = listOf("0", "1", "2", "3"),
            defaultValue = "0",
            requiresSystemUIRestart = true
        ),
        PrefNode.SwitchPref(
            key = "show_statusbar_seconds",
            title = "Display Seconds",
            summary = "Show seconds in the status bar clock",
            defaultValue = false,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "show_clock_date",
            title = "Display Date Beside Clock",
            summary = "Show current date next to the clock",
            defaultValue = false,
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Battery Indicator", "Battery Indicator"),
        PrefNode.ListPref(
            key = "battery_style",
            title = "Battery Style",
            summary = "Select status bar battery presentation",
            entries = listOf("Portrait", "Landscape (Circle)", "Circle Dotted", "Hidden", "Text Only"),
            entryValues = listOf("0", "1", "2", "3", "4"),
            defaultValue = "0",
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "show_battery_percent",
            title = "Battery Percentage",
            summary = "Show percentage text beside battery icon",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Layout & Gestures", "Layout & Gestures"),
        PrefNode.SwitchPref(
            key = "statusbar_double_tap_sleep",
            title = "Double Tap to Sleep",
            summary = "Double tap empty status bar space to turn screen off",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.SliderPref(
            key = "statusbar_side_padding",
            title = "Status Bar Side Padding",
            summary = "Horizontal inset padding for curved screen corners",
            min = 0f,
            max = 48f,
            step = 2f,
            defaultValue = 8f,
            valueFormatter = { "${it.toInt()} dp" },
            requiresSystemUIRestart = true
        ),
        PrefNode.SwitchPref(
            key = "multi_statusbar_rows",
            title = "Multi-row Statusbar",
            summary = "Stack status icons across multiple rows when crowded",
            defaultValue = false,
            requiresSystemUIRestart = true
        )
    )
}
