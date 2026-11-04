package com.example.careguardcamera.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CameraDevice
import com.example.careguardcamera.data.CameraConnection
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.data.Severity
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGChip
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.HairLine
import com.example.careguardcamera.ui.components.StatusPill
import com.example.careguardcamera.ui.components.TrustNote
import com.example.careguardcamera.ui.glyph.Glyph
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManagedCamerasScreen(onBack: () -> Unit, onAdd: () -> Unit) {
    val cameras by BackendRepository.cameras.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val serverError by BackendRepository.error.collectAsState()
    var pollingError by rememberSaveable { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(pollingError, lifecycleOwner) {
        if (pollingError != null) return@LaunchedEffect
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
                        pollingError = result
                        ticker.cancel()
                        break
                    }
                    delay(5_000L)
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CGColor.Surface)
            .statusBarsPadding()
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            CGTopBar(title = "카메라 기기", onBack = onBack)
            Spacer(Modifier.height(12.dp))
            TrustNote("카메라 상태는 마지막 접속 시간을 기준으로 표시됩니다. 서버 오류가 나면 이전 온라인 상태를 현재 상태처럼 표시하지 않습니다.")
            Spacer(Modifier.height(16.dp))

            if (loading && cameras.isEmpty()) {
                SetupCard { Text("카메라 목록을 불러오는 중입니다.", style = CGType.Body, color = CGColor.Ink2) }
            } else {
                if (serverError != null || pollingError != null) {
                    SetupCard {
                        Text(pollingError ?: serverError.orEmpty(), style = CGType.Body, color = CGColor.Critical)
                        Spacer(Modifier.height(12.dp))
                        CGButton(
                            "다시 불러오기",
                            onClick = { pollingError = null },
                            kind = ButtonKind.Secondary,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (cameras.isEmpty()) {
                    EmptyCameraState(onAdd)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        cameras.forEach { camera ->
                            key(camera.id) {
                                ManagedCameraCard(camera = camera, nowMillis = nowMillis)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(CGColor.Surface)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            CGButton("카메라 추가", onClick = onAdd, kind = ButtonKind.Brand, large = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyCameraState(onAdd: () -> Unit) {
    SetupCard {
        Box(Modifier.size(54.dp).clip(CircleShape).background(CGColor.BrandSoft), contentAlignment = Alignment.Center) {
            Glyph(Glyphs.Cam, CGColor.BrandInk, 26.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text("등록된 카메라가 없습니다", style = CGType.Heading.copy(fontSize = 19.sp), color = CGColor.Ink)
        Spacer(Modifier.height(6.dp))
        Text("환자 공간에서 사용할 카메라 폰을 먼저 연결해주세요.", style = CGType.Caption, color = CGColor.Ink2)
        Spacer(Modifier.height(16.dp))
        CGButton("첫 카메라 연결", onClick = onAdd, kind = ButtonKind.Soft, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManagedCameraCard(camera: CameraDevice, nowMillis: Long) {
    val scope = rememberCoroutineScope()
    val loading by BackendRepository.loading.collectAsState()
    var name by rememberSaveable(camera.id) { mutableStateOf(camera.name) }
    var error by rememberSaveable(camera.id) { mutableStateOf<String?>(null) }
    var saved by rememberSaveable(camera.id) { mutableStateOf(false) }
    var saving by rememberSaveable(camera.id) { mutableStateOf(false) }
    var updatingMode by rememberSaveable(camera.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(camera.id) { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }

    LaunchedEffect(camera.name) {
        name = camera.name
    }

    SetupCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(CGColor.BrandSoft), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Cam, CGColor.BrandInk, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(camera.name, style = CGType.BodyBold.copy(fontSize = 16.sp), color = CGColor.Ink)
                Text("${camera.modelLabel} · ${camera.id}", style = CGType.Caption, color = CGColor.Ink3)
            }
            val connection = camera.connectionAt(nowMillis)
            StatusPill(camera.connectionLabelAt(nowMillis), cameraStatusSeverity(connection))
        }
        Spacer(Modifier.height(14.dp))
        HairLine()
        Spacer(Modifier.height(14.dp))
        FieldLabel("카메라 이름")
        CGTextField(name, {
            name = it
            error = null
            saved = false
        }, placeholder = "거실 카메라")
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error.orEmpty(), style = CGType.Caption, color = CGColor.Critical)
        } else if (saved) {
            Spacer(Modifier.height(8.dp))
            Text("이름이 저장되었습니다.", style = CGType.Caption, color = CGColor.Safe)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CGButton(
                "이름 저장",
                onClick = {
                    scope.launch {
                        saving = true
                        val result = BackendRepository.renameCamera(camera.id, name)
                        saving = false
                        error = result
                        saved = result == null
                    }
                },
                kind = ButtonKind.Secondary,
                enabled = !saving && !loading && !deleting,
                modifier = Modifier.weight(1f)
            )
            CGButton("연결 해제", onClick = { showRemove = true }, kind = ButtonKind.Red, enabled = !deleting && !loading, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Text("영상 공개 범위", style = CGType.Label, color = CGColor.Ink2)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyMode.entries.forEach { mode ->
                CGChip(
                    text = mode.label,
                    selected = camera.privacyMode == mode,
                    onClick = {
                        scope.launch {
                            updatingMode = true
                            error = BackendRepository.setPrivacyMode(camera.id, mode)
                            updatingMode = false
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            listOfNotNull(
                "상태: ${camera.connectionLabelAt(nowMillis)}",
                camera.lastSeenAt?.let { "마지막 접속 $it" }
            ).joinToString(" · "),
            style = CGType.Caption,
            color = if (updatingMode) CGColor.BrandInk else CGColor.Ink3
        )
    }

    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("카메라 연결을 해제할까요?") },
            text = { Text("${camera.name} 등록 정보가 이 보호자 앱에서 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        deleting = true
                        val result = BackendRepository.removeCamera(camera.id)
                        deleting = false
                        if (result == null) {
                            showRemove = false
                        } else {
                            error = result
                        }
                    }
                }) { Text("해제", color = CGColor.Critical) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text("취소", color = CGColor.Brand) }
            },
            containerColor = CGColor.Paper
        )
    }
}

@Composable
private fun SetupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CGColor.Paper)
            .border(1.dp, CGColor.Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = CGType.Label, color = CGColor.Ink2, modifier = Modifier.padding(bottom = 6.dp))
}

private fun cameraStatusSeverity(connection: CameraConnection): Severity = when (connection) {
    CameraConnection.ONLINE -> Severity.SAFE
    CameraConnection.OFFLINE -> Severity.WARNING
    CameraConnection.UNPAIRED, CameraConnection.UNKNOWN -> Severity.NEUTRAL
}
