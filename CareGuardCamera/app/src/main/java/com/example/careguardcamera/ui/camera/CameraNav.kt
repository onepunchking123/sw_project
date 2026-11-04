package com.example.careguardcamera.ui.camera

import android.app.Activity
import android.content.Intent
import com.example.careguardcamera.RoleActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.data.SetupRepository

/** 카메라용 폰 경로. QR 표시 → 연동 완료 → 거치 안내 → 라이브(상시) → 기기 설정. */
object CamRoutes {
    const val LOADING = "cam/loading"
    const val QR = "cam/qr"
    const val PAIRED = "cam/paired"
    const val PLACE = "cam/place"
    const val LIVE = "cam/live"
    const val SETTINGS = "cam/settings"
}

@Composable
fun CameraApp() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!restored) {
                CameraDeviceRepository.refreshSelfOrIdle()
                val latest = CameraDeviceRepository.state.value
                val target = when {
                    latest.paired && latest.placed -> CamRoutes.LIVE
                    latest.paired -> CamRoutes.PAIRED
                    else -> CamRoutes.QR
                }
                nav.navigate(target) {
                    popUpTo(CamRoutes.LOADING) { inclusive = true }
                    launchSingleTop = true
                }
                restored = true
            }
        }
    }

    NavHost(navController = nav, startDestination = CamRoutes.LOADING) {
        composable(CamRoutes.LOADING) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("카메라 상태 확인 중")
            }
        }
        composable(CamRoutes.QR) {
            QrDisplayScreen(
                onBack = {
                    SetupRepository.selectRole("")
                    context.startActivity(Intent(context, RoleActivity::class.java))
                    (context as? Activity)?.finish()
                },
                onPaired = {
                    nav.navigate(CamRoutes.PAIRED) { popUpTo(CamRoutes.QR) { inclusive = true } }
                }
            )
        }
        composable(CamRoutes.PAIRED) {
            PairedScreen(onPlace = { nav.navigate(CamRoutes.PLACE) { popUpTo(CamRoutes.PAIRED) { inclusive = true } } })
        }
        composable(CamRoutes.PLACE) {
            PlacementScreen(
                onBack = { nav.popBackStack() },
                onStart = {
                    CameraDeviceRepository.markPlaced()
                    nav.navigate(CamRoutes.LIVE) { popUpTo(CamRoutes.PLACE) { inclusive = true } }
                }
            )
        }
        composable(CamRoutes.LIVE) {
            CameraLiveScreen(onOpenSettings = { nav.navigate(CamRoutes.SETTINGS) })
        }
        composable(CamRoutes.SETTINGS) {
            DeviceSettingsScreen(
                onBack = { nav.popBackStack() }
            )
        }
    }
}
