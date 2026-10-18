package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object QuickSettingsPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("Grid & Dimensions", "Grid & Dimensions"),
        PrefNode.SliderPref(
            key = "qs_columns_count",
            title = "Quick Settings Columns",
            summary = "Number of tile columns in expanded QS panel",
            min = 2f,
            max = 6f,
            step = 1f,
            defaultValue = 2f,
            valueFormatter = { "${it.toInt()}" },
            requiresSystemUIRestart = true
        ),
        PrefNode.SliderPref(
            key = "qs_rows_count",
            title = "Quick Settings Rows",
            summary = "Number of tile rows per QS page",
            min = 2f,
            max = 6f,
            step = 1f,
            defaultValue = 4f,
            valueFormatter = { "${it.toInt()}" },
            requiresSystemUIRestart = true
        ),
        PrefNode.SliderPref(
            key = "QSLabelScaleFactor",
            title = "Tile Label Text Size",
            summary = "Scale factor for QS tile primary text",
            min = -30f,
            max = 50f,
            step = 5f,
            defaultValue = 0f,
            valueFormatter = { "${(it + 100).toInt()}%" },
            requiresSystemUIRestart = true
        ),
        PrefNode.SliderPref(
            key = "QSSecondaryLabelScaleFactor",
            title = "Tile Secondary Text Size",
            summary = "Scale factor for QS tile subtitle text",
            min = -30f,
            max = 50f,
            step = 5f,
            defaultValue = 0f,
            valueFormatter = { "${(it + 100).toInt()}%" },
            requiresSystemUIRestart = true
        ),
        PrefNode.Category("Brightness Slider", "Brightness Slider"),
        PrefNode.SwitchPref(
            key = "qs_brightness_slider_haptic",
            title = "Brightness Haptic Feedback",
            summary = "Vibrate softly while sliding brightness controller",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "qs_show_auto_brightness_button",
            title = "Auto-Brightness Toggle",
            summary = "Show quick auto-brightness toggle icon in QS slider",
            defaultValue = true,
            requiresSystemUIRestart = true
        )
    )
}
