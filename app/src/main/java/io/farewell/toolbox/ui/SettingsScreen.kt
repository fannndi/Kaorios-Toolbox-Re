package io.farewell.toolbox.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.farewell.toolbox.BuildConfig

@Composable
fun SettingsScreen(state: PatchUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val keyboxPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importKeybox)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Play Integrity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Writes the Farewell config for Google Play services and Play Store: PIF Build fields, " +
                        "system properties, hidden app list, hidden developer status and keybox attestation.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Keybox: ${if (state.keyboxImported) "imported" else "not imported"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = { viewModel.applyPlayIntegrity() },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Apply Play Integrity setup")
                }
                OutlinedButton(
                    onClick = { keyboxPicker.launch("*/*") },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(if (state.keyboxImported) "Replace keybox XML" else "Import keybox XML")
                }
                if (state.integrationMessage.isNotEmpty()) {
                    Text(state.integrationMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Farewell Toolbox patcher", style = MaterialTheme.typography.bodyMedium)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Profile: ${viewModel.device.profile.id} (${viewModel.device.profile.label})",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Workflow", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("1. Build Patch ZIP (root required), export to Downloads/Farewell.", style = MaterialTheme.typography.bodySmall)
                Text("2. Flash the zip in recovery, then reboot.", style = MaterialTheme.typography.bodySmall)
                Text("3. Sync data, import keybox, then Apply Play Integrity setup.", style = MaterialTheme.typography.bodySmall)
                Text("4. Clear Play Store data and re-open it to refresh certification.", style = MaterialTheme.typography.bodySmall)
                Text("5. Use the stock backup zip to revert.", style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedButton(onClick = { viewModel.refreshStatus() }, enabled = !state.busy) {
            Text("Re-check patch status")
        }

        OutlinedButton(onClick = { viewModel.reboot() }) {
            Text("Reboot device")
        }
    }
}
