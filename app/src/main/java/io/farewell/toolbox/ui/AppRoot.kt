package io.farewell.toolbox.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class FarewellTab {
    PATCH,
    RULES,
    DATA,
    SETTINGS
}

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    var tab by remember { mutableStateOf(FarewellTab.PATCH) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == FarewellTab.PATCH,
                    onClick = { tab = FarewellTab.PATCH },
                    modifier = Modifier.testTag(UiTags.TAB_PATCH),
                    icon = { Icon(Icons.Default.Build, contentDescription = "Patch") },
                    label = { Text("Patch") }
                )
                NavigationBarItem(
                    selected = tab == FarewellTab.RULES,
                    onClick = { tab = FarewellTab.RULES },
                    modifier = Modifier.testTag(UiTags.TAB_RULES),
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Rules") },
                    label = { Text("Rules") }
                )
                NavigationBarItem(
                    selected = tab == FarewellTab.DATA,
                    onClick = { tab = FarewellTab.DATA },
                    modifier = Modifier.testTag(UiTags.TAB_DATA),
                    icon = { Icon(Icons.Default.Home, contentDescription = "Data") },
                    label = { Text("Data") }
                )
                NavigationBarItem(
                    selected = tab == FarewellTab.SETTINGS,
                    onClick = { tab = FarewellTab.SETTINGS },
                    modifier = Modifier.testTag(UiTags.TAB_SETTINGS),
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            FarewellTab.PATCH -> PatchScreen(state, viewModel, modifier.testTag(UiTags.SCREEN_PATCH))
            FarewellTab.RULES -> RulesScreen(state, viewModel, modifier.testTag(UiTags.SCREEN_RULES))
            FarewellTab.DATA -> HomeScreen(state, viewModel, modifier.testTag(UiTags.SCREEN_DATA))
            FarewellTab.SETTINGS -> SettingsScreen(state, viewModel, modifier.testTag(UiTags.SCREEN_SETTINGS))
        }
    }
}
