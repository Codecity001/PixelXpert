package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class TaskbarNavFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.taskbar_header_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.taskbar_prefs;
	}
}
