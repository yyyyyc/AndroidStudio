package com.example.cameratextlogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        ScreenScanBridge.appVisible = true
    }

    override fun onStop() {
        ScreenScanBridge.appVisible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val screen = viewModel.screen
                    BackHandler(enabled = screen != Screen.Home) { viewModel.back() }
                    when (screen) {
                        Screen.Home -> HomeScreen(viewModel)
                        Screen.Settings -> SettingsScreen(viewModel)
                        Screen.NewSession -> SessionFormScreen(viewModel, existing = null)
                        is Screen.EditSession -> SessionFormScreen(viewModel, screen.session)
                        is Screen.Scanning -> ScanningScreen(viewModel, screen.session)
                        is Screen.SessionLog -> SessionLogScreen(viewModel, screen.session)
                        is Screen.SessionImages -> SessionImagesScreen(viewModel, screen.session)
                    }
                }
            }
        }
    }
}
