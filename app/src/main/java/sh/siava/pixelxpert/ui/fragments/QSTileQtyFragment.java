package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class QSTileQtyFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.qs_tile_qty_title);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.qs_tile_qty_prefs;
	}
}
