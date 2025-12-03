package sh.siava.pixelxpert.utils;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import com.google.android.material.slider.LabelFormatter;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;

import dagger.hilt.android.EntryPointAccessors;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.PixelXpert;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.di.StateManagerEntryPoint;
import sh.siava.pixelxpert.ui.misc.StateManager;
import sh.siava.pixelxpert.ui.preferences.MaterialPrimarySwitchPreference;
import sh.siava.rangesliderpreference.RangeSliderPreference;

public class PreferenceHelper {
	private final ExtendedSharedPreferences mPreferences;

	public static PreferenceHelper instance;

	public static void init() {
		new PreferenceHelper(PixelXpert.get().getDefaultPreferences());
	}

	private PreferenceHelper(ExtendedSharedPreferences prefs) {
		mPreferences = prefs;

		instance = this;
	}

	public static void checkIfRequiresSystemUIRestart(Context context, String key) {
		if (context == null || key == null) return;

		StateManager stateManager = EntryPointAccessors
				.fromApplication(context.getApplicationContext(), StateManagerEntryPoint.class)
				.getStateManager();

		switch (key) {
			// SystemUI restart
			case "DWallpaperEnabled":
			case "displayOverride":
			case "QSLabelScaleFactor":
			case "QSSecondaryLabelScaleFactor":
			case "DisableLockScreenPill":
			case "ThreeButtonLayoutMod":
			case "ThreeButtonLeft":
			case "ThreeButtonCenter":
			case "ThreeButtonRight":
				stateManager.setRequiresSystemUIRestart(true);
				break;

			// Device restart
			case "volumeStps":
			case "force_hotspot":
				stateManager.setRequiresDeviceRestart(true);
				break;
		}
	}

	public static boolean isVisible(String key) {
		if (instance == null) return true;

		switch (key) {
			case "ForceThemedLauncherIcons":
				return !instance.mPreferences.getBoolean("DisableThemedIconsPref", false);

			case "TaskbarAsRecents":
			case "taskbarHeightOverride":
			case "TaskbarRadiusOverride":
				int taskBarMode = Integer.parseInt(instance.mPreferences.getString("taskBarMode", "0"));
				return taskBarMode == 1;

			case "EnableGoogleRecents":
				return !instance.mPreferences.getBoolean("TaskbarAsRecents", false);

			case "TaskbarHideAllAppsIcon":
				return instance.mPreferences.getBoolean("TaskbarAsRecents", false);

			case "displayOverride":
				return instance.mPreferences.getBoolean("displayOverrideEnabled", false);

			case "carrierTextValue":
				return instance.mPreferences.getBoolean("carrierTextMod", false);

			case "batteryFastChargingColor":
			case "batteryPowerSaveColor":
			case "batteryChargingColor":
			case "batteryWarningColor":
			case "batteryCriticalColor":

				boolean critZero = false, warnZero = false;
				List<Float> BBarLevels = instance.mPreferences.getSliderValues("batteryWarningRange", 0);

				if (!BBarLevels.isEmpty()) {
					critZero = BBarLevels.get(0) == 0;
					warnZero = BBarLevels.get(1) == 0;
				}
				boolean bBarEnabled = instance.mPreferences.getBoolean("BBarEnabled", false);
				boolean transitColors = instance.mPreferences.getBoolean("BBarTransitColors", false);

				return switch (key) {
					case "batteryFastChargingColor" ->
							instance.mPreferences.getBoolean("indicateFastCharging", false) && bBarEnabled;
					case "batteryChargingColor" ->
							instance.mPreferences.getBoolean("indicateCharging", false) && bBarEnabled;
					case "batteryPowerSaveColor" ->
							instance.mPreferences.getBoolean("indicatePowerSave", false) && bBarEnabled;
					case "batteryWarningColor" -> !warnZero && bBarEnabled;
					default ->  //batteryCriticalColor
							(!critZero || transitColors) && bBarEnabled && !warnZero;
				};

			case "networkTrafficRXTop":
				return (instance.mPreferences.getBoolean("networkOnSBEnabled", false)) && instance.mPreferences.getString("networkTrafficMode", "0").equals("0");

			case "networkTrafficColorful":
				return (instance.mPreferences.getBoolean("networkOnSBEnabled", false)) && !instance.mPreferences.getString("networkTrafficMode", "0").equals("3");

			case "networkTrafficDLColor":
			case "networkTrafficULColor":
				return (instance.mPreferences.getBoolean("networkOnSBEnabled", false)) && instance.mPreferences.getBoolean("networkTrafficColorful", true);

			case "SBCBeforeClockColor":
			case "SBCClockColor":
			case "SBCAfterClockColor":
				return instance.mPreferences.getBoolean("SBCClockColorful", false);

			case "ThreeButtonLeft":
			case "ThreeButtonCenter":
			case "ThreeButtonRight":
				return instance.mPreferences.getBoolean("ThreeButtonLayoutMod", false);

			case "network_settings_header":
			case "networkTrafficPosition":
			case "networkTrafficMode":
			case "networkTrafficShowIcons":
			case "networkTrafficInterval":
			case "networkTrafficThreshold":
			case "networkTrafficOpacity":
				return instance.mPreferences.getBoolean("networkOnSBEnabled", false);

			case "SyncNTPTimeNow":
			case "TimeSyncInterval":
			case "NTPServers":
				return instance.mPreferences.getBoolean("SyncNTPTime", false);

			case "systemIconSortPlan":
				return instance.mPreferences.getBoolean("systemIconsMultiRow", false);

			case "networkStatDLColor":
			case "networkStatULColor":
				return instance.mPreferences.getBoolean("networkStatsColorful", false);

			case "NetworkStatsStartTime":
				return instance.mPreferences.getString("NetworkStatsStartBase", "0").equals("0");

			case "NetworkStatsWeekStart":
				return instance.mPreferences.getString("NetworkStatsStartBase", "0").equals("1");

			case "NetworkStatsMonthStart":
				return instance.mPreferences.getString("NetworkStatsStartBase", "0").equals("2");

			case "QSPulldownPercent":
			case "QSPulldownSide":
			case "oneFingerPullupEnabled":
				return instance.mPreferences.getBoolean("QSPullodwnEnabled", false);

			case "isFlashLevelGlobal":
				return instance.mPreferences.getBoolean("leveledFlashTile", false);

			case "BackLeftHeight":
				return instance.mPreferences.getBoolean("BackFromLeft", true);

			case "BackRightHeight":
				return instance.mPreferences.getBoolean("BackFromRight", true);

			case "leftSwipeUpPercentage":
				return !instance.mPreferences.getString("leftSwipeUpAction", "-1").equals("-1");

			case "rightSwipeUpPercentage":
				return !instance.mPreferences.getString("rightSwipeUpAction", "-1").equals("-1");

			case "UpdateWifiOnly":
				return instance.mPreferences.getBoolean("AutoUpdate", true);

			case "SegmentorAI":
			case "DWOpacity":
			case "DWonAOD":
				return instance.mPreferences.getBoolean("DWallpaperEnabled", false);
		}
		return true;
	}

	public static boolean isEnabled(String key) {
		return switch (key) {
			case "BBOnlyWhileCharging", "BBOnBottom", "BBOpacity", "BBarHeight", "BBSetCentered",
				 "BBAnimateCharging", "indicateCharging", "indicateFastCharging",
				 "indicatePowerSave", "batteryWarningRange" ->
					instance.mPreferences.getBoolean("BBarEnabled", false);
			case "BBarTransitColors" -> instance.mPreferences.getBoolean("BBarEnabled", false) &&
					!instance.mPreferences.getBoolean("BBarColorful", false);
			case "BBarColorful" -> instance.mPreferences.getBoolean("BBarEnabled", false) &&
					!instance.mPreferences.getBoolean("BBarTransitColors", false);
			default -> true;
		};
	}

	/**
	 * @noinspection UnnecessaryCallToStringValueOf
	 */
	@SuppressLint("DefaultLocale")
	@Nullable
	public static String getSummary(Context fragmentCompat, @NonNull String key) {
		switch (key) {
			case "FastChargingWattage":
				return labelFastChargingWattage(fragmentCompat, instance.mPreferences.getSliderInt("FastChargingWattage", 5));

			case "VolumeDialogTimeout":
				int VolumeDialogTimeout = instance.mPreferences.getSliderInt("VolumeDialogTimeout", 3000);
				return VolumeDialogTimeout == 3000
						? fragmentCompat.getString(R.string.word_default)
						: String.format("%s %s", VolumeDialogTimeout, fragmentCompat.getString(R.string.milliseconds));

			case "taskbarHeightOverride":
				int taskbarHeightOverride = instance.mPreferences.getSliderInt("taskbarHeightOverride", 100);
				return taskbarHeightOverride != 100f
						? taskbarHeightOverride + "%"
						: fragmentCompat.getString(R.string.word_default);

			case "TaskbarRadiusOverride":
				int TaskbarRadiusOverride = instance.mPreferences.getSliderInt("TaskbarRadiusOverride", 1);
				return TaskbarRadiusOverride != 1
						? TaskbarRadiusOverride + "x"
						: fragmentCompat.getString(R.string.word_default);

			case "KeyGuardDimAmount":
				int KeyGuardDimAmount = instance.mPreferences.getSliderInt("KeyGuardDimAmount", -1);
				return KeyGuardDimAmount < 0
						? fragmentCompat.getString(R.string.word_default)
						: KeyGuardDimAmount + "%";

			case "BBOpacity":
				return instance.mPreferences.getSliderInt("BBOpacity", 100) + "%";

			case "BBarHeight":
				return instance.mPreferences.getSliderInt("BBarHeight", 100) + "%";

			case "networkTrafficInterval":
				return instance.mPreferences.getSliderInt("networkTrafficInterval", 1) + " second(s)";

			case "volumeStps":
				int volumeStps = instance.mPreferences.getSliderInt("volumeStps", 0);
				return String.format("%s - (%s)", volumeStps == 10 ? fragmentCompat.getString(R.string.word_default) : String.valueOf(volumeStps), fragmentCompat.getString(R.string.restart_needed));

			case "displayOverride":
				float displayOverride = instance.mPreferences.getSliderFloat("displayOverride", 100f);

				double increasedArea = Math.round(Math.abs(Math.pow(displayOverride, 2) / 100 - 100));

				return String.format("%s \n (%s)", displayOverride == 100 ? fragmentCompat.getString(R.string.word_default) : String.format("%s%% - %s%% %s", String.valueOf(displayOverride), String.valueOf(increasedArea), displayOverride > 100 ? fragmentCompat.getString(R.string.more_area) : fragmentCompat.getString(R.string.less_area)), fragmentCompat.getString(R.string.sysui_restart_needed));

			case "HeadupAutoDismissNotificationDecay":
				int headsupDecayMillis = instance.mPreferences.getSliderInt("HeadupAutoDismissNotificationDecay", 5000);

				return headsupDecayMillis + " " + fragmentCompat.getString(R.string.milliseconds);

			case "TimeSyncInterval":
				return instance.mPreferences.getSliderInt("TimeSyncInterval", 24) + " " + fragmentCompat.getString(R.string.hours);

			case "hotSpotTimeoutSecs":
				long timeout = (long) (instance.mPreferences.getSliderFloat( "hotSpotTimeoutSecs", 0) * 1L);

				return timeout > 0
						? String.format("%d %s", timeout / 60, fragmentCompat.getString(R.string.minutes_word))
						: fragmentCompat.getString(R.string.word_default);

			case "hotSpotMaxClients":
				int clients = instance.mPreferences.getSliderInt("hotSpotMaxClients", 0);
				return clients > 0
						? String.valueOf(clients)
						: fragmentCompat.getString(R.string.word_default);


			case "statusbarHeightFactor":
				int statusbarHeightFactor = instance.mPreferences.getSliderInt("statusbarHeightFactor", 100);
				return statusbarHeightFactor == 100 ? fragmentCompat.getString(R.string.word_default) : statusbarHeightFactor + "%";

			case "QQSRows":
				int QQSRows = instance.mPreferences.getSliderInt("QQSRows", 2);
				return (QQSRows == 2) ? fragmentCompat.getString(R.string.word_default) : String.valueOf(QQSRows);

			case "QSColQty":
				int QSColQty = instance.mPreferences.getSliderInt("QSColQty", 4);
				if (QSColQty < 2) {
					instance.mPreferences.edit().putInt("QSColQty", 2).apply();
				}
				return (QSColQty == 4) ? fragmentCompat.getString(R.string.word_default) : String.valueOf(QSColQty);

			case "QSRowQty":
				int QSRowQty = instance.mPreferences.getSliderInt("QSRowQty", 0);
				return (QSRowQty == 0) ? fragmentCompat.getString(R.string.word_default) : String.valueOf(QSRowQty);

			case "QQSRowsL":
				int QQSRowsL = instance.mPreferences.getSliderInt("QQSRowsL", 1);
				return (QQSRowsL == 1) ? fragmentCompat.getString(R.string.word_default) : String.valueOf(QQSRowsL);

			case "QSRowQtyL":
				int QSRowQtyL = instance.mPreferences.getSliderInt("QSRowQtyL", 0);
				return (QSRowQtyL == 0) ? fragmentCompat.getString(R.string.word_default) : String.valueOf(QSRowQtyL);

			case "QSColQtyL":
				int QSColQtyL = instance.mPreferences.getSliderInt("QSColQtyL", 8);
				if (QSColQtyL < 4) {
					instance.mPreferences.edit().putInt("QSColQtyL", 4).apply();
				}
				return (QSColQtyL == 8) ? fragmentCompat.getString(R.string.word_default) : String.valueOf(QSColQtyL);

			case "QSPulldownPercent":
				return instance.mPreferences.getSliderInt("QSPulldownPercent", 25) + "%";

			case "QSLabelScaleFactor":
				float QSLabelScaleFactor = instance.mPreferences.getSliderFloat( "QSLabelScaleFactor", 0f);
				return (QSLabelScaleFactor + 100) + "%";

			case "QSSecondaryLabelScaleFactor":
				float QSSecondaryLabelScaleFactor = instance.mPreferences.getSliderFloat( "QSSecondaryLabelScaleFactor", 0f);
				return (QSSecondaryLabelScaleFactor + 100) + "%";

			case "GesPillWidthModPos":
				return instance.mPreferences.getSliderInt("GesPillWidthModPos", 50) * 2 + fragmentCompat.getString(R.string.pill_width_summary);

			case "GesPillHeightFactor":
				return instance.mPreferences.getSliderInt("GesPillHeightFactor", 100) + fragmentCompat.getString(R.string.pill_width_summary);

			case "BackLeftHeight":
				return instance.mPreferences.getSliderInt("BackLeftHeight", 100) + "%";

			case "BackRightHeight":
				return instance.mPreferences.getSliderInt("BackRightHeight", 100) + "%";

			case "leftSwipeUpPercentage":
				float leftSwipeUpPercentage = instance.mPreferences.getSliderFloat( "leftSwipeUpPercentage", 25);
				return leftSwipeUpPercentage + "%";

			case "rightSwipeUpPercentage":
				float rightSwipeUpPercentage = instance.mPreferences.getSliderFloat( "rightSwipeUpPercentage", 25);
				return rightSwipeUpPercentage + "%";

			case "swipeUpPercentage":
				return instance.mPreferences.getSliderFloat("swipeUpPercentage", 20f) + "%";

			case "appLanguage":
				boolean default_language_selected = instance.mPreferences.getString("appLanguage", null) != null;
				String[] languages_names = fragmentCompat.getResources().getStringArray(R.array.languages_names);
				String[] languages_values = fragmentCompat.getResources().getStringArray(R.array.languages_values);

				int current_language_code_index = Arrays.asList(languages_values).indexOf(instance.mPreferences.getString("appLanguage", fragmentCompat.getResources().getConfiguration().getLocales().get(0).getLanguage()));
				int selected_language_code_index = current_language_code_index < 0 ? Arrays.asList(languages_values).indexOf("en") : current_language_code_index;

				if (!default_language_selected) {
					instance.mPreferences.edit().putString("appLanguage", languages_values[selected_language_code_index]).apply();
				}

				return Arrays.asList(languages_names).get(selected_language_code_index);

			case "CheckForUpdate":
				return fragmentCompat.getString(R.string.current_version, BuildConfig.VERSION_NAME);

			case "FlatStandbyTime":
				return String.format(fragmentCompat.getString(R.string.duration_seconds), instance.mPreferences.getSliderInt("FlatStandbyTime", 5));
		}
		return null;
	}

	public static void setupPreference(Preference preference) {
		try {
			String key = preference.getKey();

			preference.setVisible(isVisible(key));
			preference.setEnabled(isEnabled(key));

			String summary = getSummary(preference.getContext(), key);
			if (summary != null) {
				preference.setSummary(summary);
			}

			//Other special cases
			switch (key) {
				case "QSLabelScaleFactor":
				case "QSSecondaryLabelScaleFactor":
					((RangeSliderPreference) preference).setLabelFormatter(value -> (value + 100) + "%");
					break;
				case "FastChargingWattage":
					((RangeSliderPreference) preference).setLabelFormatter(new LabelFormatter() {
						@NonNull
						@Override
						public String getFormattedValue(float value) {
							return labelFastChargingWattage(preference.getContext(), Math.round(value));
						}
					});
					break;

				case "DWOpacity":
					((RangeSliderPreference) preference).setLabelFormatter(new LabelFormatter() {
						@NonNull
						@Override
						public String getFormattedValue(float value) {
							NumberFormat format = NumberFormat.getInstance();
							format.setMaximumFractionDigits(1);
							return String.format("%s%%", format.format(value*100/255));
						}
					});
					break;
				default:
					break;
			}
		} catch (Throwable ignored) {
		}
	}

	public static String labelFastChargingWattage(Context context, int value)
	{
		return value <= 5
				? context.getString(R.string.word_default)
				: String.format("%s %s", value, context.getString(R.string.watts));
	}

	public static void setupAllPreferences(PreferenceGroup group) {
		for (int i = 0; ; i++) {
			try {
				Preference thisPreference = group.getPreference(i);

				if (thisPreference instanceof MaterialPrimarySwitchPreference switchPreference) {
                    switchPreference.setChecked(instance.mPreferences.getBoolean(switchPreference.getKey(), false));
				} else if (thisPreference instanceof PreferenceGroup) {
					setupAllPreferences((PreferenceGroup) thisPreference);
				}
				else
				{
					PreferenceHelper.setupPreference(thisPreference);
				}
			} catch (Throwable ignored) {
				break;
			}
		}
	}

	public static void setupMainSwitches(PreferenceGroup group) {
		for (int i = 0; ; i++) {
			try {
				Preference thisPreference = group.getPreference(i);

				PreferenceHelper.setupPreference(thisPreference);

				if (thisPreference instanceof PreferenceGroup) {
					setupAllPreferences((PreferenceGroup) thisPreference);
				}
			} catch (Throwable ignored) {
				break;
			}
		}
	}

}