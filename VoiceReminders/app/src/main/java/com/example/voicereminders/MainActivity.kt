package com.example.voicereminders

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {

    /** Bumped whenever permissions or calendars may have changed, to read them again. */
    private var refreshKey by mutableIntStateOf(0)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshKey++
            startListeningIfWanted()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
        if (Permissions.missing(this).isNotEmpty()) requestPermissions.launch(Permissions.all.keys.toTypedArray())

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) {
                        MainScreen(
                            refreshKey = refreshKey,
                            onRequestPermissions = { requestPermissions.launch(Permissions.all.keys.toTypedArray()) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
        startListeningIfWanted()
    }

    /** Listening can't restart by itself after a reboot, so it restarts whenever the app is opened. */
    private fun startListeningIfWanted() {
        if (SettingsStore(this).handsFree && !ListenService.isRunning &&
            Permissions.granted(this, Manifest.permission.RECORD_AUDIO)
        ) {
            ListenService.start(this)
        }
    }
}
