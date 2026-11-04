package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.ui.components.Avatar
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGChip
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.HairLine
import com.example.careguardcamera.ui.components.TrustNote
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.launch

@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()
        .then(if (bottomBar == null) Modifier.navigationBarsPadding() else Modifier)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            CGTopBar(title = title, onBack = onBack)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        if (bottomBar != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(CGColor.Surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                bottomBar()
            }
        }
    }
}

@Composable
fun PatientEditScreen(onBack: () -> Unit) {
    val patient by BackendRepository.patient.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(patient.name) }
    var age by rememberSaveable { mutableStateOf(patient.age.toString()) }
    var sex by rememberSaveable { mutableStateOf(patient.sex) }
    var address by rememberSaveable { mutableStateOf(patient.address) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }

    SettingsPage(
        title = "환자 정보 수정",
        onBack = onBack,
        bottomBar = {
            CGButton(
                "저장",
                onClick = {
                    val parsedAge = age.trim().toIntOrNull()
                    when {
                        name.isBlank() -> error = "이름을 입력해주세요."
                        parsedAge == null || parsedAge !in 0..130 -> error = "나이는 0~130 사이 숫자로 입력해주세요."
                        else -> {
                            scope.launch {
                                saving = true
                                error = BackendRepository.savePatient(name, parsedAge.toString(), sex, address)
                                saving = false
                                if (error == null) onBack()
                            }
                        }
                    }
                },
                kind = ButtonKind.Brand,
                large = true,
                enabled = !saving && !loading,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        SettingsCard {
            FieldLabel("이름")
            CGTextField(name, { name = it }, placeholder = "환자 이름")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("나이")
                    CGTextField(age, { age = it }, placeholder = "85", keyboardType = KeyboardType.Number)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("성별")
                    CGTextField(sex, { sex = it }, placeholder = "남성")
                }
            }
            Spacer(Modifier.height(14.dp))
            FieldLabel("주소")
            CGTextField(address, { address = it }, placeholder = "응급 대응 주소", singleLine = false, minHeight = 88.dp)
            ErrorText(error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacySettingsScreen(onBack: () -> Unit) {
    val cameras by BackendRepository.cameras.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val scope = rememberCoroutineScope()
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsPage(
        title = "영상 공개 범위",
        onBack = onBack,
        bottomBar = {
            CGButton("완료", onClick = onBack, kind = ButtonKind.Brand, large = true, modifier = Modifier.fillMaxWidth())
        }
    ) {
        TrustNote("카메라별 영상 표시 방식을 선택하세요. 변경 내용은 서버에 저장된 뒤 화면에 반영됩니다.")
        ErrorText(error)
        Spacer(Modifier.height(12.dp))
        if (loading) {
            SettingsCard {
                Text("카메라 설정을 불러오는 중입니다.", style = CGType.Body, color = CGColor.Ink2)
            }
        } else if (cameras.isEmpty()) {
            SettingsCard {
                Text("등록된 카메라가 없습니다.", style = CGType.Body, color = CGColor.Ink2)
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cameras.forEach { camera ->
                SettingsCard {
                    Text(camera.name, style = CGType.BodyBold, color = CGColor.Ink)
                    Text(camera.modelLabel, style = CGType.Caption, color = CGColor.Ink3)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrivacyMode.entries.forEach { mode ->
                            CGChip(
                                text = mode.label,
                                selected = camera.privacyMode == mode,
                                onClick = {
                                    scope.launch {
                                        error = BackendRepository.setPrivacyMode(camera.id, mode)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StillAlertSettingsScreen(onBack: () -> Unit) {
    val notifications by BackendRepository.notifications.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(notifications.stillMinutes) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }

    SettingsPage(
        title = "미동 알림",
        onBack = onBack,
        bottomBar = {
            CGButton(
                "저장",
                onClick = {
                    scope.launch {
                        saving = true
                        error = BackendRepository.setStillMinutes(selected)
                        saving = false
                        if (error == null) onBack()
                    }
                },
                kind = ButtonKind.Brand,
                large = true,
                enabled = !saving && !loading,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        SettingsCard {
            Text("움직임 없음 알림의 기준 시간을 선택하세요. 설정은 서버에 저장됩니다.", style = CGType.Body, color = CGColor.Ink2)
            ErrorText(error)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StillChoice("꺼짐", selected == null) { selected = null }
                listOf(3, 5, 10).forEach { minutes ->
                    StillChoice("${minutes}분", selected == minutes) { selected = minutes }
                }
            }
        }
    }
}

@Composable
fun DataUsageScreen(onBack: () -> Unit) {
    SettingsPage(title = "데이터 활용 안내", onBack = onBack) {
        SettingsCard {
            Text("환자 정보·비상 연락처·등록 기기는 서버에 저장됩니다.", style = CGType.BodyBold, color = CGColor.Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "감지 기록과 보호자 기록은 백엔드 CRUD API 응답을 기준으로 표시합니다. 실제 카메라 페어링·영상 전송·푸시 발송은 아직 별도 구현 범위입니다.",
                style = CGType.Body.copy(lineHeight = 23.sp),
                color = CGColor.Ink2
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsCard {
            InfoLine("환자/보호자 정보", "백엔드 CRUD API")
            InfoLine("감지/기록", "백엔드 타임라인 API")
            InfoLine("알림", "설정 저장만 지원")
        }
    }
}

@Composable
fun AppInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }

    SettingsPage(title = "앱 정보", onBack = onBack) {
        SettingsCard {
            Text("CareGuard Camera", style = CGType.Heading, color = CGColor.Ink)
            Spacer(Modifier.height(8.dp))
            Text("환자 상태 확인, 감지 타임라인, 보호자 기록 작성을 한곳에서 확인하는 보호자용 데모 앱입니다.", style = CGType.Body.copy(lineHeight = 23.sp), color = CGColor.Ink2)
            Spacer(Modifier.height(14.dp))
            InfoLine("패키지", context.packageName)
            InfoLine("버전", versionName.ifBlank { "정보 없음" })
            InfoLine("데이터", "메모리 샘플 기반 데모")
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CGColor.Paper)
            .padding(16.dp),
        content = content
    )
}


@Composable
private fun FieldLabel(text: String) {
    Text(text, style = CGType.Label, color = CGColor.Ink2, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(10.dp))
        Text(error, style = CGType.Caption, color = CGColor.Critical)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = CGType.Caption, color = CGColor.Ink3, modifier = Modifier.weight(0.9f))
        Text(value, style = CGType.Caption.copy(fontWeight = FontWeight.SemiBold), color = CGColor.Ink, modifier = Modifier.weight(1.4f))
    }
}



@Composable
private fun StillChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    CGChip(text = text, selected = selected, onClick = onClick)
}
