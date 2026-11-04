package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.ui.components.*
import com.example.careguardcamera.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ServerSettingsScreen(onBack: () -> Unit, onChanged: () -> Unit, onSelected: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(BackendRepository.serverUrl) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val patients by BackendRepository.availablePatients.collectAsState()
    var connected by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CGTopBar(title = "서버 연결", onBack = onBack)
        Text("데이터를 저장할 서버 주소", style = CGType.Heading, color = CGColor.BrandInk)
        CGTextField(url, { url = it }, placeholder = "http://10.0.2.2:8080", keyboardType = KeyboardType.Uri)
        Text("에뮬레이터는 http://10.0.2.2:8080을 사용합니다. 실제 휴대폰은 연결 가능한 개발 서버 주소를 입력해주세요.", style = CGType.Body, color = CGColor.Ink2)
        TrustNote("서버를 변경하면 이 앱의 환자 선택과 온보딩 상태가 초기화됩니다. 기존 서버의 데이터는 삭제되지 않습니다. 현재는 로그인 없는 개발용 연결입니다.")
        error?.let { Text(it, color = CGColor.Critical, style = CGType.Caption) }
        CGButton(if (saving) "연결 중…" else "저장 및 연결", onClick = {
            saving = true
            scope.launch {
                error = BackendRepository.configureServer(url)
                if (error == null) {
                    connected = BackendRepository.loadPatients() != null
                    error = BackendRepository.error.value
                }
                saving = false
            }
        }, enabled = !saving, kind = ButtonKind.Brand, modifier = Modifier.fillMaxWidth())
        if (connected) {
            Text("서버의 환자 선택", style = CGType.LabelBold, color = CGColor.BrandInk)
            patients.forEach { patient ->
                CGButton("${patient.name} · ${patient.age}세", onClick = {
                    saving = true
                    scope.launch {
                        error = BackendRepository.selectPatient(patient.id)
                        saving = false
                        if (error == null) onSelected()
                    }
                }, enabled = !saving, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
            }
            CGButton("새 환자 등록", onClick = {
                saving = true
                scope.launch {
                    error = BackendRepository.startNewPatient()
                    saving = false
                    if (error == null) onChanged()
                }
            }, enabled = !saving, kind = ButtonKind.Brand, modifier = Modifier.fillMaxWidth())
        }
    }
}
