package sh.siava.pixelxpert.ui.screens

import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.prefs.PrefScreen
import sh.siava.pixelxpert.ui.prefs.node.*

val dialer_prefs: PrefScreen = PrefScreen(
	key = "dialer_prefs",
	nodes = listOf(
			Illustration(key = "dialer_illustration", lottieRawRes = R.raw.dialer, ignore = true),
			SwitchPref(key = "vibrateOnAnswered", title = R.string.vibrate_on_call_active_title, default = "false", summaryOn = R.string.vibration_type_notification, summaryOff = R.string.general_off),
			SwitchPref(key = "vibrateOnDrop", title = R.string.vibrate_on_call_drop_title, default = "false", summaryOn = R.string.vibration_type_notification, summaryOff = R.string.general_off),
			SwitchPref(key = "DialerRemoveRecordMessage", title = R.string.dialer_mute_record_message_title, default = "false", summaryOn = R.string.dialer_mute_record_message_summary, summaryOff = R.string.general_off),
			SwitchPref(key = "force_volte", title = R.string.radio_force_volte_nr, default = "false", summaryOn = R.string.general_on, summaryOff = R.string.general_off),
	),
)
