package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class NetworkFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.ntsb_category_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.sbqs_network_prefs;
	}
}
