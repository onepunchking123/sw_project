package com.example.careguardcamera.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.ui.components.BlinkDot
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.TrustNote
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun QrDisplayScreen(onBack: () -> Unit, onPaired: () -> Unit) {
    val state by CameraDeviceRepository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var retryKey by remember { mutableIntStateOf(0) }
    var forceReissue by remember { mutableStateOf(false) }
    var stoppedError by rememberSaveable { mutableStateOf<String?>(null) }
    var serverUrl by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var serverError by rememberSaveable { mutableStateOf<String?>(null) }
    var savingServer by remember { mutableStateOf(false) }
    val disconnected = state.disconnected

    LaunchedEffect(retryKey, stoppedError, lifecycleOwner) {
        if (stoppedError != null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                val countdown = launch {
                    while (true) {
                        CameraDeviceRepository.tickCountdown()
                        delay(1_000L)
                    }
                }
                fun stopWith(message: String) {
                    stoppedError = message
                    countdown.cancel()
                }

                CameraDeviceRepository.refreshSelfOrIdle()?.let {
                    stopWith(it)
                    return@coroutineScope
                }
                if (CameraDeviceRepository.state.value.disconnected) {
                    countdown.cancel()
                    return@coroutineScope
                }
                val latest = CameraDeviceRepository.state.value
                if (!latest.paired && forceReissue) {
                    CameraDeviceRepository.issuePairingCode()?.let {
                        stopWith(it)
                        return@coroutineScope
                    }
                    forceReissue = false
                }
                while (!CameraDeviceRepository.state.value.paired) {
                    delay(3_000L)
                    val paired = CameraDeviceRepository.pollSelf()
                    val error = CameraDeviceRepository.state.value.error
                    if (error != null) {
                        stopWith(error)
                        break
                    }
                    if (paired) break
                }
                countdown.cancel()
            }
        }
    }
    LaunchedEffect(state.paired) {
        if (state.paired) onPaired()
    }

    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            CGTopBar(title = "카메라 연결 · 1/2", onBack = onBack, centerTitle = true)
            Spacer(Modifier.height(12.dp))
            Text("보호자 폰에\n연결 코드를 입력해주세요", style = CGType.Headline.copy(fontSize = 26.sp, lineHeight = 34.sp), color = CGColor.Ink)
            Spacer(Modifier.height(8.dp))
            Text("보호자 앱의 카메라 연결 화면에서 아래 8자리 코드를 입력하면 서버에서 이 카메라와 환자를 연결합니다.", style = CGType.Label, color = CGColor.Ink2)
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x1F1B1D22), spotColor = Color(0x1F1B1D22))
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White)
                    .border(1.dp, CGColor.Line, RoundedCornerShape(22.dp))
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(CGColor.BrandSoft), contentAlignment = Alignment.Center) {
                    Glyph(Glyphs.Cam, CGColor.BrandInk, 34.dp)
                }
                Spacer(Modifier.height(18.dp))
                Text(if (state.loading && state.pairingCode == null) "연결 코드 발급 중" else "연결 코드", style = CGType.Caption, color = CGColor.Ink3)
                Spacer(Modifier.height(6.dp))
                Text(
                    state.pairingCode ?: "--------",
                    style = CGType.Headline.copy(fontSize = 40.sp, letterSpacing = 6.sp),
                    color = CGColor.Brand,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        state.secondsRemaining > 0 -> "${state.secondsRemaining}초 남음"
                        state.pairingCode != null -> "만료됨 · 새 코드를 발급해주세요"
                        else -> "연결 코드 발급을 눌러 시작하세요"
                    },
                    style = CGType.Caption,
                    color = CGColor.Ink3,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
            TrustNote(
                when {
                    disconnected -> "연결이 해제됐습니다. 현재 버전에서는 재연결을 지원하지 않습니다."
                    state.error != null -> state.error.orEmpty()
                    else -> "코드가 만료되면 새 코드 발급을 눌러 다시 시작하세요."
                },
                Icons.Outlined.Info
            )
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CGColor.Surface)
                    .border(1.dp, CGColor.Line, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text("서버 주소", style = CGType.LabelBold, color = CGColor.Ink2)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        serverError = null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                if (serverError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(serverError.orEmpty(), style = CGType.Caption, color = CGColor.Critical)
                }
                Spacer(Modifier.height(10.dp))
                CGButton(
                    if (savingServer) "저장 중…" else "서버 주소 저장",
                    onClick = {
                        scope.launch {
                            savingServer = true
                            try {
                                val result = CameraDeviceRepository.configureServer(serverUrl)
                                serverError = result
                                if (result == null) {
                                    stoppedError = null
                                    retryKey++
                                }
                            } finally {
                                savingServer = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                    enabled = !savingServer && !state.loading
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                BlinkDot(CGColor.Brand)
                Spacer(Modifier.width(8.dp))
                Text(
                    stoppedError ?: "보호자 앱의 코드 입력을 기다리는 중",
                    style = CGType.Label.copy(fontSize = 13.5.sp),
                    color = if (stoppedError == null) CGColor.Ink2 else CGColor.Critical
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Box(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp).navigationBarsPadding()) {
            if (!disconnected) {
                CGButton(
                    when {
                        state.loading -> "처리 중…"
                        stoppedError != null -> "다시 확인"
                        state.pairingCode == null -> "연결 코드 발급"
                        else -> "새 코드 발급"
                    },
                    onClick = {
                        val retryOnly = stoppedError != null
                        stoppedError = null
                        forceReissue = !retryOnly
                        retryKey++
                    },
                    modifier = Modifier.fillMaxWidth(),
                    kind = ButtonKind.Brand,
                    large = true,
                    enabled = !state.loading
                )
            }
        }
    }
}
