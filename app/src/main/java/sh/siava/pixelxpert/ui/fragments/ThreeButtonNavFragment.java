package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class ThreeButtonNavFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.threebutton_header_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.three_button_prefs;
	}
}
