package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class SBBIconFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.sbbIcon_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.statusbar_batteryicon_prefs;
	}
}
