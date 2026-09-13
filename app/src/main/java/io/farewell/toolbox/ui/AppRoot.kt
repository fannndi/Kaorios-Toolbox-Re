package io.farewell.toolbox.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class FarewellTab {
    PATCH,
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
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text("Patch") }
                )
                NavigationBarItem(
                    selected = tab == FarewellTab.DATA,
                    onClick = { tab = FarewellTab.DATA },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Data") }
                )
                NavigationBarItem(
                    selected = tab == FarewellTab.SETTINGS,
                    onClick = { tab = FarewellTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            FarewellTab.PATCH -> PatchScreen(state, viewModel, modifier)
            FarewellTab.DATA -> HomeScreen(state, viewModel, modifier)
            FarewellTab.SETTINGS -> SettingsScreen(state, viewModel, modifier)
        }
    }
}
