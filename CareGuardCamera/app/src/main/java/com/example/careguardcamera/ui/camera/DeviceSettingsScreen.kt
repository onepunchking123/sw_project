package com.example.careguardcamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.data.Severity
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.GroupLabel
import com.example.careguardcamera.ui.components.RowsCard
import com.example.careguardcamera.ui.components.SettingRow
import com.example.careguardcamera.ui.components.StatusPill
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType

@Composable
fun DeviceSettingsScreen(onBack: () -> Unit) {
    val state by CameraDeviceRepository.state.collectAsState()
    val displayedStatus = if (state.error != null) "UNKNOWN" else state.status
    Column(
        Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(20.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        CGTopBar(title = "기기 설정", onBack = onBack)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(CGColor.Paper)
                .border(1.dp, CGColor.Line, RoundedCornerShape(22.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(CGColor.BrandSoft), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Cam, CGColor.BrandInk, 26.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(state.name, style = CGType.Heading.copy(fontSize = 19.sp), color = CGColor.Ink)
                Text(state.cameraId?.let { "기기 ID ${it.take(8)}" } ?: "기기 등록 전", style = CGType.Caption, color = CGColor.Ink2)
            }
            StatusPill(displayedStatus.label(), displayedStatus.severity())
        }

        GroupLabel("서버")
        RowsCard {
            SettingRow(Glyphs.Mark, "서버 주소", value = state.serverUrl)
            SettingRow(Glyphs.Cam, "마지막 연결", value = state.lastSeenAt ?: "아직 없음", last = true)
        }

        GroupLabel("안내")
        RowsCard {
            SettingRow(Glyphs.Lock, "연결 해제", value = "보호자 앱 카메라 관리에서 진행", last = true)
        }

        if (state.disconnected) {
            Text(
                "연결이 해제됐습니다. 현재 버전에서는 재연결을 지원하지 않습니다.",
                style = CGType.Caption,
                color = CGColor.Critical
            )
        }
    }
}

private fun String.label(): String = when (this) {
    "ONLINE" -> "온라인"
    "OFFLINE" -> "오프라인"
    "UNPAIRED" -> "페어링 대기"
    else -> "확인 필요"
}

private fun String.severity(): Severity = when (this) {
    "ONLINE" -> Severity.SAFE
    "OFFLINE" -> Severity.WARNING
    else -> Severity.NEUTRAL
}
