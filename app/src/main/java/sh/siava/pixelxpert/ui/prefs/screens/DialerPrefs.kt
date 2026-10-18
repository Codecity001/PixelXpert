package sh.siava.pixelxpert.ui.prefs.screens

import sh.siava.pixelxpert.ui.prefs.node.PrefNode

object DialerPrefs {
    fun getNodes(): List<PrefNode> = listOf(
        PrefNode.Category("Call Recording", "Call Recording"),
        PrefNode.SwitchPref(
            key = "dialer_silence_call_recording_announcement",
            title = "Silence Recording Announcement",
            summary = "Mute the 'This call is now being recorded' audio warning in Google Dialer",
            defaultValue = true,
            requiresSystemUIRestart = false
        ),
        PrefNode.SwitchPref(
            key = "dialer_auto_record_calls",
            title = "Automatic Call Recording",
            summary = "Begin recording incoming and outgoing phone calls automatically",
            defaultValue = false,
            requiresSystemUIRestart = false
        )
    )
}
