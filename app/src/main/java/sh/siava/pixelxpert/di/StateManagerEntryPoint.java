package sh.siava.pixelxpert.di;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import sh.siava.pixelxpert.ui.misc.StateManager;

@EntryPoint
@InstallIn(SingletonComponent.class)
public interface StateManagerEntryPoint {
    StateManager getStateManager();
}
