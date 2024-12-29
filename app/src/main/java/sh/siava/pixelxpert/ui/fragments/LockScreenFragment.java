package sh.siava.pixelxpert.ui.fragments;

import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.AppUtils;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;
import sh.siava.pixelxpert.utils.MLKitSegmentor;
import sh.siava.pixelxpert.utils.PyTorchSegmentor;

public class LockScreenFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.lockscreen_header_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.lock_screen_prefs;
	}

	@Override
	public void updateScreen(String key) {
		super.updateScreen(key);

		if (key == null) {
			updateModelAvailabilitySummary();
			return;
		}

		if (key.equals("DWallpaperEnabled")) {
			try {
				boolean DepthEffectEnabled = mPreferences.getBoolean("DWallpaperEnabled", false);

				if (DepthEffectEnabled && getContext() != null) {
					new MaterialAlertDialogBuilder(getContext(), R.style.MaterialComponents_MaterialAlertDialog)
							.setTitle(R.string.depth_effect_alert_title)
							.setMessage(getString(R.string.depth_effect_alert_body, getString(R.string.sysui_restart_needed)))
							.setPositiveButton(R.string.depth_effect_ok_btn, (dialog, which) -> AppUtils.Restart("systemui"))
							.setCancelable(false)
							.show();
				}
			} catch (Exception ignored) {
			}
		} else if (key.equals("SegmentorAI")) {
			updateModelAvailabilitySummary();
		}
	}

	private void updateModelAvailabilitySummary() {
		try {
			boolean mlKitModel = Integer.parseInt(mPreferences.getString("SegmentorAI", "0")) == 0;

			if (mlKitModel) {
				new MLKitSegmentor(getActivity()).checkModelAvailability(moduleAvailabilityResponse ->
						findPreference("DWallpaperEnabled")
								.setSummary(moduleAvailabilityResponse.areModulesAvailable()
										? R.string.depth_wallpaper_model_ready
										: R.string.depth_wallpaper_model_not_available));
			} else {
				findPreference("DWallpaperEnabled")
						.setSummary(PyTorchSegmentor.loadAssets(getContext())
								? R.string.depth_wallpaper_model_ready
								: R.string.depth_wallpaper_model_not_available);
			}
		} catch (Exception exception) {
			Log.e(LockScreenFragment.class.getSimpleName(), exception.getMessage());
		}
	}
}
