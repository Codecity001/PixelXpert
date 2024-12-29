package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class SBBBFragment extends ControlledPreferenceFragmentCompat {

	@Override
	public String getTitle() {
		return getString(R.string.sbbb_header_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.statusbar_batterybar_prefs;
	}
}
