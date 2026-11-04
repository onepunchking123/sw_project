package com.example.careguardcamera

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.careguardcamera.ui.nav.CaregiverApp
import com.example.careguardcamera.ui.theme.CGTheme

/** 보호자 앱 (Compose). */
class CaregiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.careguardcamera.data.SetupRepository.initialize(this)
        com.example.careguardcamera.data.BackendRepository.initialize(this)
        enableEdgeToEdge()
        setContent {
            CGTheme {
                CaregiverApp(onLogout = {
                    com.example.careguardcamera.data.BackendRepository.logout()
                    com.example.careguardcamera.data.SetupRepository.selectRole("")
                    startActivity(Intent(this, RoleActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                })
            }
        }
    }
}
