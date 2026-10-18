package sh.siava.pixelxpert

import android.content.Context
import android.content.Intent
import sh.siava.pixelxpert.BuildConfig.APPLICATION_ID
import sh.siava.pixelxpert.xposed.utils.BootLoopProtector

object Constants {
    const val ACTION_HOME: String = "$APPLICATION_ID.ACTION_HOME"
    const val ACTION_BACK: String = "$APPLICATION_ID.ACTION_BACK"
    const val ACTION_SLEEP: String = "$APPLICATION_ID.ACTION_SLEEP"
    const val ACTION_SWITCH_APP_PROFILE: String = "$APPLICATION_ID.ACTION_SWITCH_APP_PROFILE"
    const val ACTION_PROFILE_SWITCH_AVAILABLE: String = "$APPLICATION_ID.ACTION_PROFILE_SWITCH_AVAILABLE"
    const val ACTION_CHECK_XPOSED_ENABLED: String = "$APPLICATION_ID.ACTION_CHECK_XPOSED_ENABLED"
    const val ACTION_XPOSED_CONFIRMED: String = "$APPLICATION_ID.ACTION_XPOSED_CONFIRMED"
    const val ACTION_KSU_ACQUIRE_ROOT: String = "${APPLICATION_ID}ACTION_KSU_ACQUIRE_ROOT"

    @JvmField
    var DEFAULT_PREFS_FILE_NAME: String = "${APPLICATION_ID}_preferences"

    const val PREF_NO_CUTOUT_ENABLED: String = "noCutoutEnabled"
    const val PROP_NO_CUTOUT_ENABLED: String = "pixelxpert.no_cutout_enabled"

    // None of these are used for matching package names during modpack loading.
    const val SYSTEM_UI_PACKAGE: String = "com.android.systemui"
    const val SYSTEM_FRAMEWORK_PACKAGE: String = "android"
    const val TELECOM_SERVER_PACKAGE: String = "com.android.server.telecom"
    const val LAUNCHER_PACKAGE: String = "com.google.android.apps.nexuslauncher"
    const val SETTINGS_PACKAGE: String = "com.android.settings"
    const val DIALER_PACKAGE: String = "com.google.android.dialer"
    const val KSU_PACKAGE: String = "me.weishu.kernelsu"
    const val KSU_NEXT_PACKAGE: String = "com.rifsxd.ksunext"

    const val LAUNCH_REASON_EXTRA: String = "LAUNCH_REASON"
    const val LAUNCH_REASON_XPOSED_SERVICE_FAIL: String = "XPOSED_SERVICE_FAIL"
    const val PX_ROOT_EXTRA: String = "EXTRA_PX_ROOT"

    const val AI_METHOD_MLKIT: Int = 0
    const val AI_METHOD_PYTORCH: Int = 1

    @JvmField
    val PREF_UPDATE_EXCLUSIONS: List<String> = listOf(
        BootLoopProtector.LOAD_TIME_KEY_KEY,
        BootLoopProtector.PACKAGE_STRIKE_KEY_KEY
    )

    @JvmStatic
    fun getLockScreenSubjectCachePath(context: Context): String {
        return "${context.cacheDir.absolutePath}/lswt.png"
    }

    @JvmStatic
    fun getLockScreenBitmapCachePath(context: Context): String {
        return "${context.cacheDir.absolutePath}/lsw.jpg"
    }

    @JvmStatic
    fun getAppProfileSwitchIntent(): Intent {
        return Intent()
            .setAction(ACTION_SWITCH_APP_PROFILE)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
}
