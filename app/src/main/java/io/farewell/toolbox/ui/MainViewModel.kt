package io.farewell.toolbox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.farewell.toolbox.BuildConfig
import io.farewell.toolbox.core.DataSync
import io.farewell.toolbox.core.DeviceProfileInfo
import io.farewell.toolbox.core.PatchRepository
import io.farewell.toolbox.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PatchUiState(
    val root: Boolean? = null,
    val installed: Boolean = false,
    val installedVersion: String? = null,
    val statusMessage: String = "",
    val busy: Boolean = false,
    val progress: String = "",
    val log: List<String> = emptyList(),
    val lastZip: File? = null,
    val lastBackup: File? = null,
    val dataVersion: String? = null,
    val dataMessage: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PatchRepository(application)

    val device: DeviceProfileInfo = repository.device

    private val _state = MutableStateFlow(
        PatchUiState(dataVersion = DataSync.cachedVersion(application))
    )
    val state: StateFlow<PatchUiState> = _state

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Checking root and framework...") }
            val status = repository.detectStatus()
            _state.update {
                it.copy(
                    root = status.root,
                    installed = status.installed,
                    installedVersion = status.installedVersion,
                    statusMessage = status.message,
                    busy = false,
                    progress = ""
                )
            }
        }
    }

    fun buildPatch() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, log = emptyList(), progress = "Starting...") }
            try {
                val result = repository.buildPatch { message ->
                    _state.update { current ->
                        current.copy(progress = message, log = current.log + message)
                    }
                }
                _state.update {
                    it.copy(
                        busy = false,
                        progress = "",
                        lastZip = result.zip,
                        lastBackup = result.backupZip,
                        log = it.log + "Patch ZIP ready: ${result.zip.name}"
                    )
                }
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        progress = "",
                        log = it.log + "Error: ${throwable.message}"
                    )
                }
            }
        }
    }

    fun exportZip(file: File, displayName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.exportToDownloads(file, displayName)
                _state.update { it.copy(log = it.log + "Exported to Downloads/Farewell/$displayName") }
            } catch (throwable: Throwable) {
                _state.update { it.copy(log = it.log + "Export failed: ${throwable.message}") }
            }
        }
    }

    fun syncData() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Downloading Toolbox data...") }
            val result = DataSync.sync(getApplication(), BuildConfig.DATA_BASE_URL)
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    dataVersion = result.version,
                    dataMessage = result.message
                )
            }
        }
    }

    fun reboot() {
        viewModelScope.launch(Dispatchers.IO) {
            RootShell.run("reboot")
        }
    }
}
