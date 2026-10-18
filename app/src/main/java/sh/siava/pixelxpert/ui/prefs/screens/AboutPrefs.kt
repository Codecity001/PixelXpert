package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.ui.prefs.node.PrefNode
import sh.siava.pixelxpert.utils.AppUtils

object AboutPrefs {
    fun getNodes(onOpenChangelog: () -> Unit = {}): List<PrefNode> = listOf(
        PrefNode.Category("Version Information", "Version Information"),
        PrefNode.Header(
            key = "app_version_info",
            title = "PixelXpert v${BuildConfig.VERSION_NAME}",
            summary = "Target SDK 37 • Pure Kotlin & LibXposed 102"
        ),
        PrefNode.ActionPref(
            key = "changelog_action",
            title = "View Changelog",
            summary = "What's new in this release",
            onClick = onOpenChangelog
        ),
        PrefNode.Category("System Controls", "System Controls"),
        PrefNode.ActionPref(
            key = "restart_systemui_action",
            title = "Restart SystemUI",
            summary = "Restart SystemUI process to apply pending statusbar/lockscreen hooks",
            onClick = { AppUtils.restart("systemui") }
        ),
        PrefNode.ActionPref(
            key = "reboot_device_action",
            title = "Reboot Device",
            summary = "Perform soft reboot using root shell",
            onClick = { AppUtils.restart("system") }
        )
    )
}
