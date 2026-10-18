package sh.siava.pixelxpert.utils

import android.annotation.SuppressLint
import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.PixelXpert
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.ui.misc.StateManager
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

class PreferenceHelper private constructor(private val mPreferences: ExtendedSharedPreferences) {

    companion object {
        @JvmField
        var instance: PreferenceHelper? = null

        @JvmStatic
        fun init() {
            PreferenceHelper(PixelXpert.instance.defaultPreferences)
        }

        @JvmStatic
        fun checkIfRequiresSystemUIRestart(context: Context?, key: String?) {
            if (context == null || key == null) return

            val stateManager: StateManager = PixelXpert.instance.stateManager

            when (key) {
                // SystemUI restart
                "DWallpaperEnabled",
                "displayOverride",
                "QSLabelScaleFactor",
                "QSSecondaryLabelScaleFactor",
                "DisableLockScreenPill",
                "ThreeButtonLayoutMod",
                "ThreeButtonLeft",
                "ThreeButtonCenter",
                "ThreeButtonRight" -> stateManager.requiresSystemUIRestart = true

                // Device restart
                "volumeStps",
                "force_hotspot" -> stateManager.requiresDeviceRestart = true
            }
        }

        @JvmStatic
        fun isVisible(key: String): Boolean {
            val inst = instance ?: return true

            return when (key) {
                "ForceThemedLauncherIcons" -> !inst.mPreferences.getBoolean("DisableThemedIconsPref", false)

                "TaskbarAsRecents",
                "taskbarHeightOverride",
                "TaskbarRadiusOverride" -> {
                    val taskBarMode = inst.mPreferences.getString("taskBarMode", "0")?.toIntOrNull() ?: 0
                    taskBarMode == 1
                }

                "EnableGoogleRecents" -> !inst.mPreferences.getBoolean("TaskbarAsRecents", false)

                "TaskbarHideAllAppsIcon" -> inst.mPreferences.getBoolean("TaskbarAsRecents", false)

                "displayOverride" -> inst.mPreferences.getBoolean("displayOverrideEnabled", false)

                "carrierTextValue" -> inst.mPreferences.getBoolean("carrierTextMod", false)

                "batteryFastChargingColor",
                "batteryPowerSaveColor",
                "batteryChargingColor",
                "batteryWarningColor",
                "batteryCriticalColor" -> {
                    var critZero = false
                    var warnZero = false
                    val bBarLevels = inst.mPreferences.getSliderValues("batteryWarningRange", 0f)

                    if (bBarLevels.isNotEmpty()) {
                        critZero = bBarLevels[0] == 0f
                        if (bBarLevels.size > 1) {
                            warnZero = bBarLevels[1] == 0f
                        }
                    }
                    val bBarEnabled = inst.mPreferences.getBoolean("BBarEnabled", false)
                    val transitColors = inst.mPreferences.getBoolean("BBarTransitColors", false)

                    when (key) {
                        "batteryFastChargingColor" -> inst.mPreferences.getBoolean("indicateFastCharging", false) && bBarEnabled
                        "batteryChargingColor" -> inst.mPreferences.getBoolean("indicateCharging", false) && bBarEnabled
                        "batteryPowerSaveColor" -> inst.mPreferences.getBoolean("indicatePowerSave", false) && bBarEnabled
                        "batteryWarningColor" -> !warnZero && bBarEnabled
                        else -> (!critZero || transitColors) && bBarEnabled && !warnZero
                    }
                }

                "networkTrafficRXTop" -> inst.mPreferences.getBoolean("networkOnSBEnabled", false) &&
                        inst.mPreferences.getString("networkTrafficMode", "0") == "0"

                "networkTrafficColorful" -> inst.mPreferences.getBoolean("networkOnSBEnabled", false) &&
                        inst.mPreferences.getString("networkTrafficMode", "0") != "3"

                "networkTrafficDLColor",
                "networkTrafficULColor" -> inst.mPreferences.getBoolean("networkOnSBEnabled", false) &&
                        inst.mPreferences.getBoolean("networkTrafficColorful", true)

                "SBCBeforeClockColor",
                "SBCClockColor",
                "SBCAfterClockColor" -> inst.mPreferences.getBoolean("SBCClockColorful", false)

                "ThreeButtonLeft",
                "ThreeButtonCenter",
                "ThreeButtonRight" -> inst.mPreferences.getBoolean("ThreeButtonLayoutMod", false)

                "network_settings_header",
                "networkTrafficPosition",
                "networkTrafficMode",
                "networkTrafficShowIcons",
                "networkTrafficInterval",
                "networkTrafficThreshold",
                "networkTrafficOpacity" -> inst.mPreferences.getBoolean("networkOnSBEnabled", false)

                "SyncNTPTimeNow",
                "TimeSyncInterval",
                "NTPServers" -> inst.mPreferences.getBoolean("SyncNTPTime", false)

                "systemIconSortPlan" -> inst.mPreferences.getBoolean("systemIconsMultiRow", false)

                "networkStatDLColor",
                "networkStatULColor" -> inst.mPreferences.getBoolean("networkStatsColorful", false)

                "NetworkStatsStartTime" -> inst.mPreferences.getString("NetworkStatsStartBase", "0") == "0"
                "NetworkStatsWeekStart" -> inst.mPreferences.getString("NetworkStatsStartBase", "0") == "1"
                "NetworkStatsMonthStart" -> inst.mPreferences.getString("NetworkStatsStartBase", "0") == "2"

                "QSPulldownPercent",
                "QSPulldownSide",
                "oneFingerPullupEnabled" -> inst.mPreferences.getBoolean("QSPullodwnEnabled", false)

                "isFlashLevelGlobal" -> inst.mPreferences.getBoolean("leveledFlashTile", false)

                "BackLeftHeight" -> inst.mPreferences.getBoolean("BackFromLeft", true)

                "BackRightHeight" -> inst.mPreferences.getBoolean("BackFromRight", true)

                "leftSwipeUpPercentage" -> inst.mPreferences.getString("leftSwipeUpAction", "-1") != "-1"

                "rightSwipeUpPercentage" -> inst.mPreferences.getString("rightSwipeUpAction", "-1") != "-1"

                "UpdateWifiOnly" -> inst.mPreferences.getBoolean("AutoUpdate", true)

                "SegmentorAI",
                "DWOpacity",
                "DWonAOD" -> inst.mPreferences.getBoolean("DWallpaperEnabled", false)

                else -> true
            }
        }

        @JvmStatic
        fun isEnabled(key: String): Boolean {
            val inst = instance ?: return true
            return when (key) {
                "BBOnlyWhileCharging", "BBOnBottom", "BBOpacity", "BBarHeight", "BBSetCentered",
                "BBAnimateCharging", "indicateCharging", "indicateFastCharging",
                "indicatePowerSave", "batteryWarningRange" ->
                    inst.mPreferences.getBoolean("BBarEnabled", false)
                "BBarTransitColors" -> inst.mPreferences.getBoolean("BBarEnabled", false) &&
                        !inst.mPreferences.getBoolean("BBarColorful", false)
                "BBarColorful" -> inst.mPreferences.getBoolean("BBarEnabled", false) &&
                        !inst.mPreferences.getBoolean("BBarTransitColors", false)
                else -> true
            }
        }

        @SuppressLint("DefaultLocale")
        @JvmStatic
        fun getSummary(context: Context, key: String): String? {
            val inst = instance ?: return null
            when (key) {
                "FastChargingWattage" -> {
                    return labelFastChargingWattage(context, inst.mPreferences.getSliderInt("FastChargingWattage", 5))
                }
                "VolumeDialogTimeout" -> {
                    val timeout = inst.mPreferences.getSliderInt("VolumeDialogTimeout", 3000)
                    return if (timeout == 3000) context.getString(R.string.word_default)
                    else "$timeout ${context.getString(R.string.milliseconds)}"
                }
                "taskbarHeightOverride" -> {
                    val height = inst.mPreferences.getSliderInt("taskbarHeightOverride", 100)
                    return if (height != 100) "$height%" else context.getString(R.string.word_default)
                }
                "TaskbarRadiusOverride" -> {
                    val radius = inst.mPreferences.getSliderInt("TaskbarRadiusOverride", 1)
                    return if (radius != 1) "${radius}x" else context.getString(R.string.word_default)
                }
                "KeyGuardDimAmount" -> {
                    val dim = inst.mPreferences.getSliderInt("KeyGuardDimAmount", -1)
                    return if (dim < 0) context.getString(R.string.word_default) else "$dim%"
                }
                "clockMultiRowStartOffset" -> {
                    val offset = inst.mPreferences.getSliderInt("clockMultiRowStartOffset", 2)
                    return if (offset == 0) context.getString(R.string.word_default) else "$offset dp"
                }
                "BBOpacity" -> return "${inst.mPreferences.getSliderInt("BBOpacity", 100)}%"
                "BBarHeight" -> return "${inst.mPreferences.getSliderInt("BBarHeight", 100)}%"
                "networkTrafficInterval" -> return "${inst.mPreferences.getSliderInt("networkTrafficInterval", 1)} second(s)"
                "volumeStps" -> {
                    val stps = inst.mPreferences.getSliderInt("volumeStps", 0)
                    val label = if (stps == 10) context.getString(R.string.word_default) else stps.toString()
                    return "$label - (${context.getString(R.string.restart_needed)})"
                }
                "displayOverride" -> {
                    val override = inst.mPreferences.getSliderFloat("displayOverride", 100f)
                    val increasedArea = abs(override.toDouble().pow(2.0) / 100 - 100).roundToLong()
                    val areaLabel = if (override > 100) context.getString(R.string.more_area) else context.getString(R.string.less_area)
                    val desc = if (override == 100f) context.getString(R.string.word_default) else "$override% - $increasedArea% $areaLabel"
                    return "$desc \n (${context.getString(R.string.sysui_restart_needed)})"
                }
                "HeadupAutoDismissNotificationDecay" -> {
                    val decay = inst.mPreferences.getSliderInt("HeadupAutoDismissNotificationDecay", 5000)
                    return "$decay ${context.getString(R.string.milliseconds)}"
                }
                "TimeSyncInterval" -> {
                    return "${inst.mPreferences.getSliderInt("TimeSyncInterval", 24)} ${context.getString(R.string.hours)}"
                }
                "hotSpotTimeoutSecs" -> {
                    val timeout = (inst.mPreferences.getSliderFloat("hotSpotTimeoutSecs", 0f)).toLong()
                    return if (timeout > 0) "${timeout / 60} ${context.getString(R.string.minutes_word)}"
                    else context.getString(R.string.word_default)
                }
                "hotSpotMaxClients" -> {
                    val clients = inst.mPreferences.getSliderInt("hotSpotMaxClients", 0)
                    return if (clients > 0) clients.toString() else context.getString(R.string.word_default)
                }
                "statusbarHeightFactor" -> {
                    val factor = inst.mPreferences.getSliderInt("statusbarHeightFactor", 100)
                    return if (factor == 100) context.getString(R.string.word_default) else "$factor%"
                }
                "QQSRows" -> {
                    val rows = inst.mPreferences.getSliderInt("QQSRows", 2)
                    return if (rows == 2) context.getString(R.string.word_default) else rows.toString()
                }
                "QSColQty" -> {
                    val cols = inst.mPreferences.getSliderInt("QSColQty", 4)
                    return if (cols == 4) context.getString(R.string.word_default) else cols.toString()
                }
                "QSRowQty" -> {
                    val rows = inst.mPreferences.getSliderInt("QSRowQty", 0)
                    return if (rows == 0) context.getString(R.string.word_default) else rows.toString()
                }
                "QQSRowsL" -> {
                    val rows = inst.mPreferences.getSliderInt("QQSRowsL", 1)
                    return if (rows == 1) context.getString(R.string.word_default) else rows.toString()
                }
                "QSRowQtyL" -> {
                    val rows = inst.mPreferences.getSliderInt("QSRowQtyL", 0)
                    return if (rows == 0) context.getString(R.string.word_default) else rows.toString()
                }
                "QSColQtyL" -> {
                    val cols = inst.mPreferences.getSliderInt("QSColQtyL", 8)
                    return if (cols == 8) context.getString(R.string.word_default) else cols.toString()
                }
                "QSPulldownPercent" -> return "${inst.mPreferences.getSliderInt("QSPulldownPercent", 25)}%"
                "QSLabelScaleFactor" -> {
                    val factor = inst.mPreferences.getSliderFloat("QSLabelScaleFactor", 0f)
                    return "${(factor + 100).toInt()}%"
                }
                "QSSecondaryLabelScaleFactor" -> {
                    val factor = inst.mPreferences.getSliderFloat("QSSecondaryLabelScaleFactor", 0f)
                    return "${(factor + 100).toInt()}%"
                }
                "GesPillWidthModPos" -> {
                    val factor = inst.mPreferences.getSliderInt("GesPillWidthModPos", 50) * 2
                    return "$factor${context.getString(R.string.pill_width_summary)}"
                }
                "GesPillHeightFactor" -> {
                    val factor = inst.mPreferences.getSliderInt("GesPillHeightFactor", 100)
                    return "$factor${context.getString(R.string.pill_width_summary)}"
                }
                "BackLeftHeight" -> return "${inst.mPreferences.getSliderInt("BackLeftHeight", 100)}%"
                "BackRightHeight" -> return "${inst.mPreferences.getSliderInt("BackRightHeight", 100)}%"
                "leftSwipeUpPercentage" -> return "${inst.mPreferences.getSliderFloat("leftSwipeUpPercentage", 25f)}%"
                "rightSwipeUpPercentage" -> return "${inst.mPreferences.getSliderFloat("rightSwipeUpPercentage", 25f)}%"
                "swipeUpPercentage" -> return "${inst.mPreferences.getSliderFloat("swipeUpPercentage", 20f)}%"
                "appLanguage" -> {
                    val languageNames = context.resources.getStringArray(R.array.languages_names)
                    val languageValues = context.resources.getStringArray(R.array.languages_values)
                    val currentCode = inst.mPreferences.getString("appLanguage", context.resources.configuration.locales[0].language)
                    val idx = languageValues.indexOf(currentCode).let { if (it < 0) languageValues.indexOf("en") else it }
                    return languageNames.getOrNull(idx) ?: "English"
                }
                "CheckForUpdate" -> return context.getString(R.string.current_version, BuildConfig.VERSION_NAME)
                "FlatStandbyTime" -> return String.format(context.getString(R.string.duration_seconds), inst.mPreferences.getSliderInt("FlatStandbyTime", 5))
            }
            return null
        }

        @JvmStatic
        fun labelFastChargingWattage(context: Context, value: Int): String {
            return if (value <= 5) context.getString(R.string.word_default)
            else "$value ${context.getString(R.string.watts)}"
        }

        @JvmStatic
        fun setupPreference(preference: Preference) {
            runCatching {
                val key = preference.key ?: return
                preference.isVisible = isVisible(key)
                preference.isEnabled = isEnabled(key)
                val summary = getSummary(preference.context, key)
                if (summary != null) {
                    preference.summary = summary
                }
            }
        }

        @JvmStatic
        fun setupAllPreferences(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                runCatching {
                    val pref = group.getPreference(i)
                    if (pref is PreferenceGroup) {
                        setupAllPreferences(pref)
                    } else {
                        setupPreference(pref)
                    }
                }
            }
        }

        @JvmStatic
        fun setupMainSwitches(group: PreferenceGroup) {
            setupAllPreferences(group)
        }
    }

    init {
        instance = this
    }
}
