package sh.siava.pixelxpert.ui.misc;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import javax.inject.Singleton;

@Singleton
public class StateManager {

    private final MutableLiveData<Boolean> requiresSystemUIRestart = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> requiresDeviceRestart = new MutableLiveData<>(false);

    public LiveData<Boolean> getRequiresSystemUIRestart() {
        return requiresSystemUIRestart;
    }

    public void setRequiresSystemUIRestart(boolean value) {
        requiresSystemUIRestart.postValue(value);
    }

    public LiveData<Boolean> getRequiresDeviceRestart() {
        return requiresDeviceRestart;
    }

    public void setRequiresDeviceRestart(boolean value) {
        requiresDeviceRestart.postValue(value);
    }
}
