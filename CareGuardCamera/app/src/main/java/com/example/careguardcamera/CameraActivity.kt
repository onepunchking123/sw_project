package com.example.careguardcamera

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.careguardcamera.ui.camera.CameraApp
import com.example.careguardcamera.ui.theme.CGTheme

/** 카메라용 폰 앱 (Compose). 거치 후에는 라이브 화면에 머물고 화면이 꺼지지 않는다. */
class CameraActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.careguardcamera.data.SetupRepository.initialize(this)
        com.example.careguardcamera.data.BackendRepository.initialize(this)
        com.example.careguardcamera.data.CameraDeviceRepository.initialize(this)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CGTheme { CameraApp() }
        }
    }
}
