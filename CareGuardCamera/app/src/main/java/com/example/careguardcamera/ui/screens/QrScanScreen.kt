package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.careguardcamera.data.Severity
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.HairLine
import com.example.careguardcamera.ui.components.StatusPill
import com.example.careguardcamera.ui.components.TrustNote
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.launch

@Composable
fun QrScanScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    allowSkip: Boolean = true
) {
    var cameraName by rememberSaveable { mutableStateOf("거실 카메라") }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var registeredName by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val loading by BackendRepository.loading.collectAsState()
    val scope = rememberCoroutineScope()

    fun register() {
        val trimmedName = cameraName.trim()
        val trimmedCode = pairingCode.trim()
        when {
            trimmedName.isBlank() -> error = "카메라 이름을 입력해주세요."
            !Regex("[0-9]{8}").matches(trimmedCode) -> error = "카메라 앱에 표시된 숫자 8자리를 입력해주세요."
            else -> {
                scope.launch {
                    if (saving) return@launch
                    saving = true
                    try {
                        val result = BackendRepository.connectCamera(trimmedCode, trimmedName)
                        if (result == null) {
                            registeredName = trimmedName
                            error = null
                        } else {
                            registeredName = null
                            error = result
                        }
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(CGColor.Paper).statusBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            CGTopBar(title = if (allowSkip) "카메라 연결 · 3/3" else "카메라 연결", onBack = onBack, centerTitle = true)
            Spacer(Modifier.height(12.dp))
            Text("카메라 코드를\n입력해주세요", style = CGType.Headline.copy(fontSize = 26.sp, lineHeight = 34.sp), color = CGColor.Ink)
            Spacer(Modifier.height(8.dp))
            Text("환자 공간의 카메라 앱에 표시된 8자리 코드를 보호자 앱에 입력하면 서버에서 연결을 확정합니다.", style = CGType.Label, color = CGColor.Ink2)
            Spacer(Modifier.height(16.dp))
            TrustNote("코드는 한 번만 사용할 수 있고 만료될 수 있어요. 실패해도 입력한 코드와 이름은 유지됩니다.", Icons.Outlined.Info)
            Spacer(Modifier.height(16.dp))
            CodeGuideCard()
            Spacer(Modifier.height(16.dp))
            SetupCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(CGColor.BrandSoft), contentAlignment = Alignment.Center) {
                        Glyph(Glyphs.Cam, CGColor.BrandInk, 20.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("카메라 연결", style = CGType.BodyBold.copy(fontSize = 15.sp), color = CGColor.Ink)
                        Text("카메라 앱의 연결 코드와 표시 이름을 입력합니다.", style = CGType.Caption, color = CGColor.Ink3)
                    }
                }
                Spacer(Modifier.height(14.dp))
                FieldLabel("연결 코드")
                CGTextField(pairingCode, {
                    pairingCode = it.filter { ch -> ch.isDigit() }.take(8)
                    registeredName = null
                    error = null
                }, placeholder = "00000000")
                Spacer(Modifier.height(12.dp))
                FieldLabel("카메라 이름")
                CGTextField(cameraName, {
                    cameraName = it
                    registeredName = null
                    error = null
                }, placeholder = "거실 카메라")
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error.orEmpty(), style = CGType.Caption, color = CGColor.Critical)
                }
                if (registeredName != null) {
                    Spacer(Modifier.height(12.dp))
                    HairLine()
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("등록 완료", Severity.SAFE)
                        Spacer(Modifier.width(8.dp))
                        Text("${registeredName} 연결이 완료됐습니다.", style = CGType.Caption, color = CGColor.Ink2)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(CGColor.Paper)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CGButton(
                text = if (registeredName == null) "카메라 연결하기" else "완료",
                onClick = { if (registeredName == null) register() else onDone() },
                kind = ButtonKind.Brand,
                large = true,
                enabled = !saving && !loading,
                modifier = Modifier.fillMaxWidth()
            )
            if (allowSkip) {
                CGButton("카메라를 나중에 연결할게요", onClick = onDone, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
            }
        }
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

@Composable
private fun CodeGuideCard() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CGColor.BrandSoft)
            .border(1.dp, CGColor.Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(CGColor.Paper), contentAlignment = Alignment.Center) {
            Glyph(Glyphs.Cam, CGColor.BrandInk, 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("카메라 앱의 8자리 코드", style = CGType.BodyBold.copy(fontSize = 15.sp), color = CGColor.Ink)
            Text("환자 공간의 카메라 폰에서 코드를 발급한 뒤 이 화면에 입력하세요.", style = CGType.Caption, color = CGColor.Ink2)
        }
    }
}
