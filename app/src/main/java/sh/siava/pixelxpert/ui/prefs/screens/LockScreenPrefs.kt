package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object LockScreenPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("Depth Wallpaper", "Depth Wallpaper"),
        PrefNode.SwitchPref(
            key = "DWallpaperEnabled",
            title = "Depth Wallpaper Effect",
            summary = "Place subject segment in front of the lockscreen clock",
            defaultValue = false,
            requiresSystemUIRestart = true
        ),
        PrefNode.SliderPref(
            key = "DWOpacity",
            title = "Subject Opacity",
            summary = "Transparency of segmented foreground subject",
            min = 0f,
            max = 255f,
            step = 5f,
            defaultValue = 255f,
            valueFormatter = { "${(it * 100 / 255).toInt()}%" },
            dependencyKey = "DWallpaperEnabled",
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Security & Unlock", "Security & Unlock"),
        PrefNode.SwitchPref(
            key = "easy_unlock_enabled",
            title = "Quick Unlock (PIN / Password)",
            summary = "Unlock immediately upon entering correct code without pressing enter",
            defaultValue = false,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "scramble_pin_layout",
            title = "Scramble PIN Pad",
            summary = "Randomize number button positions on every lockscreen appearance",
            defaultValue = false,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "BlockQSPowerOnLockscreen",
            title = "Block Sensitive QS Tiles & Power Menu",
            summary = "Prevents toggling network tiles and opening the power menu while the device is locked",
            defaultValue = false,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "fingerprint_while_dozing",
            title = "Fingerprint While Dozing",
            summary = "Keep fingerprint sensor active while ambient display is active",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Shortcuts", "Shortcuts"),
        PrefNode.SwitchPref(
            key = "custom_lockscreen_shortcuts",
            title = "Custom Bottom Shortcuts",
            summary = "Allow launching any app from lockscreen bottom affordance corners",
            defaultValue = false,
            requiresSystemUIRestart = true
        )
    )
}
