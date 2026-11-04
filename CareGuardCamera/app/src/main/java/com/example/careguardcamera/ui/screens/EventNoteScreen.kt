package com.example.careguardcamera.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CareEvent
import com.example.careguardcamera.data.CareRecord
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGChip
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.GroupLabel
import com.example.careguardcamera.ui.components.TrustNote
import com.example.careguardcamera.ui.glyph.Glyph
import com.example.careguardcamera.ui.glyph.Glyphs
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.launch

private enum class NoteKind(val code: String, val label: String) {
    CALL("CALL", "전화로 확인"),
    VISIT("VISIT", "직접 방문"),
    HOSPITAL("HOSPITAL", "병원 이동"),
    OBSERVE("OBSERVE", "상태 설명"),
    ETC("ETC", "기타")
}

private enum class PatientState(val code: String, val label: String, val critical: Boolean = false) {
    FINE("FINE", "괜찮음"),
    PAIN("PAIN", "통증 호소", true),
    SLEEPY("SLEEPY", "기력 저하"),
    UNSURE("UNSURE", "확인 필요")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventNoteScreen(eventId: String, onBack: () -> Unit, manual: Boolean = false) {
    val events by BackendRepository.events.collectAsState()
    val records by BackendRepository.records.collectAsState()
    val event = if (manual) null else events.firstOrNull { it.id == eventId }
    val record = if (manual) records.firstOrNull { it.id == eventId } else null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by rememberSaveable { mutableStateOf(NoteKind.CALL) }
    var state by rememberSaveable { mutableStateOf<PatientState?>(PatientState.FINE) }
    var memo by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }

    if (event == null && record == null) {
        Column(Modifier.fillMaxSize().background(CGColor.Paper).statusBarsPadding().padding(horizontal = 20.dp)) {
            CGTopBar(title = "대응 메모 추가", onBack = onBack, centerTitle = true)
            NotFoundBlock(if (manual) "기록을 찾을 수 없어요" else "감지 기록을 찾을 수 없어요", onBack)
        }
        return
    }

    Column(Modifier.fillMaxSize().background(CGColor.Paper).statusBarsPadding().imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CGTopBar(title = "대응 메모 추가", onBack = onBack, centerTitle = true)
            Text(
                if (manual) "직접 기록한 이상 상황에 대한 대응을 남겨 주세요" else "감지 상황에 대한 대응을 남겨 주세요",
                style = CGType.Caption,
                color = CGColor.Ink2
            )

            when {
                event != null -> TargetCard(event)
                record != null -> TargetCard(record)
            }

            GroupLabel("어떻게 대응했나요")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteKind.entries.forEach { k -> CGChip(k.label, kind == k, { kind = k }) }
            }

            GroupLabel("환자 상태")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PatientState.entries.forEach { s ->
                    CGChip(s.label, state == s, { state = if (state == s) null else s }, critical = s.critical)
                }
            }

            GroupLabel("메모")
            CGTextField(
                memo,
                { memo = it },
                placeholder = "예) 전화해 보니 혼자 일어나셨고, 허리는 괜찮다고 하심",
                singleLine = false,
                minHeight = 120.dp
            )
            Spacer(Modifier.height(8.dp))
            Text("환자가 한 말, 다친 곳, 다음에 확인할 것 등을 적어 두면 좋아요", style = CGType.Caption, color = CGColor.Ink3)

            Spacer(Modifier.height(16.dp))
            TrustNote("메모는 선택한 상황 상세에만 붙습니다")
            Spacer(Modifier.height(16.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CGButton("취소", onClick = onBack, kind = ButtonKind.Secondary, large = true, enabled = !saving, modifier = Modifier.weight(1f))
            CGButton(
                if (saving) "저장 중" else "저장",
                onClick = {
                    if (memo.isBlank()) {
                        Toast.makeText(context, "메모를 입력해주세요", Toast.LENGTH_SHORT).show()
                        return@CGButton
                    }
                    scope.launch {
                        saving = true
                        val error = BackendRepository.addNote(
                            targetId = eventId,
                            manual = manual,
                            kind = kind.code,
                            patientState = state?.code,
                            memo = memo
                        )
                        saving = false
                        if (error == null) {
                            Toast.makeText(context, "대응 메모를 저장했어요", Toast.LENGTH_SHORT).show()
                            onBack()
                        } else {
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                kind = ButtonKind.Brand,
                large = true,
                enabled = !saving,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TargetCard(event: CareEvent) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CGColor.Surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(Glyphs.Lie, CGColor.Critical, 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${event.time} ${event.title}", style = CGType.BodyBold, color = CGColor.Ink)
            Text(event.detail ?: "AI 감지 기록", style = CGType.Caption, color = CGColor.Ink2)
        }
    }
}

@Composable
private fun TargetCard(record: CareRecord) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CGColor.Surface).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(Glyphs.Sit, CGColor.Brand, 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${record.time} ${record.title}", style = CGType.BodyBold, color = CGColor.Ink)
            Text("${record.dateLabel} · ${record.category.label}", style = CGType.Caption, color = CGColor.Ink2)
        }
    }
}
