package com.androidengineers.agent_quickstart_android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.androidengineers.agent_quickstart_android.fillerfree.live.presentation.LiveRoomScreen
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.FillerFreeScreen
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.FillerFreeViewModel
import com.androidengineers.agent_quickstart_android.ui.theme.AgentquickstartandroidTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<FillerFreeViewModel> {
        viewModelFactory {
            initializer {
                FillerFreeViewModel(application)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val systemDarkTheme = isSystemInDarkTheme()

            AgentquickstartandroidTheme(darkTheme = systemDarkTheme) {
                val context = LocalContext.current
                val currentViewModel by rememberUpdatedState(viewModel)

                fun hasPermission(permission: String): Boolean =
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

                // Mic is required to start a session at all; camera is optional
                // (eye-contact coaching degrades to disabled, not a hard failure,
                // if the user denies it — see FillerFreeViewModel.setHasCameraPermission).
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    currentViewModel.setHasCameraPermission(
                        results[Manifest.permission.CAMERA] ?: hasPermission(Manifest.permission.CAMERA)
                    )
                    val micGranted = results[Manifest.permission.RECORD_AUDIO]
                        ?: hasPermission(Manifest.permission.RECORD_AUDIO)
                    if (micGranted) {
                        currentViewModel.startSession()
                    }
                }

                LaunchedEffect(Unit) {
                    currentViewModel.setHasCameraPermission(hasPermission(Manifest.permission.CAMERA))
                }

                // Simple two-destination switch -- the app has no nav
                // library, and "Filler-Free: Live" (fillerfree/live) is
                // reached as a full-screen destination from the "Go Live"
                // button rather than nested inside the solo-coaching
                // screen's own state machine. Leaving the room always
                // returns to wherever the solo flow currently is (topic
                // select or summary), matching how it looked before Go
                // Live was pressed.
                var showLiveRooms by remember { mutableStateOf(false) }

                if (showLiveRooms) {
                    val uiState by currentViewModel.uiState.collectAsStateWithLifecycle()
                    LiveRoomScreen(
                        userName = uiState.userName,
                        onExit = { showLiveRooms = false },
                    )
                } else {
                    FillerFreeScreen(
                        viewModel = viewModel,
                        onGoLive = { showLiveRooms = true },
                        onRequestStart = {
                            val micGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
                            val cameraGranted = hasPermission(Manifest.permission.CAMERA)
                            currentViewModel.setHasCameraPermission(cameraGranted)

                            if (micGranted) {
                                currentViewModel.startSession()
                                if (!cameraGranted) {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                }
                            } else {
                                val toRequest = if (cameraGranted) {
                                    arrayOf(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                                }
                                permissionLauncher.launch(toRequest)
                            }
                        },
                    )
                }
            }
        }
    }
}
