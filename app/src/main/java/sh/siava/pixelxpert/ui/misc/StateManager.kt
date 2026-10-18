package sh.siava.pixelxpert.ui.misc

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StateManager {

    private val _requiresSystemUIRestart = MutableLiveData(false)
    val requiresSystemUIRestart: LiveData<Boolean> = _requiresSystemUIRestart

    private val _requiresDeviceRestart = MutableLiveData(false)
    val requiresDeviceRestart: LiveData<Boolean> = _requiresDeviceRestart

    private val _systemUIRestartFlow = MutableStateFlow(false)
    val systemUIRestartFlow: StateFlow<Boolean> = _systemUIRestartFlow.asStateFlow()

    private val _deviceRestartFlow = MutableStateFlow(false)
    val deviceRestartFlow: StateFlow<Boolean> = _deviceRestartFlow.asStateFlow()

    fun setRequiresSystemUIRestart(value: Boolean) {
        _requiresSystemUIRestart.postValue(value)
        _systemUIRestartFlow.value = value
    }

    fun setRequiresDeviceRestart(value: Boolean) {
        _requiresDeviceRestart.postValue(value)
        _deviceRestartFlow.value = value
    }
}
