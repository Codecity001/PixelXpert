package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class NetworkStatFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.netstat_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.lsqs_custom_text;
	}
}
