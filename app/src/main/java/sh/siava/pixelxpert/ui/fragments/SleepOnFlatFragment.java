package sh.siava.pixelxpert.ui.fragments;

import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.service.tileServices.SleepOnSurfaceTileService;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;

public class SleepOnFlatFragment extends ControlledPreferenceFragmentCompat {

    @Override
    public String getTitle() {
        return getString(R.string.sleep_on_flat_screen_tile_title);
    }

    @Override
    public int getLayoutResource() {
        return R.xml.sleep_on_flat_prefs;
    }

    @Override
    public void updateScreen(String key)
    {
        if(key != null && getActivity() != null) {
            getActivity().recreate();
            return;
        }
        super.updateScreen(key);
        SleepOnSurfaceTileService.onPrefsChanged();
    }
}
