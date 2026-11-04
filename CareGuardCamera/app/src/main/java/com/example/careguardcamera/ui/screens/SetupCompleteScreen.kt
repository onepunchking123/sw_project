package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.ui.components.*
import com.example.careguardcamera.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SetupCompleteScreen(onDone: () -> Unit) {
    val patient by BackendRepository.patient.collectAsState()
    val contacts by BackendRepository.contacts.collectAsState()
    val cameras by BackendRepository.cameras.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        CGTopBar(title = "설정 완료")
        Text("${patient.name}님의 돌봄 준비를 마쳤어요", style = CGType.Heading, color = CGColor.BrandInk)
        Text("비상 연락처 ${contacts.size}명 · 등록 카메라 ${cameras.size}대", style = CGType.Body)
        if (cameras.isEmpty()) Text("카메라는 설정 → 카메라 기기에서 나중에 등록할 수 있어요.", style = CGType.Body)
        TrustNote("입력한 정보는 서버에 저장됩니다. 현재 카메라 등록은 실제 영상 전송이나 알림 시작을 의미하지 않습니다.")
        Spacer(Modifier.weight(1f))
        error?.let { Text(it, color = CGColor.Critical) }
        CGButton("시작하기", onClick = {
            scope.launch {
                saving = true
                error = BackendRepository.finishOnboarding()
                saving = false
                if (error == null) onDone()
            }
        }, kind = ButtonKind.Brand, large = true, enabled = !saving && !loading, modifier = Modifier.fillMaxWidth())
    }
}
