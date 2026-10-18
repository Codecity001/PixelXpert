package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object MiscPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("App Management", "App Management"),
        PrefNode.SwitchPref(
            key = "enable_app_cloning_all",
            title = "Allow App Cloning for Any App",
            summary = "Unlock OEM app cloning restriction to duplicate any installed app",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Buttons & Hardware", "Buttons & Hardware"),
        PrefNode.SwitchPref(
            key = "volume_keys_skip_track",
            title = "Volume Key Track Skip",
            summary = "Long-press volume buttons when screen is off to change music tracks",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "torch_high_brightness",
            title = "Ultra Bright Flashlight",
            summary = "Increase maximum torch LED level above standard OEM limits",
            defaultValue = false,
            requiresSystemUIRestart = false
        ),
        PrefNode.Category("Screen & Capture", "Screen & Capture"),
        PrefNode.SwitchPref(
            key = "disable_screenshot_shutter_sound",
            title = "Mute Screenshot Shutter Sound",
            summary = "Capture screenshots without triggering audio feedback",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "bypass_secure_window_flag",
            title = "Allow Screenshots in Restricted Apps",
            summary = "Override FLAG_SECURE to permit screenshots and screen recording anywhere",
            defaultValue = false,
            requiresSystemUIRestart = true
        )
    )
}
