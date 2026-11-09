package com.example.facefilters

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }

    // Screen mode keeps its stickers off the app itself, which has its own.
    override fun onStart() {
        super.onStart()
        ScreenFilterService.appVisible.value = true
    }

    override fun onStop() {
        ScreenFilterService.appVisible.value = false
        super.onStop()
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    fun cameraAllowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(cameraAllowed()) }
    // The user may have allowed it in Settings.
    LifecycleResumeEffect(Unit) {
        hasCamera = cameraAllowed()
        onPauseOrDispose {}
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCamera = it }
    LaunchedEffect(Unit) {
        if (!hasCamera) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    if (hasCamera) {
        CameraScreen()
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        ) {
            Text(
                "Face Filters needs the camera to put filters on your face.",
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text("Allow camera")
            }
            // Once the user has said no twice, Android stops asking: only Settings can allow it.
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
            ) {
                Text("Open app settings")
            }
        }
    }
}
