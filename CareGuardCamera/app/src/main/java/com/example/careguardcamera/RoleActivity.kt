package com.example.careguardcamera

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.careguardcamera.ui.screens.RoleSelectScreen
import com.example.careguardcamera.ui.theme.CGTheme

/** 첫 실행 화면. 보호자용 → CaregiverActivity, 카메라용 → CameraActivity (둘 다 Compose). 기존 XML 카메라 화면은 MainActivity 에 그대로 남아 있다. */
class RoleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.careguardcamera.data.SetupRepository.initialize(this)
        val setup = com.example.careguardcamera.data.SetupRepository
        val destination = when (setup.role) {
            "caregiver" -> CaregiverActivity::class.java
            "camera" -> CameraActivity::class.java
            else -> null
        }
        if (destination != null) {
            startActivity(Intent(this, destination))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            CGTheme {
                RoleSelectScreen(
                    onCaregiver = {
                        setup.selectRole("caregiver")
                        startActivity(Intent(this, CaregiverActivity::class.java))
                        finish()
                    },
                    onCamera = {
                        setup.selectRole("camera")
                        startActivity(Intent(this, CameraActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}
