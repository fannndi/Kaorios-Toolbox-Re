package io.farewell.toolbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.farewell.toolbox.ui.AppRoot
import io.farewell.toolbox.ui.theme.FarewellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FarewellTheme {
                AppRoot()
            }
        }
    }
}
