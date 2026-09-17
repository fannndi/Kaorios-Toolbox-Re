package io.farewell.toolbox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.farewell.toolbox.BuildConfig
import io.farewell.toolbox.core.AutoRefresh
import io.farewell.toolbox.core.DataSync
import io.farewell.toolbox.core.DeviceProfileInfo
import io.farewell.toolbox.core.IntegrityCheck
import io.farewell.toolbox.core.NativeService
import io.farewell.toolbox.core.PatchRepository
import io.farewell.toolbox.core.PlayIntegrityFlags
import io.farewell.toolbox.core.PlayIntegritySetup
import io.farewell.patcher.SpoofRules
import io.farewell.toolbox.core.RootShell
import io.farewell.toolbox.core.SpoofRulesStore
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
    val dataMessage: String = "",
    val keyboxImported: Boolean = false,
    val keyboxCount: Int = 0,
    val autoRefresh: Boolean = false,
    val autoRefreshLast: String = "",
    val integrationMessage: String = "",
    val nativeStatus: String = "",
    val rules: SpoofRules = SpoofRules(),
    val rulesMessage: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PatchRepository(application)

    val device: DeviceProfileInfo = repository.device

    private val _state = MutableStateFlow(
        PatchUiState(
            dataVersion = DataSync.cachedVersion(application),
            keyboxImported = PlayIntegritySetup.keyboxImported(application),
            keyboxCount = PlayIntegritySetup.keyboxFiles(application).size,
            autoRefresh = AutoRefresh.isEnabled(application),
            autoRefreshLast = AutoRefresh.lastResult(application),
            rules = SpoofRulesStore.load(application)
        )
    )
    val state: StateFlow<PatchUiState> = _state

    /**
     * Every per-app rule edit is persisted immediately and then needs an
     * "Apply Play Integrity setup" run to reach the hook config in
     * `Settings.Global`.
     */
    fun updateRules(transform: (SpoofRules) -> SpoofRules) {
        val updated = transform(_state.value.rules)
        val saved = SpoofRulesStore.save(getApplication(), updated)
        _state.update {
            it.copy(
                rules = updated,
                rulesMessage = if (saved) {
                    "Saved. Press \"Apply Play Integrity setup\" to write it to the device."
                } else {
                    "Could not save rules"
                }
            )
        }
    }

    fun clearRules() {
        val saved = SpoofRulesStore.save(getApplication(), SpoofRules())
        _state.update {
            it.copy(
                rules = SpoofRules(),
                rulesMessage = if (saved) "All per-app rules cleared" else "Could not save rules"
            )
        }
    }

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Checking root and framework...") }
            val status = repository.detectStatus()
            val native = NativeService.statusSummary(getApplication())
            _state.update {
                it.copy(
                    root = status.root,
                    installed = status.installed,
                    installedVersion = status.installedVersion,
                    statusMessage = status.message,
                    nativeStatus = native,
                    busy = false,
                    progress = ""
                )
            }
        }
    }

    fun applyNativeProps() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Applying native properties via root...") }
            val message = NativeService.applyStock(getApplication())
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    nativeStatus = NativeService.statusSummary(getApplication()),
                    integrationMessage = message,
                    log = it.log + "Native props: $message"
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

    fun exportStockZip() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Building restore zip...") }
            try {
                val zip = repository.buildStockZip { message ->
                    _state.update { current ->
                        current.copy(progress = message, log = current.log + message)
                    }
                }
                repository.exportToDownloads(zip, zip.name)
                val stored = repository.storedStockCount()
                _state.update {
                    it.copy(
                        busy = false,
                        progress = "",
                        lastBackup = zip,
                        log = it.log +
                            "Restore zip exported: ${zip.name} (flash this if the patch does not boot)." +
                            " $stored original file(s) saved on the phone for future patch builds."
                    )
                }
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(busy = false, progress = "", log = it.log + "Restore build failed: ${throwable.message}")
                }
            }
        }
    }

    fun exportPrivilegedZip() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, progress = "Building system-app zip...") }
            try {
                val zip = repository.buildPrivilegedZip()
                repository.exportToDownloads(zip, zip.name)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = "",
                        log = it.log +
                            "System-app zip exported: ${zip.name}. Flash it once (TWRP): the app becomes privileged, " +
                            "so Reboot to recovery and Apply setup work with no root."
                    )
                }
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(busy = false, progress = "", log = it.log + "System-app zip failed: ${throwable.message}")
                }
            }
        }
    }

    fun exportSeedZip() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, progress = "Building seed zip...") }
            try {
                val seed = repository.buildSeedZip()
                repository.exportToDownloads(seed, seed.name)
                _state.update {
                    it.copy(
                        busy = false,
                        progress = "",
                        log = it.log + "Seed zip exported to Downloads/Farewell/${seed.name}"
                    )
                }
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(busy = false, progress = "", log = it.log + "Seed export failed: ${throwable.message}")
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

    fun applyPlayIntegrity() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Applying Play Integrity setup...") }
            val result = PlayIntegritySetup.apply(getApplication(), PlayIntegrityFlags())
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    integrationMessage = result.message,
                    rulesMessage = if (result.ok) {
                        "Rules written to the device. Restart the target app to pick them up."
                    } else {
                        it.rulesMessage
                    },
                    log = it.log + result.message
                )
            }
        }
    }

    fun refreshPlayIntegrity() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Refreshing Play Integrity...") }
            val result = PlayIntegritySetup.refresh(getApplication()) { message ->
                _state.update { it.copy(progress = message) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    integrationMessage = result.message,
                    dataVersion = DataSync.cachedVersion(getApplication()),
                    log = it.log + result.message
                )
            }
        }
    }

    fun pickHealthiestKeybox() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Validating keyboxes against Google lists...") }
            val report = PlayIntegritySetup.validateAndPickHealthiest(getApplication()) { message ->
                _state.update { it.copy(progress = message.take(80)) }
            }
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    keyboxImported = PlayIntegritySetup.keyboxImported(getApplication()),
                    keyboxCount = PlayIntegritySetup.keyboxFiles(getApplication()).size,
                    integrationMessage = report,
                    log = it.log + report
                )
            }
        }
    }

    fun useNextKeybox() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = PlayIntegritySetup.keyboxFiles(getApplication())
            val message = if (files.isEmpty()) {
                "No keybox imported"
            } else {
                val next = (PlayIntegritySetup.activeKeyboxIndex(getApplication()) + 1) % files.size
                if (PlayIntegritySetup.setActiveKeybox(getApplication(), next)) {
                    "Active keybox #${next + 1} (${files[next].name})"
                } else {
                    "Keybox switch failed"
                }
            }
            _state.update { it.copy(integrationMessage = message, log = it.log + message) }
        }
    }

    fun setAutoRefresh(enabled: Boolean) {
        AutoRefresh.setEnabled(getApplication(), enabled)
        _state.update { it.copy(autoRefresh = enabled, autoRefreshLast = AutoRefresh.lastResult(getApplication())) }
    }

    fun runAutoRefreshNow() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Running auto-refresh now...") }
            val message = AutoRefresh.runNow(getApplication())
            AutoRefresh.storeResult(getApplication(), message)
            _state.update { it.copy(busy = false, progress = "", autoRefreshLast = message, log = it.log + message) }
        }
    }

    fun analyzeVerdict(basic: Boolean, device: Boolean, strong: Boolean) {
        viewModelScope.launch {
            val message = IntegrityCheck.compareVerdict(getApplication(), basic, device, strong)
            _state.update { it.copy(integrationMessage = message, log = it.log + message) }
        }
    }

    fun analyzeVerdictJson(text: String) {
        viewModelScope.launch {
            val message = IntegrityCheck.decodeVerdictJson(text)
            _state.update { it.copy(integrationMessage = message, log = it.log + message) }
        }
    }

    fun verifyKeybox() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Checking keybox against Google lists...") }
            val report = IntegrityCheck.verifyImportedKeybox(getApplication())
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    integrationMessage = report,
                    log = it.log + report
                )
            }
        }
    }

    fun checkReadiness() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = "Running STRONG readiness check...") }
            val report = IntegrityCheck.readinessReport(getApplication())
            _state.update {
                it.copy(
                    busy = false,
                    progress = "",
                    integrationMessage = report,
                    log = it.log + report
                )
            }
        }
    }

    fun exportPropOverlay() {
        viewModelScope.launch(Dispatchers.IO) {
            val content = PlayIntegritySetup.buildPropOverlay(getApplication())
            if (content == null) {
                _state.update { it.copy(integrationMessage = "Pif-props.json not synced yet") }
                return@launch
            }
            val message = try {
                val file = File(getApplication<android.app.Application>().cacheDir, "pif_overlay.prop")
                file.writeText(content)
                repository.exportToDownloads(file, "pif_overlay.prop", "text/plain")
                "Exported pif_overlay.prop to Downloads/Farewell"
            } catch (throwable: Throwable) {
                "Overlay export failed: ${throwable.message}"
            }
            _state.update { it.copy(integrationMessage = message, log = it.log + message) }
        }
    }

    fun importKeybox(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val xml = getApplication<android.app.Application>().contentResolver
                    .openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (xml != null && PlayIntegritySetup.importKeybox(getApplication<android.app.Application>(), xml)) {
                    "Keybox imported"
                } else {
                    "Invalid keybox XML (needs PrivateKey + Certificate)"
                }
            } catch (throwable: Throwable) {
                "Keybox import failed: ${throwable.message}"
            }
            val imported = PlayIntegritySetup.keyboxImported(getApplication())
            val count = PlayIntegritySetup.keyboxFiles(getApplication()).size
            _state.update {
                it.copy(
                    keyboxImported = imported,
                    keyboxCount = count,
                    integrationMessage = message,
                    log = it.log + message
                )
            }
        }
    }

    fun reboot() {
        rebootTo(null)
    }

    fun rebootToRecovery() {
        rebootTo("recovery")
    }

    /**
     * Reboots without root when this build was flashed as a privileged app
     * (`REBOOT` from the privapp allowlist), and falls back to `su`, then to
     * guidance. `target` null = normal reboot, "recovery" = recovery.
     */
    private fun rebootTo(target: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<android.app.Application>()
            val permitted = app.checkSelfPermission(android.Manifest.permission.REBOOT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (permitted) {
                try {
                    val power = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                    if (target != null) {
                        power.reboot(target)
                    } else {
                        power.reboot(null)
                    }
                    return@launch
                } catch (throwable: Throwable) {
                    _state.update { it.copy(log = it.log + "PowerManager reboot failed: ${throwable.message}") }
                }
            }
            val result = if (target != null) RootShell.run("reboot $target") else RootShell.run("reboot")
            if (result.code != 0) {
                val hint = if (target != null) {
                    "No REBOOT permission and no root. From a PC: adb reboot recovery"
                } else {
                    "No REBOOT permission and no root. From a PC: adb reboot"
                }
                _state.update { it.copy(log = it.log + hint) }
            }
        }
    }
}
