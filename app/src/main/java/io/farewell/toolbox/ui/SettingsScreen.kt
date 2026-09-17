package io.farewell.toolbox.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
                    "Keybox: ${if (state.keyboxImported) "active, ${state.keyboxCount} imported" else "not imported"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = { viewModel.applyPlayIntegrity() },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_APPLY)
                ) {
                    Text("Apply Play Integrity setup")
                }
                Button(
                    onClick = { viewModel.refreshPlayIntegrity() },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_REFRESH_PI)
                ) {
                    Text("Refresh + clear Play Store")
                }
                OutlinedButton(
                    onClick = { keyboxPicker.launch("*/*") },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_PICK_KEYBOX)
                ) {
                    Text(if (state.keyboxImported) "Replace keybox XML" else "Import keybox XML")
                }
                OutlinedButton(
                    onClick = { viewModel.exportPropOverlay() },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_EXPORT_PROPS)
                ) {
                    Text("Export ROM prop overlay")
                }
                OutlinedButton(
                    onClick = { viewModel.verifyKeybox() },
                    enabled = !state.busy && state.keyboxImported,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_VERIFY_KEYBOX)
                ) {
                    Text("Verify keybox (Google lists)")
                }
                OutlinedButton(
                    onClick = { viewModel.pickHealthiestKeybox() },
                    enabled = !state.busy && state.keyboxCount > 0,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_KEYBOX_HEALTHIEST)
                ) {
                    Text("Validate & pick healthiest keybox")
                }
                OutlinedButton(
                    onClick = { viewModel.useNextKeybox() },
                    enabled = !state.busy && state.keyboxCount > 1,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_KEYBOX_NEXT)
                ) {
                    Text("Use next keybox (soft-ban fallback)")
                }
                OutlinedButton(
                    onClick = { viewModel.checkReadiness() },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_CHECK_READINESS)
                ) {
                    Text("STRONG readiness check")
                }
                if (state.integrationMessage.isNotEmpty()) {
                    Text(state.integrationMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.autoRefresh, onCheckedChange = { viewModel.setAutoRefresh(it) }, modifier = Modifier.testTag(UiTags.SETTINGS_AUTO_REFRESH))
                    Text("Auto-refresh every 6h (PIF + keybox health)", style = MaterialTheme.typography.bodySmall)
                }
                if (state.autoRefreshLast.isNotEmpty()) {
                    Text("Last run: ${state.autoRefreshLast}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { viewModel.runAutoRefreshNow() },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_AUTO_REFRESH_NOW)
                ) {
                    Text("Run auto-refresh now")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Verdict compare", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Play Store > developer options > Play Integrity > Check integrity, then mark the labels you got.",
                    style = MaterialTheme.typography.bodySmall
                )
                var basic by remember { mutableStateOf(false) }
                var device by remember { mutableStateOf(false) }
                var strong by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = basic, onCheckedChange = { basic = it }, modifier = Modifier.testTag(UiTags.SETTINGS_VERDICT_BASIC))
                    Text("MEETS_BASIC_INTEGRITY", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = device, onCheckedChange = { device = it }, modifier = Modifier.testTag(UiTags.SETTINGS_VERDICT_DEVICE))
                    Text("MEETS_DEVICE_INTEGRITY", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = strong, onCheckedChange = { strong = it }, modifier = Modifier.testTag(UiTags.SETTINGS_VERDICT_STRONG))
                    Text("MEETS_STRONG_INTEGRITY", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { viewModel.analyzeVerdict(basic, device, strong) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_VERDICT_ANALYZE)
                ) {
                    Text("Analyze verdict")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Decode verdict JSON", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Decrypt your integrity token server-side (Play Console project + OAuth), then paste the tokenPayloadExternal JSON here.",
                    style = MaterialTheme.typography.bodySmall
                )
                var verdictJson by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = verdictJson,
                    onValueChange = { verdictJson = it },
                    label = { Text("Verdict JSON") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(UiTags.SETTINGS_VERDICT_JSON)
                )
                Button(
                    onClick = { viewModel.analyzeVerdictJson(verdictJson) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.SETTINGS_VERDICT_JSON_ANALYZE)
                ) {
                    Text("Decode verdict")
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

        OutlinedButton(onClick = { viewModel.refreshStatus() }, enabled = !state.busy, modifier = Modifier.testTag(UiTags.SETTINGS_REFRESH)) {
            Text("Re-check patch status")
        }

        OutlinedButton(onClick = { viewModel.reboot() }, modifier = Modifier.testTag(UiTags.SETTINGS_REBOOT)) {
            Text("Reboot device")
        }
    }
}
