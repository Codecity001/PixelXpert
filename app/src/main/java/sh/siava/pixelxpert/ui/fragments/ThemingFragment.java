package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class ThemingFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.theme_customization_category);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.theming_prefs;
	}
}
