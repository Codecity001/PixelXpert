package sh.siava.pixelxpert.di;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import sh.siava.pixelxpert.ui.misc.StateManager;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    public StateManager provideStateManager() {
        return new StateManager();
    }
}
