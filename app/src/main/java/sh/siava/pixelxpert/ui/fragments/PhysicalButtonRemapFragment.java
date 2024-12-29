package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class PhysicalButtonRemapFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.remap_physical_buttons_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.physical_buttons_prefs;
	}
}
