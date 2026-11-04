package com.example.careguardcamera.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.careguardcamera.data.ApiMapping
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CameraDevice
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.data.Severity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.careguardcamera.video.GuardianWebRtcViewer
import com.example.careguardcamera.video.WebRtcFrameBridge
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.careguardcamera.ui.components.*
import com.example.careguardcamera.ui.theme.*
import livekit.org.webrtc.SurfaceViewRenderer

@Composable
fun LiveScreen(onBack: () -> Unit, startCameraId: String? = null) {
    val cameras by BackendRepository.cameras.collectAsState()
    val serverError by BackendRepository.error.collectAsState()
    var pollingError by rememberSaveable { mutableStateOf<String?>(null) }
    var liveError by remember { mutableStateOf<String?>(null) }
    var peerState by remember { mutableStateOf("IDLE") }
    var liveRetryKey by rememberSaveable { mutableIntStateOf(0) }
    var selectedCameraId by rememberSaveable { mutableStateOf(startCameraId) }
    var liveRunId by remember { mutableLongStateOf(0L) }
    var activeViewerAttemptId by remember { mutableLongStateOf(0L) }
    var nowMillis by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // 선택한 카메라가 목록에서 사라지면 온라인 카메라 → 첫 카메라 순으로 다시 고른다. 선택이 유지되는 동안은 바꾸지 않는다.
    LaunchedEffect(cameras) {
        if (cameras.isEmpty()) return@LaunchedEffect
        if (cameras.none { it.id == selectedCameraId }) {
            val now = SystemClock.elapsedRealtime()
            selectedCameraId = (cameras.firstOrNull { it.onlineAt(now) } ?: cameras.first()).id
        }
    }
    val selectedCamera = cameras.firstOrNull { it.id == selectedCameraId }
    val selectedLiveCameraId = selectedCamera?.id
    val viewerNeedsReconnect = peerState.needsGuardianReconnect()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(WebRtcFrameBridge.sharedEglContext, null)
            setMirror(false)
            setEnableHardwareScaler(true)
        }
    }
    // 카메라가 바뀌면 이전 카메라의 마지막 프레임을 지우고, 이전 viewer의 늦은 콜백이 새 상태를 덮지 못하게 한다.
    LaunchedEffect(selectedLiveCameraId) {
        liveRunId++
        activeViewerAttemptId++
        liveError = null
        peerState = "IDLE"
        runCatching { renderer.clearImage() }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { renderer.release() }
        }
    }
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
                    val result = BackendRepository.refreshCameras() ?: BackendRepository.refreshLiveEvents()
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
    LaunchedEffect(selectedLiveCameraId, liveRetryKey, lifecycleOwner) {
        val cameraId = selectedLiveCameraId ?: return@LaunchedEffect
        val patientId = BackendRepository.currentPatientId() ?: return@LaunchedEffect
        val guardianUserId = BackendRepository.currentGuardianUserId() ?: return@LaunchedEffect
        val runId = ++liveRunId
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var reconnectAttempt = 0
            while (reconnectAttempt <= 3) {
                val attemptId = ++activeViewerAttemptId
                val api = BackendRepository.liveSessionApi()
                val factory = WebRtcFrameBridge.createFactory(renderer.context)
                val stateEvents = Channel<String>(Channel.CONFLATED)
                var viewer: GuardianWebRtcViewer? = null
                try {
                    liveError = if (reconnectAttempt == 0) {
                        null
                    } else {
                        "영상 연결이 끊겨 자동 재접속 중입니다. ($reconnectAttempt/3)"
                    }
                    peerState = if (reconnectAttempt == 0) "DISCOVERING" else "RETRYING"
                    val session = api.discoverGuardianSession(patientId, cameraId, guardianUserId)
                    if (session.privacyMode == PrivacyMode.SKELETON_ONLY) {
                        peerState = "PRIVACY_MODE_UNSUPPORTED"
                        liveError = "골격만 모드에서는 원본 영상이 보호자 앱으로 전송되지 않습니다. 카메라 설정에서 영상 송출 모드를 변경한 뒤 다시 시도해주세요."
                        return@repeatOnLifecycle
                    }
                    peerState = "JOINING"
                    viewer = GuardianWebRtcViewer(
                        api = api,
                        factory = factory,
                        patientId = session.patientId,
                        sessionId = session.sessionId,
                        guardianUserId = guardianUserId,
                        videoSink = renderer,
                        scope = scope,
                        onState = { state ->
                            scope.launch {
                                if (liveRunId == runId && activeViewerAttemptId == attemptId) {
                                    peerState = state
                                    stateEvents.trySend(state)
                                }
                            }
                        }
                    )
                    viewer.start()
                    liveError = null
                    var established = false
                    val negotiationDeadline = android.os.SystemClock.elapsedRealtime() + 20_000L
                    while (true) {
                        val state = if (established) {
                            stateEvents.receive()
                        } else {
                            kotlinx.coroutines.withTimeoutOrNull(
                                (negotiationDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                            ) { stateEvents.receive() }
                                ?: throw IllegalStateException("영상 연결 시간이 초과되었습니다.")
                        }
                        if (state == "CONNECTED") established = true
                        if (state.needsGuardianReconnect()) break
                    }
                    reconnectAttempt++
                    if (reconnectAttempt > 3) {
                        liveError = "영상 연결이 끊겼습니다. 다시 불러오기를 눌러 새 연결을 시작해주세요."
                        peerState = "FAILED"
                        return@repeatOnLifecycle
                    }
                    liveError = "영상 연결이 끊겨 자동 재접속 중입니다. ($reconnectAttempt/3)"
                    delay(1_000L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reconnectAttempt++
                    if (reconnectAttempt > 3) {
                        liveError = e.message?.takeIf { it.isNotBlank() } ?: "실시간 영상을 연결할 수 없습니다. 다시 불러오기를 눌러 새 연결을 시작해주세요."
                        peerState = "FAILED"
                        return@repeatOnLifecycle
                    }
                    liveError = "영상 연결 오류가 발생해 자동 재접속 중입니다. ($reconnectAttempt/3)"
                    peerState = "RETRYING"
                    delay(1_000L)
                } finally {
                    stateEvents.close()
                    withContext(NonCancellable) {
                        runCatching { viewer?.stop() }
                        runCatching { factory.dispose() }
                    }
                }
            }
        }
    }

    fun selectCamera(id: String) {
        if (id != selectedCameraId) selectedCameraId = id
    }

    Column(
        Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CGTopBar(title = "카메라 보기", onBack = onBack)

        if (cameras.isEmpty()) {
            EmptyCameraCard(pollingError ?: serverError, onRetry = { pollingError = null })
        } else {
            if (cameras.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    cameras.forEach { camera ->
                        CameraChip(
                            camera = camera,
                            selected = camera.id == selectedCameraId,
                            online = camera.onlineAt(nowMillis),
                            onClick = { selectCamera(camera.id) }
                        )
                    }
                }
            }

            selectedCamera?.let { camera ->
                val online = camera.onlineAt(nowMillis)
                val status = liveStatus(peerState, online)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(camera.name, style = CGType.Heading, color = CGColor.Ink, maxLines = 1)
                        Text(camera.connectionLabelAt(nowMillis), style = CGType.Caption, color = if (online) CGColor.Safe else CGColor.Ink3)
                    }
                    if (peerState == "CONNECTED") LiveBadge() else StatusPill(status.label, status.tone)
                }

                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(CGColor.Dark)
                ) {
                    AndroidView(factory = { renderer }, modifier = Modifier.matchParentSize())
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HudChip(if (peerState == "CONNECTED") "● LIVE" else status.label, status.hudColor)
                    }
                    if (peerState != "CONNECTED") {
                        Text(
                            status.hint,
                            style = CGType.Label,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp)
                        )
                    }
                }

                if (serverError != null || pollingError != null || liveError != null || viewerNeedsReconnect) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(CGRadius.Card)).background(CGColor.Paper).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            liveError ?: pollingError ?: serverError ?: "영상 연결이 끊겼습니다. 다시 불러오기를 눌러 새 연결을 시작해주세요.",
                            style = CGType.Body,
                            color = CGColor.Critical
                        )
                        CGButton("다시 불러오기", onClick = { pollingError = null; liveError = null; liveRetryKey++ }, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
                    }
                }

                CameraDetailCard(camera, online, peerState)
            }
        }

        TrustNote("실시간 보기 연결은 영상만 사용하며 오디오는 전송하지 않습니다. 시청자가 없어도 카메라 폰의 로컬 감지는 계속됩니다.")
    }
}

/** 카메라 선택 칩: 상태 점 + 이름. 선택된 칩은 보라색 채움. */
@Composable
private fun CameraChip(camera: CameraDevice, selected: Boolean, online: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CGColor.Brand else CGColor.Paper
    val fg = if (selected) Color.White else CGColor.Ink
    val border = if (selected) Color.Transparent else CGColor.Line
    val dot = when {
        online && selected -> CGColor.SafeOnDark
        online -> CGColor.Safe
        selected -> Color.White.copy(alpha = 0.55f)
        else -> CGColor.Ink3
    }
    val stateLabel = if (online) "온라인" else "오프라인"
    Row(
        Modifier
            .heightIn(min = CGSize.Touch)
            .clip(CircleShape)
            .background(bg)
            .border(1.5.dp, border, CircleShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { stateDescription = stateLabel }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(camera.name, style = CGType.Label, color = fg, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
    }
}

@Composable
private fun CameraDetailCard(camera: CameraDevice, online: Boolean, peerState: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CGRadius.Card)).background(CGColor.Paper).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DetailRow("기기", camera.modelLabel)
        DetailRow("영상 표시", camera.livePrivacyMessage())
        DetailRow("마지막 접속", camera.lastSeenAt?.let { ApiMapping.displayDateTime(it) } ?: "아직 접속 기록이 없습니다")
        HairLine()
        Text(
            when {
                peerState == "CONNECTED" -> "실시간 영상을 받고 있어요."
                peerState.needsGuardianReconnect() && online -> "연결이 끊겼어요. 다시 불러오기를 누르면 새 연결을 시작합니다."
                online -> "활성 라이브 세션을 찾아 영상 연결을 시작합니다."
                else -> "카메라 앱이 화면에 켜져 있어야 실시간 보기가 시작됩니다."
            },
            style = CGType.Caption,
            color = CGColor.Ink2
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = CGType.Caption, color = CGColor.Ink3, modifier = Modifier.width(72.dp))
        Text(value, style = CGType.Label, color = CGColor.Ink, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyCameraCard(error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CGRadius.Card)).background(CGColor.Paper).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("등록된 카메라가 없어요", style = CGType.Subheading, color = CGColor.Ink)
        Text("환자·설정 탭에서 카메라를 추가하면 여기에서 실시간 영상을 볼 수 있습니다.", style = CGType.Caption, color = CGColor.Ink2)
        if (error != null) {
            Text(error, style = CGType.Caption, color = CGColor.Critical)
            CGButton("다시 확인", onClick = onRetry, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        }
    }
}

private data class LiveStatus(val label: String, val tone: Severity, val hudColor: Color, val hint: String)

/** WebRTC 내부 상태 문자열을 사용자용 라벨로 바꾼다. */
private fun liveStatus(peerState: String, online: Boolean): LiveStatus = when (peerState) {
    "CONNECTED" -> LiveStatus("LIVE", Severity.SAFE, CGColor.Brand, "")
    "DISCOVERING", "JOINING", "NEW", "CONNECTING" -> LiveStatus("연결 중", Severity.NEUTRAL, Color(0x29FFFFFF), "영상을 연결하고 있어요…")
    "RETRYING" -> LiveStatus("재접속 중", Severity.WARNING, CGColor.Warning, "연결이 끊겨 다시 접속하고 있어요")
    "PRIVACY_MODE_UNSUPPORTED" -> LiveStatus("영상 제한", Severity.WARNING, CGColor.Warning, "골격만 모드라 영상이 전송되지 않아요")
    "FAILED", "DISCONNECTED", "CLOSED" ->
        if (online) LiveStatus("연결 끊김", Severity.CRITICAL, CGColor.CriticalOnDark, "영상 연결이 끊겼어요")
        else LiveStatus("오프라인", Severity.NEUTRAL, Color(0x29FFFFFF), "카메라 앱이 켜져 있지 않아 영상을 받을 수 없어요")
    else -> if (online) LiveStatus("대기", Severity.NEUTRAL, Color(0x29FFFFFF), "잠시 후 영상 연결을 시작해요")
    else LiveStatus("오프라인", Severity.NEUTRAL, Color(0x29FFFFFF), "카메라 앱이 켜져 있지 않아요")
}

private fun CameraDevice.livePrivacyMessage(): String = when (privacyMode) {
    PrivacyMode.NO_OVERLAY -> "원본 영상"
    PrivacyMode.VIDEO_SKELETON -> "영상 위 골격 표시"
    PrivacyMode.SKELETON_ONLY -> "골격만 · 원본 영상 송출 차단"
}

private fun String.needsGuardianReconnect(): Boolean = this in setOf("RETRYING", "FAILED", "DISCONNECTED", "CLOSED")
