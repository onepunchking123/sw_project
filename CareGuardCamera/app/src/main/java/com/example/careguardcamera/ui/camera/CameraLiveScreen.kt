package com.example.careguardcamera.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.careguardcamera.AlarmHelper
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.data.LiveSessionStatus
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.inference.TimestampedFallModelEngine
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.IconCircle
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.screens.HudChip
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import com.example.careguardcamera.video.CancelDelivery
import com.example.careguardcamera.video.ClipDelivery
import com.example.careguardcamera.video.clipPrivacyMode
import com.example.careguardcamera.video.FallAlarm
import com.example.careguardcamera.video.FallAlertState
import com.example.careguardcamera.video.FallIncidentCoordinator
import com.example.careguardcamera.video.IncidentClipRecorder
import com.example.careguardcamera.video.LiveCaptureSession
import com.example.careguardcamera.video.Mp4ClipEncoder
import com.example.careguardcamera.video.LiveCameraSessionState
import com.example.careguardcamera.video.LiveSessionController
import com.example.careguardcamera.video.FallModelInferenceAdapter
import com.example.careguardcamera.video.LatestOverlayPose
import com.example.careguardcamera.video.WebRtcBridgeVideoPeer
import com.example.careguardcamera.video.WebRtcFrameBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraLiveScreen(onOpenSettings: () -> Unit) {
    val cameraState by CameraDeviceRepository.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val idleLiveState = remember { MutableStateFlow(LiveCameraSessionState()) }
    var retryKey by remember { mutableIntStateOf(0) }
    var stoppedError by remember { mutableStateOf<String?>(null) }
    var transportError by remember { mutableStateOf<String?>(null) }
    var liveController by remember { mutableStateOf<LiveSessionController?>(null) }
    val liveState by (liveController?.state ?: idleLiveState).collectAsState()
    var cameraPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionGranted = granted
        if (!granted) stoppedError = "카메라 권한이 필요합니다."
    }
    val displayedStatus = if (stoppedError != null || cameraState.error != null) "UNKNOWN" else cameraState.status
    // 낙상 클립용 최근 프레임. 화면이 살아 있는 동안 하나만 둔다.
    val clipRecorder = remember { IncidentClipRecorder() }
    val fallAlarm = remember {
        val helper = AlarmHelper(context)
        object : FallAlarm {
            // 경보음이 실패해도 카운트다운은 계속돼야 한다.
            override fun start() { runCatching { helper.startAlarm() } }
            override fun stop() { runCatching { helper.stopAlarm() } }
        }
    }
    val fallCoordinator = remember(cameraState.serverUrl) {
        FallIncidentCoordinator(
            scope = scope,
            api = CameraDeviceRepository.fallIncidentApi(),
            cameraId = { CameraDeviceRepository.state.value.cameraId },
            alarm = fallAlarm,
            onClipError = { error -> android.util.Log.w("FallIncident", "낙상 클립을 만들지 못했습니다.", error) },
            captureClip = { event ->
                val trigger = event.frameTimestampNanos
                if (trigger == null) {
                    null
                } else {
                    val frames = clipRecorder.framesAround(trigger)
                    val mode = clipPrivacyMode(liveController?.state?.value?.privacyMode, CameraDeviceRepository.state.value.privacyMode)
                    withContext(Dispatchers.Default) {
                        Mp4ClipEncoder(context.cacheDir).encode(frames, mode)
                    }
                }
            }
        )
    }
    val fallAlert by fallCoordinator.state.collectAsState()
    DisposableEffect(fallCoordinator) {
        onDispose { fallAlarm.stop() }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(retryKey, stoppedError, lifecycleOwner) {
        if (stoppedError != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            coroutineScope {
                val loop = launch {
                    while (true) {
                        val error = CameraDeviceRepository.heartbeat()
                        if (error != null) {
                            transportError = error
                        }
                        delay(15_000L)
                    }
                }
                loop.join()
            }
        }
    }

    LaunchedEffect(retryKey, stoppedError, cameraPermissionGranted, cameraState.cameraId, cameraState.serverUrl, lifecycleOwner) {
        if (stoppedError != null || !cameraPermissionGranted || cameraState.cameraId == null) return@LaunchedEffect
        transportError = null
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val capture = LiveCaptureSession(context, previewView, lifecycleOwner)
            val overlayPose = LatestOverlayPose()
            var model: FallModelInferenceAdapter? = null
            var factory: livekit.org.webrtc.PeerConnectionFactory? = null
            var bridge: WebRtcFrameBridge? = null
            var controller: LiveSessionController? = null
            try {
                // Retain partially initialized resources even if lifecycle cancellation occurs.
                withContext(NonCancellable + Dispatchers.Default) {
                    model = FallModelInferenceAdapter(TimestampedFallModelEngine(context), overlayPose)
                    factory = WebRtcFrameBridge.createFactory(context)
                    bridge = WebRtcFrameBridge.create(requireNotNull(factory))
                }
                val videoPeer = WebRtcBridgeVideoPeer(
                    requireNotNull(factory), requireNotNull(bridge), scope, overlayPose
                ) { message -> scope.launch { transportError = message } }
                val activeController = LiveSessionController(
                    api = CameraDeviceRepository.liveSessionApi(),
                    captureSession = capture,
                    inference = requireNotNull(model),
                    peer = videoPeer,
                    scope = scope
                )
                controller = activeController
                liveController = activeController
                activeController.start(cameraState.cameraId.orEmpty(), cameraState.privacyModeOrDefault())
                coroutineScope {
                    launch(Dispatchers.Default) {
                        capture.frames.collect { frame ->
                            // 골격만 모드에서는 클립을 만들 수 없으므로 원본 프레임을 보관하지도 않는다.
                            if (clipPrivacyMode(activeController.state.value.privacyMode, CameraDeviceRepository.state.value.privacyMode) == PrivacyMode.SKELETON_ONLY) {
                                clipRecorder.clear()
                            } else {
                                clipRecorder.offer(frame, overlayPose.freshFor(frame))
                            }
                        }
                    }
                    launch {
                        activeController.fallIncidents.collect { fallCoordinator.onFallIncident(it) }
                    }
                    awaitCancellation()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stoppedError = e.message?.takeIf { it.isNotBlank() } ?: "실시간 세션을 시작할 수 없습니다."
            } finally {
                withContext(NonCancellable) {
                    runCatching { controller?.stop() }
                    capture.stop()
                    withContext(Dispatchers.Default) {
                        runCatching { model?.close() }
                        runCatching { bridge?.close() }
                        runCatching { factory?.dispose() }
                    }
                    liveController = null
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(CGColor.Dark)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HudChip("카메라 앱 실행 중", CGColor.Brand)
                Spacer(Modifier.weight(1f))
                Text("서버 상태 ${displayedStatus.label()}", style = CGType.Caption.copy(fontSize = 12.sp), color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(10.dp))
                IconCircle(onClick = onOpenSettings, dark = true) {
                    Icon(Icons.Outlined.Settings, "기기 설정", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                    if (cameraPermissionGranted) {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    } else {
                        Glyph(Glyphs.Cam, CGColor.BrandOnDark, 44.dp)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    if (cameraPermissionGranted) "실시간 감지 준비 중" else "카메라 권한이 필요해요",
                    style = CGType.Title.copy(fontSize = 22.sp, lineHeight = 28.sp),
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    liveState.liveMessage(),
                    style = CGType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = Color.White.copy(alpha = 0.66f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HudChip(displayedStatus.label(), displayedStatus.statusColor())
                    HudChip(liveState.status.label(), liveState.status.statusColor())
                    cameraState.lastSeenAt?.let { HudChip("최근 접속 기록 있음", Color(0x33000000)) }
                }
            }

            Column(Modifier.navigationBarsPadding().padding(top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(cameraState.name, style = CGType.BodyBold.copy(fontSize = 15.sp), color = Color.White)
                    Text(
                        stoppedError ?: cameraState.error ?: transportError ?: liveState.lastError ?: "서버에 접속 상태를 보내는 중",
                        style = CGType.Caption.copy(fontSize = 12.5.sp),
                        color = if (cameraState.error != null || stoppedError != null) CGColor.CriticalOnDark else Color.White.copy(alpha = 0.6f)
                    )
                }
                if (stoppedError != null) {
                    CGButton(
                        "다시 시도",
                        onClick = {
                            stoppedError = null
                            retryKey++
                        },
                        modifier = Modifier.fillMaxWidth(),
                        kind = ButtonKind.Brand,
                        large = true,
                        enabled = !cameraState.loading
                    )
                }
            }
        }
        FallAlertOverlay(fallAlert, onPatientOk = fallCoordinator::patientOk)
    }
}

/** 환자가 보는 낙상 경보. 카운트다운 동안 화면 전체를 덮고, 끝난 뒤에는 결과만 잠깐 보여 준다. */
@Composable
private fun FallAlertOverlay(state: FallAlertState, onPatientOk: () -> Unit) {
    when (state) {
        FallAlertState.Idle -> Unit
        is FallAlertState.Countdown -> Column(
            Modifier.fillMaxSize().background(CGColor.Critical).statusBarsPadding().navigationBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("낙상이 감지됐어요", style = CGType.Title.copy(fontSize = 30.sp, lineHeight = 38.sp), color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                "괜찮으시면 아래 버튼을 눌러주세요.\n누르지 않으면 보호자에게 알리고 현장 영상을 보냅니다.",
                style = CGType.Body.copy(fontSize = 17.sp, lineHeight = 26.sp),
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Text("${state.secondsLeft}", style = CGType.Title.copy(fontSize = 96.sp, lineHeight = 104.sp), color = Color.White)
            Spacer(Modifier.height(40.dp))
            CGButton("괜찮아요 (알리지 않기)", onClick = onPatientOk, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, large = true)
        }
        is FallAlertState.PatientOk -> when (state.delivery) {
            CancelDelivery.SENDING -> FallAlertBanner("괜찮다는 응답을 보내는 중이에요…", CGColor.Safe)
            CancelDelivery.SENT -> FallAlertBanner("보호자에게 알리지 않았어요. 몸 상태를 다시 확인해주세요.", CGColor.Safe)
            CancelDelivery.FAILED -> FallAlertBanner("서버에 응답을 보내지 못해 보호자에게 경보가 갈 수 있어요. 보호자에게 직접 연락해주세요.", CGColor.Critical)
        }
        is FallAlertState.Escalated -> FallAlertBanner(
            when (state.clip) {
                ClipDelivery.PREPARING -> "보호자에게 알렸어요. 현장 영상을 보내는 중입니다."
                ClipDelivery.UPLOADED -> "보호자에게 알리고 현장 영상을 보냈어요."
                ClipDelivery.NOT_AVAILABLE -> "보호자에게 알렸어요. 영상 설정 또는 사람 인식 상태 때문에 현장 영상은 만들지 않았어요."
                ClipDelivery.FAILED -> "현장 영상을 보내지 못했어요. 서버 연결이 불안정하면 보호자 알림도 늦을 수 있어요."
            },
            CGColor.Critical
        )
    }
}

@Composable
private fun FallAlertBanner(message: String, color: Color) {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            message,
            style = CGType.BodyBold.copy(fontSize = 15.sp),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color).padding(16.dp)
        )
    }
}

private fun com.example.careguardcamera.data.CameraAppState.privacyModeOrDefault(): com.example.careguardcamera.data.PrivacyMode =
    privacyMode

private fun com.example.careguardcamera.video.LiveCameraSessionState.liveMessage(): String = when (status) {
    LiveSessionStatus.IDLE -> "프리뷰와 단일 캡처 세션을 준비하고 있습니다."
    LiveSessionStatus.NO_VIEWER -> "시청자가 없어도 로컬 감지는 계속 실행됩니다."
    LiveSessionStatus.NEGOTIATING -> "보호자 앱과 영상 연결을 협상하고 있습니다."
    LiveSessionStatus.CONNECTED -> "보호자 앱에 영상을 전송 중입니다. 오디오는 전송하지 않습니다."
    LiveSessionStatus.RETRYING -> "연결을 다시 확인하고 있습니다."
    LiveSessionStatus.PRIVACY_MODE_UNSUPPORTED -> "현재 개인정보 모드에서는 원본 영상 송출을 차단하고 감지만 유지합니다."
    LiveSessionStatus.MODEL_INCOMPATIBLE -> "모델 호환성 확인이 필요합니다."
    LiveSessionStatus.EVENT_UPLOAD_FAILED -> "감지 이벤트 전송에 실패했습니다."
    LiveSessionStatus.STOPPED -> "화면이 내려가면 캡처와 전송을 종료합니다."
}

private fun LiveSessionStatus.label(): String = when (this) {
    LiveSessionStatus.IDLE -> "대기"
    LiveSessionStatus.NEGOTIATING -> "연결 중"
    LiveSessionStatus.CONNECTED -> "영상 연결"
    LiveSessionStatus.NO_VIEWER -> "시청자 없음"
    LiveSessionStatus.RETRYING -> "재시도"
    LiveSessionStatus.PRIVACY_MODE_UNSUPPORTED -> "송출 차단"
    LiveSessionStatus.MODEL_INCOMPATIBLE -> "모델 확인"
    LiveSessionStatus.EVENT_UPLOAD_FAILED -> "전송 실패"
    LiveSessionStatus.STOPPED -> "중지"
}

private fun LiveSessionStatus.statusColor(): Color = when (this) {
    LiveSessionStatus.CONNECTED -> CGColor.Safe
    LiveSessionStatus.NO_VIEWER, LiveSessionStatus.IDLE, LiveSessionStatus.STOPPED -> Color(0x33000000)
    LiveSessionStatus.NEGOTIATING, LiveSessionStatus.RETRYING -> CGColor.Warning
    else -> CGColor.Critical
}

private fun String.label(): String = when (this) {
    "ONLINE" -> "온라인"
    "OFFLINE" -> "오프라인"
    "UNPAIRED" -> "페어링 대기"
    else -> "확인 중"
}

private fun String.statusColor(): Color = when (this) {
    "ONLINE" -> CGColor.Safe
    "OFFLINE" -> CGColor.Warning
    "UNPAIRED" -> Color(0x33FFFFFF)
    else -> Color(0x33000000)
}
