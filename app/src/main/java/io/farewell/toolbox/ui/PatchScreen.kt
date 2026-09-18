package io.farewell.toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PatchScreen(state: PatchUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.installed) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (state.installed) Color(0xFF16A34A) else Color(0xFFDC2626)
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            if (state.installed) "Farewell Patch: ON" else "Farewell Patch: OFF",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(UiTags.PATCH_STATUS)
                    )
                    state.installedVersion?.let {
                        Text("Hook version: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "Root: ${if (state.root == true) "granted" else if (state.root == false) "unavailable (optional)" else "checking..."}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Device: ${viewModel.device.model} (${viewModel.device.device}) - ${viewModel.device.miuiLabel} / Android ${viewModel.device.androidApi}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Profile: ${viewModel.device.profile.id}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!viewModel.device.supportedDevice) {
                        Text(
                            "Unsupported device. Farewell patch is limited to surya (M2007J20CG / M2007J20CT).",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFDC2626)
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.refreshStatus() },
                    enabled = !state.busy,
                    modifier = Modifier.testTag(UiTags.PATCH_REFRESH)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh status")
                    Spacer(Modifier.padding(2.dp))
                    Text("Refresh")
                }
                Button(
                    onClick = { viewModel.buildPatch() },
                    enabled = !state.busy && viewModel.device.supportedDevice,
                    modifier = Modifier.testTag(UiTags.PATCH_BUILD)
                ) {
                    Text("Build Patch ZIP")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("No root? Use the seed", fontWeight = FontWeight.Bold)
                    Text(
                        "1. Export the restore zip below FIRST - it backs up the originals on the phone, so every " +
                            "later patch (even after an app update) patches the originals, never the flashed files. " +
                            "2. Flash the seed zip once in TWRP so the prop files are readable without root.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.exportStockZip() },
                        enabled = !state.busy && viewModel.device.supportedDevice,
                        modifier = Modifier.testTag(UiTags.PATCH_EXPORT_RESTORE)
                    ) {
                        Text("1. Backup original (export restore zip)")
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.exportSeedZip() },
                        enabled = !state.busy && viewModel.device.supportedDevice,
                        modifier = Modifier.testTag(UiTags.PATCH_EXPORT_SEED)
                    ) {
                        Text("2. Export seed zip (TWRP, once)")
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { viewModel.exportPrivilegedZip() },
                        enabled = !state.busy && viewModel.device.supportedDevice,
                        modifier = Modifier.testTag(UiTags.PATCH_EXPORT_SYSAPP)
                    ) {
                        Text("3. Export system-app zip (TWRP, once)")
                    }
                    Text(
                        "Optional but recommended: flash the system-app zip once and the app gains " +
                            "reboot-to-recovery and rootless config writes. Keep the restore zip off the phone too. " +
                            "The patch flash additionally writes its own backup to /data/media/0/Farewell/backup-<stamp>/ with a restore.sh.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (state.busy) {
            item {
                Column {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.progress,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(UiTags.PATCH_PROGRESS)
                    )
                }
            }
        }

        state.lastZip?.let { zip ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Patch package", fontWeight = FontWeight.Bold)
                        Text(zip.name, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.exportZip(zip, zip.name) },
                            modifier = Modifier.testTag(UiTags.PATCH_EXPORT_ZIP)
                        ) {
                            Text("Export to Downloads")
                        }
                    }
                }
            }
        }

        state.lastBackup?.let { backup ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Stock backup (unpatch)", fontWeight = FontWeight.Bold)
                        Text(backup.name, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.exportZip(backup, backup.name) },
                            modifier = Modifier.testTag(UiTags.PATCH_EXPORT_BACKUP)
                        ) {
                            Text("Export to Downloads")
                        }
                    }
                }
            }
        }

        if (state.log.isNotEmpty()) {
            item {
                Text("Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(state.log) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.reboot() },
                    modifier = Modifier.testTag(UiTags.PATCH_REBOOT)
                ) {
                    Text("Reboot")
                }
                OutlinedButton(
                    onClick = { viewModel.rebootToRecovery() },
                    modifier = Modifier.testTag(UiTags.PATCH_REBOOT_RECOVERY)
                ) {
                    Text("Reboot to recovery")
                }
            }
        }
    }
}
