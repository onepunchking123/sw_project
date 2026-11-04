package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGChip
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.launch

/** 화면 공용 입력 상자: 14dp 라운드, 1.5dp Line 테두리, 포커스 시 2dp Brand. */
@Composable
internal fun CGTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = CGType.Body.copy(color = CGColor.Ink),
        cursorBrush = SolidColor(CGColor.Brand),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight).background(CGColor.Paper, shape)
                    .border(if (focused) 2.dp else 1.5.dp, if (focused) CGColor.Brand else CGColor.Line, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
            ) {
                if (value.isEmpty()) Text(placeholder, style = CGType.Body, color = CGColor.Ink3)
                inner()
            }
        }
    )
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = CGType.Label, color = CGColor.Ink2, modifier = Modifier.padding(bottom = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatientInfoScreen(onNext: () -> Unit) {
    val savedPatient by BackendRepository.patient.collectAsState()
    val loading by BackendRepository.loading.collectAsState()
    val serverError by BackendRepository.error.collectAsState()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(savedPatient.name) { mutableStateOf(savedPatient.name) }
    var age by rememberSaveable(savedPatient.age) { mutableStateOf(if (savedPatient.age == 0) "" else savedPatient.age.toString()) }
    var sex by rememberSaveable(savedPatient.sex) { mutableStateOf(savedPatient.sex) }
    var address by rememberSaveable(savedPatient.address) { mutableStateOf(savedPatient.address) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(serverError) {
        if (serverError != null) error = serverError
    }

    Column(Modifier.fillMaxSize().background(CGColor.Paper).statusBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            CGTopBar(title = "환자 정보 · 1/3", centerTitle = true)
            Spacer(Modifier.height(12.dp))
            Text("환자 정보를\n입력해주세요", style = CGType.Headline.copy(fontSize = 26.sp, lineHeight = 34.sp), color = CGColor.Ink)
            Spacer(Modifier.height(8.dp))
            Text("이름, 나이, 성별, 주소를 입력하면 환자 프로필이 만들어집니다", style = CGType.Label, color = CGColor.Ink2)
            Spacer(Modifier.height(24.dp))

            FieldLabel("이름")
            CGTextField(name, { name = it; error = null }, placeholder = "환자 이름")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("나이")
                    CGTextField(age, { value -> age = value.filter { it.isDigit() }; error = null }, placeholder = "85", keyboardType = KeyboardType.Number)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("성별")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("남성", "여성").forEach { label ->
                            CGChip(label, sex == label, { sex = label; error = null })
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FieldLabel("응급 대응 주소")
            CGTextField(address, { address = it }, placeholder = "주소를 입력해주세요", singleLine = false, minHeight = 88.dp)
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error.orEmpty(), style = CGType.Caption, color = CGColor.Critical)
            }
            Spacer(Modifier.height(20.dp))
        }
        Box(Modifier.fillMaxWidth().background(CGColor.Paper).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            CGButton("다음", onClick = {
                scope.launch {
                    saving = true
                    val result = BackendRepository.savePatient(name, age, sex, address)
                    saving = false
                    if (result == null) onNext() else error = result
                }
            }, kind = ButtonKind.Brand, large = true, enabled = !saving && !loading, modifier = Modifier.fillMaxWidth())
        }
    }
}
