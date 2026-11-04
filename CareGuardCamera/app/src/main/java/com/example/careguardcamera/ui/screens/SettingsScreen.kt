package com.example.careguardcamera.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.ui.components.Avatar
import com.example.careguardcamera.ui.components.CGSwitch
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.GroupLabel
import com.example.careguardcamera.ui.components.RowsCard
import com.example.careguardcamera.ui.components.SettingRow
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SettingsDestination(val route: String) {
    PATIENT("settings/patient"), CAREGIVERS("settings/caregivers"), CAMERAS("settings/cameras"),
    PRIVACY("settings/privacy"), CONTACTS("settings/contacts"), STILL_ALERT("settings/still-alert"),
    DATA_USAGE("settings/data-usage"), APP_INFO("settings/app-info"), SERVER("settings/server")
}

@Composable
fun SettingsScreen(onOpen: (SettingsDestination) -> Unit, onLogout: () -> Unit) {
    var showLogout by remember { mutableStateOf(false) }
    val notifications by BackendRepository.notifications.collectAsState()
    val patient by BackendRepository.patient.collectAsState()
    val contacts by BackendRepository.contacts.collectAsState()
    val cameras by BackendRepository.cameras.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val camera = cameras.firstOrNull()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf<String?>(null) }
    var cameraPollingError by rememberSaveable { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var savingNotification by remember { mutableStateOf(false) }

    LaunchedEffect(cameraPollingError, lifecycleOwner) {
        if (cameraPollingError != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                val ticker = launch {
                    while (true) {
                        nowMillis = SystemClock.elapsedRealtime()
                        delay(1_000L)
                    }
                }
                while (true) {
                    val result = BackendRepository.refreshCameras()
                    if (result != null) {
                        cameraPollingError = result
                        ticker.cancel()
                        break
                    }
                    delay(5_000L)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        CGTopBar(title = "환자 · 설정")
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(22.dp)
        Row(
            Modifier.fillMaxWidth().shadow(8.dp, shape, spotColor = Color(0x143C286E), ambientColor = Color(0x143C286E)).clip(shape).background(CGColor.Paper).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(patient.name.take(1).ifEmpty { "홍" }, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${patient.name}님", style = CGType.Heading.copy(fontSize = 19.sp), color = CGColor.Ink)
                Text("${patient.age}세 ${patient.sex} · 보호자 ${contacts.size}명", style = CGType.Caption, color = CGColor.Ink3)
            }
            Text("수정", style = CGType.LabelBold.copy(fontSize = 13.sp), color = CGColor.Brand,
                modifier = Modifier.clip(CircleShape).background(CGColor.BrandSoft).clickable { onOpen(SettingsDestination.PATIENT) }.padding(horizontal = 16.dp, vertical = 14.dp))
        }

        GroupLabel("돌봄")
        if (cameraPollingError != null) {
            Text(
                "카메라 상태 확인 필요: ${cameraPollingError.orEmpty()} · 다시 확인",
                style = CGType.Caption,
                color = CGColor.Critical,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clickable { cameraPollingError = null }
            )
        }
        RowsCard {
            SettingRow(Glyphs.Mark, "보호자 관리", value = "${contacts.size}명", onClick = { onOpen(SettingsDestination.CAREGIVERS) })
            SettingRow(Glyphs.Cam, "카메라 기기", onClick = { onOpen(SettingsDestination.CAMERAS) }, trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (camera?.onlineAt(nowMillis) == true) CGColor.Safe else CGColor.Ink3))
                    Spacer(Modifier.width(6.dp))
                    Text(camera?.name ?: "미등록", style = CGType.Caption, color = CGColor.Ink3)
                    Spacer(Modifier.width(6.dp))
                    Text("›", style = CGType.Heading, color = CGColor.Ink3)
                }
            })
            SettingRow(Glyphs.Lock, "영상 공개 범위", value = camera?.privacyMode?.label ?: "미등록", onClick = { onOpen(SettingsDestination.PRIVACY) })
            SettingRow(Icons.Outlined.Call, "비상 연락처 관리", value = "보호자 관리", onClick = { onOpen(SettingsDestination.CAREGIVERS) }, last = true)
        }

        GroupLabel("알림")
        if (error != null) {
            Text(error.orEmpty(), style = CGType.Caption, color = CGColor.Critical, modifier = Modifier.padding(bottom = 8.dp))
        }
        RowsCard {
            SettingRow(Icons.Outlined.NotificationsNone, "낙상 알림", trailing = {
                CGSwitch(notifications.fallAlert) { enabled ->
                    if (!savingNotification && !loading) {
                        scope.launch {
                            savingNotification = true
                            error = BackendRepository.setFallAlert(enabled)
                            savingNotification = false
                        }
                    }
                }
            })
            SettingRow(Icons.Outlined.NotificationsNone, "미동(R2) 알림", value = notifications.stillMinutes?.let { "${it}분 이상" } ?: "꺼짐", onClick = { onOpen(SettingsDestination.STILL_ALERT) })
            SettingRow(Glyphs.Moon, "카메라 연결 끊김 알림", trailing = {
                CGSwitch(notifications.offlineAlert) { enabled ->
                    if (!savingNotification && !loading) {
                        scope.launch {
                            savingNotification = true
                            error = BackendRepository.setOfflineAlert(enabled)
                            savingNotification = false
                        }
                    }
                }
            }, last = true)
        }

        GroupLabel("서비스")
        RowsCard {
            SettingRow(Icons.Outlined.Info, "서버 설정", value = BackendRepository.serverUrl, onClick = { onOpen(SettingsDestination.SERVER) })
            SettingRow(Icons.Outlined.Description, "데이터 활용 안내", onClick = { onOpen(SettingsDestination.DATA_USAGE) })
            SettingRow(Icons.Outlined.Info, "앱 정보", onClick = { onOpen(SettingsDestination.APP_INFO) })
            Box(Modifier.fillMaxWidth().height(52.dp).clickable { showLogout = true }, contentAlignment = Alignment.Center) {
                Text("로그아웃", style = CGType.Body.copy(fontSize = 15.sp), color = CGColor.Ink2)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("로그아웃할까요?") },
            text = { Text("역할 선택 화면으로 돌아갑니다.") },
            confirmButton = { TextButton(onClick = { showLogout = false; onLogout() }) { Text("로그아웃", color = CGColor.Brand) } },
            dismissButton = { TextButton(onClick = { showLogout = false }) { Text("취소") } },
            containerColor = CGColor.Paper
        )
    }
}
