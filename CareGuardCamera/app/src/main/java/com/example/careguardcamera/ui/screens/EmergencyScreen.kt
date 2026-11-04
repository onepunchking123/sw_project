package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.example.careguardcamera.data.ApiMapping
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.ui.components.*
import com.example.careguardcamera.ui.theme.*

@Composable
fun EmergencyScreen(onBack: () -> Unit, onResolve: () -> Unit) {
    val patient by BackendRepository.patient.collectAsState()
    val contacts by BackendRepository.contacts.collectAsState()
    val caregivers by BackendRepository.caregivers.collectAsState()
    val state by BackendRepository.demo.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val incident by BackendRepository.activeIncident.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CGTopBar(title = "긴급 상황", onBack = onBack)
        Text(patient.name, style = CGType.Title, color = CGColor.Critical)
        Text(if (state.emergency) "종료되지 않은 긴급 상황입니다." else "진행 중인 긴급 상황이 없습니다.", style = CGType.Body)
        incident?.let { active ->
            Text(
                active.startedAt?.let { "낙상 감지 · ${ApiMapping.displayDateTime(it)} · 환자가 카메라 폰에서 응답하지 않았어요" }
                    ?: "환자가 카메라 폰에서 응답하지 않았어요",
                style = CGType.Label,
                color = CGColor.Critical
            )
            key(active.id) { IncidentClipPlayer(BackendRepository.incidentClipUrl(active)) }
        }
        Text(patient.address.ifBlank { "환자 주소가 등록되지 않았습니다." }, style = CGType.Body)
        caregivers.forEach { Text("${it.name} · ${when(it.responseStatus) {
            com.example.careguardcamera.data.ResponseStatus.RESPONDING -> "대응 중"
            com.example.careguardcamera.data.ResponseStatus.ACKNOWLEDGED -> "확인함"
            else -> "미확인"
        }}", style = CGType.Label) }
        CGButton("119 신고", { context.dial("119") }, kind = ButtonKind.Red, modifier = Modifier.fillMaxWidth())
        contacts.forEach { contact ->
            CGButton("${contact.name}에게 전화", { context.dial(contact.phone) }, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        }
        CGButton("내가 대응 중으로 표시", { scope.launch { BackendRepository.respondToIncident() } },
            enabled = !loading && state.emergency, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        CGButton("상황 종료 · 상태 확인함", onResolve, enabled = !loading && state.emergency,
            kind = ButtonKind.Brand, modifier = Modifier.fillMaxWidth())
    }
}
