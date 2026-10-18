package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object NavigationPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("Gesture Bar", "Gesture Bar"),
        PrefNode.SwitchPref(
            key = "hide_navigation_bar_pill",
            title = "Hide Navigation Pill",
            summary = "Completely remove navigation bar handle while preserving gestures",
            defaultValue = false,
            requiresSystemUIRestart = true
        ),
        PrefNode.SliderPref(
            key = "nav_bar_pill_height",
            title = "Navigation Pill Thickness",
            summary = "Adjust vertical thickness of the home gesture bar",
            min = 1f,
            max = 8f,
            step = 1f,
            defaultValue = 4f,
            valueFormatter = { "${it.toInt()} dp" },
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Back Gestures", "Back Gestures"),
        PrefNode.SliderPref(
            key = "back_gesture_height_percent",
            title = "Back Gesture Trigger Area Height",
            summary = "Portion of screen height sensitive to edge back swipe",
            min = 20f,
            max = 100f,
            step = 5f,
            defaultValue = 100f,
            valueFormatter = { "${it.toInt()}%" },
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("3-Button Navigation", "3-Button Navigation"),
        PrefNode.SwitchPref(
            key = "ThreeButtonLayoutMod",
            title = "Swap Back and Recents Buttons",
            summary = "Place back button on the right and recents on the left",
            defaultValue = false,
            requiresSystemUIRestart = true
        )
    )
}
