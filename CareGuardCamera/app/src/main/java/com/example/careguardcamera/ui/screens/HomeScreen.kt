package com.example.careguardcamera.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.timelineEntries
import com.example.careguardcamera.data.Severity
import com.example.careguardcamera.ui.components.*
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

@Composable
internal fun HudChip(text: String, bg: Color) {
    Text(
        text,
        style = CGType.LabelBold.copy(fontSize = 11.5.sp, lineHeight = 14.sp),
        color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

/** 홈 섹션 카드: 연보라 바탕 위 흰 카드. 헤더(글리프 + 제목 + 액션 링크) 뒤에 내용이 온다. */
@Composable
private fun SectionCard(
    title: String,
    glyph: ImageVector,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CGColor.Paper).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, CGColor.Brand, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(title, style = CGType.BodyBold.copy(fontSize = 16.sp), color = CGColor.Ink, maxLines = 1, modifier = Modifier.weight(1f))
            if (action != null) Text(
                action, style = CGType.Caption, color = CGColor.Brand, maxLines = 1, softWrap = false,
                modifier = Modifier.clickable(enabled = onAction != null) { onAction?.invoke() }.padding(4.dp)
            )
        }
        content()
    }
}

@Composable
fun HomeScreen(onOpenLive: () -> Unit, onOpenTimeline: () -> Unit, onOpenEmergency: () -> Unit) {
    val ctx = LocalContext.current
    val patient by BackendRepository.patient.collectAsState()
    val events by BackendRepository.events.collectAsState()
    val records by BackendRepository.records.collectAsState()
    val cameras by BackendRepository.cameras.collectAsState()
    val demo by BackendRepository.demo.collectAsState()
    val weekly by BackendRepository.weekly.collectAsState()
    var pollingError by rememberSaveable { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val today = timelineEntries(events, records)

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

    Column(Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${BackendRepository.todayMonth}월 ${BackendRepository.today}일", style = CGType.Caption, color = CGColor.Ink2)
        Text("${patient.name.ifBlank { "환자" }}님의 돌봄 기록", style = CGType.Title, color = CGColor.Ink)
        if (demo.emergency) {
            SectionCard("대응이 필요한 상황", Glyphs.Lie, "상세 확인 ›", onOpenEmergency) {
                Text("종료되지 않은 긴급 상황이 있습니다.", style = CGType.BodyBold, color = CGColor.Critical)
            }
        }
        SectionCard("카메라", Glyphs.Cam, "기기 보기 ›", onOpenLive) {
            Text(if (cameras.isEmpty()) "등록된 카메라가 없습니다." else "등록 ${cameras.size}대 · 온라인 ${cameras.count { it.onlineAt(nowMillis) }}대", style = CGType.BodyBold)
            Text("기기 정보는 서버에서 조회합니다. 실시간 영상 전송은 아직 연결되지 않았습니다.", style = CGType.Caption, color = CGColor.Ink2)
            if (pollingError != null) {
                Text(
                    "카메라 상태 확인 필요: ${pollingError.orEmpty()} · 다시 확인",
                    style = CGType.Caption,
                    color = CGColor.Critical,
                    modifier = Modifier.clickable { pollingError = null }
                )
            }
        }
        SectionCard("오늘 타임라인", Glyphs.Mark, "전체 보기 ›", onOpenTimeline) {
            Text("AI 감지 ${events.count { it.dayOffset == 0 }}건 · 보호자 기록 ${records.count { it.dayOffset == 0 }}건", style = CGType.LabelBold, color = CGColor.BrandInk)
            if (today.isEmpty()) Text("오늘 등록된 기록이 없습니다.", style = CGType.Body, color = CGColor.Ink2)
            today.take(4).forEach { row -> FlatRow(row.time, row.title, row.detail, row.severity, glyphLeft = true) }
        }
        SectionCard("최근 주간 리포트", Glyphs.Sit) {
            val report = weekly
            if (report == null) Text("아직 생성된 리포트가 없습니다.", style = CGType.Body, color = CGColor.Ink2)
            else {
                Text(report.rangeLabel, style = CGType.Caption, color = CGColor.BrandInk)
                Text(report.sentence, style = CGType.Body)
                Row(Modifier.fillMaxWidth()) {
                    Stat("${report.abnormalCount}", "이상 행동", CGColor.Ink)
                    Stat("${report.fallCount}", "낙상", CGColor.Critical)
                    Stat("${report.uptimePercent}%", "카메라 가동", CGColor.Ink)
                }
                Text(report.summaryLine, style = CGType.Caption, color = CGColor.Ink2)
            }
        }
        CGButton("긴급전화 119", { ctx.dial("119") }, Modifier.fillMaxWidth(), kind = ButtonKind.Red, icon = Icons.Outlined.Call)
    }
}

@Composable
private fun RowScope.Stat(value: String, label: String, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = CGType.Stat, color = color)
        Spacer(Modifier.height(4.dp))
        Text(label, style = CGType.Caption.copy(fontSize = 12.sp), color = CGColor.Ink3)
    }
}
