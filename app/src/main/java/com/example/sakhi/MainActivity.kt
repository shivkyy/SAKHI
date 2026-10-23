package com.example.sakhi

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.sakhi.ui.navigation.SakhiAppRoot

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // State updates reactively through PermissionsManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enable display over lock-screen and turn screen on for emergency safety sessions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val app = application as SakhiApplication

        if (!app.permissionsManager.checkPermissions().hasCallPhone) {
            permissionLauncher.launch(arrayOf(android.Manifest.permission.CALL_PHONE))
        }

        // Start background safety monitoring service
        com.example.sakhi.service.EmergencyForegroundService.startMonitoring(app)

        setContent {
            SakhiAppRoot(
                app = app,
                onRequestPermissions = {
                    val permissions = app.permissionsManager.getRequiredRuntimePermissions()
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            )
        }
    }
}
