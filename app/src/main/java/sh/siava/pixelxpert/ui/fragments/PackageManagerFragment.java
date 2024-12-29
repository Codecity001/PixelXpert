package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class PackageManagerFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.pm_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.packagemanger_prefs;
	}
}
