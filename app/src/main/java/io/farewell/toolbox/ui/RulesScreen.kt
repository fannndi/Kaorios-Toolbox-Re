package io.farewell.toolbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.farewell.patcher.SpoofRules

/**
 * Editor for the per-app spoof rules the boot-classpath hook consumes.
 *
 * Edits are persisted immediately; "Apply Play Integrity setup" writes the whole
 * config into `Settings.Global` where the hook picks it up. The hook side has
 * always read these four sections — this screen is the missing writer.
 */
@Composable
fun RulesScreen(state: PatchUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Per-app spoof rules",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Rules are stored on this device and written into the framework config. " +
                        "They only change what a target app is told — nothing is written to the real setting.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "${state.rules.ruleCount} rule(s) configured",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                if (state.rulesMessage.isNotEmpty()) {
                    Text(state.rulesMessage, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { viewModel.applyPlayIntegrity() },
                    enabled = !state.busy,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.RULES_APPLY)
                ) {
                    Text("Apply Play Integrity setup")
                }
                OutlinedButton(
                    onClick = { viewModel.clearRules() },
                    enabled = !state.busy && !state.rules.isEmpty,
                    modifier = Modifier.padding(top = 8.dp).testTag(UiTags.RULES_CLEAR)
                ) {
                    Text("Clear all rules")
                }
            }
        }

        InstallerCard(state, viewModel)
        SettingCard(state, viewModel)
        RemoveCard(state, viewModel)
        FeatureCard(state, viewModel)
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun RuleRow(primary: String, secondary: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(primary, style = MaterialTheme.typography.bodyMedium)
            if (secondary.isNotEmpty()) {
                Text(secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

/** Dropdown over the three real Settings table names. */
@Composable
private fun NamespacePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (namespace in SpoofRules.NAMESPACES) {
                DropdownMenuItem(
                    text = { Text(namespace) },
                    onClick = {
                        onSelect(namespace)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun InstallerCard(state: PatchUiState, viewModel: MainViewModel) {
    SectionCard(
        title = "Installer source spoof",
        description = "When the app on the left asks for the installer of any package, report the value " +
            "on the right instead (e.g. com.android.vending). The key is the app doing the asking."
    ) {
        for ((pkg, installer) in state.rules.installer) {
            RuleRow(primary = pkg, secondary = "sees installer: $installer") {
                viewModel.updateRules { it.copy(installer = it.installer - pkg) }
            }
        }

        var pkg by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        val valid = SpoofRules.isPackageName(pkg) && SpoofRules.isPackageName(target)
        OutlinedTextField(
            value = pkg,
            onValueChange = { pkg = it },
            label = { Text("App doing the asking") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("Installer to report") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val key = pkg.trim()
                val value = target.trim()
                viewModel.updateRules { it.copy(installer = it.installer + (key to value)) }
                pkg = ""
                target = ""
            },
            enabled = valid,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Add installer rule")
        }
    }
}

@Composable
private fun SettingCard(state: PatchUiState, viewModel: MainViewModel) {
    SectionCard(
        title = "Setting overrides",
        description = "Per app, per table: change the value an app reads for one Settings key. " +
            "The real setting is never modified."
    ) {
        for ((pkg, tables) in state.rules.settings) {
            for ((namespace, entries) in tables) {
                for ((name, value) in entries) {
                    RuleRow(
                        primary = "$namespace/$name",
                        secondary = "$pkg = \"$value\""
                    ) {
                        viewModel.updateRules { rules ->
                            val updatedTables = tables.toMutableMap()
                            updatedTables[namespace] = entries - name
                            val updatedSettings = rules.settings.toMutableMap()
                            if (updatedTables.values.all { it.isEmpty() }) {
                                updatedSettings.remove(pkg)
                            } else {
                                updatedSettings[pkg] = updatedTables.filterValues { it.isNotEmpty() }
                            }
                            rules.copy(settings = updatedSettings)
                        }
                    }
                }
            }
        }

        var pkg by remember { mutableStateOf("") }
        var namespace by remember { mutableStateOf(SpoofRules.NAMESPACES.first()) }
        var name by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        val valid = SpoofRules.isPackageName(pkg) && name.isNotBlank()
        OutlinedTextField(
            value = pkg,
            onValueChange = { pkg = it },
            label = { Text("Package name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Table", style = MaterialTheme.typography.bodySmall)
            NamespacePicker(selected = namespace, onSelect = { namespace = it })
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Setting key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Value to return") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val app = pkg.trim()
                val key = name.trim()
                viewModel.updateRules { rules ->
                    val tables = rules.settings[app].orEmpty().toMutableMap()
                    tables[namespace] = tables[namespace].orEmpty() + (key to value)
                    rules.copy(settings = rules.settings + (app to tables))
                }
                pkg = ""
                name = ""
                value = ""
            },
            enabled = valid,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Add setting override")
        }
    }
}

@Composable
private fun RemoveCard(state: PatchUiState, viewModel: MainViewModel) {
    SectionCard(
        title = "Hidden settings",
        description = "Keys that read as absent for every caller, in the chosen table. " +
            "Use this for values the ROM would otherwise expose."
    ) {
        for ((namespace, names) in state.rules.remove) {
            for (name in names) {
                RuleRow(primary = "$namespace/$name", secondary = "reads as missing") {
                    viewModel.updateRules { rules ->
                        val remaining = names - name
                        rules.copy(
                            remove = if (remaining.isEmpty()) {
                                rules.remove - namespace
                            } else {
                                rules.remove + (namespace to remaining)
                            }
                        )
                    }
                }
            }
        }

        var namespace by remember { mutableStateOf(SpoofRules.NAMESPACES.first()) }
        var name by remember { mutableStateOf("") }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Table", style = MaterialTheme.typography.bodySmall)
            NamespacePicker(selected = namespace, onSelect = { namespace = it })
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Setting key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val key = name.trim()
                viewModel.updateRules { rules ->
                    val existing = rules.remove[namespace].orEmpty()
                    rules.copy(remove = rules.remove + (namespace to (existing + key).distinct()))
                }
                name = ""
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Hide setting")
        }
    }
}

@Composable
private fun FeatureCard(state: PatchUiState, viewModel: MainViewModel) {
    SectionCard(
        title = "System features",
        description = "Force the answer of PackageManager.hasSystemFeature for a feature string, " +
            "e.g. android.hardware.keystore."
    ) {
        for ((feature, enabled) in state.rules.features) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(
                    checked = enabled,
                    onCheckedChange = { next ->
                        viewModel.updateRules { it.copy(features = it.features + (feature to next)) }
                    }
                )
                Text(
                    feature,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    viewModel.updateRules { it.copy(features = it.features - feature) }
                }) {
                    Text("Remove")
                }
            }
        }

        var feature by remember { mutableStateOf("") }
        var value by remember { mutableStateOf(true) }
        OutlinedTextField(
            value = feature,
            onValueChange = { feature = it },
            label = { Text("Feature string") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = value, onCheckedChange = { value = it })
            Text(
                if (value) "Report as supported" else "Report as unsupported",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            onClick = {
                val key = feature.trim()
                viewModel.updateRules { it.copy(features = it.features + (key to value)) }
                feature = ""
            },
            enabled = feature.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Add feature rule")
        }
    }
}
