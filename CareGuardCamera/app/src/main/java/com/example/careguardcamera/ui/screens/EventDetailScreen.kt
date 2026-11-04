package com.example.careguardcamera.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CareEvent
import com.example.careguardcamera.data.ResponseNote
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.launch

@Composable
fun EventDetailScreen(eventId: String, onBack: () -> Unit, onAddNote: () -> Unit = {}) {
    val events by BackendRepository.events.collectAsState()
    val notesByTarget by BackendRepository.notes.collectAsState()
    val event = events.firstOrNull { it.id == eventId }
    val notes = notesByTarget[eventId].orEmpty()

    Column(
        Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)
    ) {
        CGTopBar(title = "감지 상세", onBack = onBack)
        if (event == null) {
            NotFoundBlock("감지 기록을 찾을 수 없어요", onBack)
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        EventHero(event)
        Spacer(Modifier.height(16.dp))
        VideoUnavailable()
        DetailSectionTitle("AI 판정")
        AiJudgement(event)
        DetailSectionTitle("대응 내역 · ${notes.size}건")
        NoteList(targetId = eventId, notes = notes)
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CGButton("대응 메모 추가", onClick = onAddNote, modifier = Modifier.weight(1f), kind = ButtonKind.Brand)
        }
    }
}

@Composable
private fun EventHero(event: CareEvent) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(CGColor.BrandSoft).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(event.detail ?: "AI 감지 상황", style = CGType.Caption, color = CGColor.BrandInk)
        Text("${event.time} ${event.title}", style = CGType.Headline, color = CGColor.Critical)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "기록됨",
                style = CGType.Caption.copy(fontWeight = FontWeight.Bold),
                color = CGColor.BrandInk,
                modifier = Modifier.clip(CircleShape).background(CGColor.Paper).padding(horizontal = 10.dp, vertical = 5.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(event.responder?.let { "$it 님 대응" } ?: "대응 상태를 확인 중", style = CGType.Label, color = CGColor.Ink2, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun VideoUnavailable() {
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(18.dp)).background(CGColor.Ink2)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.VideocamOff, "영상 없음", tint = CGColor.Brand, modifier = Modifier.size(28.dp))
                }
                Text("저장된 영상은 아직 제공되지 않아요", style = CGType.Caption, color = Color.White)
            }
        }
    }
}

@Composable
private fun AiJudgement(event: CareEvent) {
    val cells = listOf(
        Triple("낙상 확률", "%.2f".format(event.probFall), false),
        Triple("방치 확률", "%.2f".format(event.probStill), false),
        Triple("미동 지속", "%.1f초".format(event.stillSec), false),
        Triple("환자 응답", if (event.patientResponded == true) "있음" else "없음", event.patientResponded != true)
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, value, crit) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (crit) CGColor.CriticalSoft else CGColor.Paper)
                            .border(1.dp, if (crit) CGColor.CriticalSoft else CGColor.BrandSoft, RoundedCornerShape(16.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(label, style = CGType.Caption, color = CGColor.Ink2)
                        Text(value, style = CGType.Heading, color = if (crit) CGColor.Critical else CGColor.BrandInk)
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoteList(targetId: String, notes: List<ResponseNote>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val loading by BackendRepository.loading.collectAsState()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CGColor.Paper)
            .border(1.dp, CGColor.BrandSoft, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp)
    ) {
        if (notes.isEmpty()) {
            Text("아직 남겨진 대응 메모가 없어요", style = CGType.Label, color = CGColor.Ink2, modifier = Modifier.padding(vertical = 20.dp))
        }
        notes.forEachIndexed { index, note ->
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(CGColor.Brand))
                    Spacer(Modifier.width(7.dp))
                    Text(note.time, style = CGType.LabelBold, color = CGColor.BrandInk, modifier = Modifier.weight(1f))
                    if (note.id.isNotBlank()) {
                        Text("수정", color = CGColor.BrandInk, modifier = Modifier.padding(8.dp).clickable { editing = note.id; draft = note.detail.orEmpty() })
                        Text(
                            "삭제",
                            style = CGType.Caption,
                            color = CGColor.Critical,
                            modifier = Modifier.clip(CircleShape).background(CGColor.CriticalSoft)
                                .padding(horizontal = 9.dp, vertical = 5.dp)
                                .clickable { deleting = note.id }
                        )
                    }
                }
                Text(note.title, style = CGType.BodyBold, color = CGColor.Ink)
                note.patientState?.let { Text("환자 상태: $it", style = CGType.Caption, color = CGColor.BrandInk) }
                note.detail?.let { Text(it, style = CGType.Caption, color = CGColor.Ink2) }
            }
            if (index < notes.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(CGColor.BrandSoft))
        }
    }

    editing?.let { noteId ->
        val note = notes.firstOrNull { it.id == noteId }
        if (note != null) AlertDialog(
            onDismissRequest = { if (!loading) editing = null },
            title = { Text("대응 메모 수정") },
            text = { OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("메모") }) },
            confirmButton = { CGButton("저장", enabled = !loading, onClick = { scope.launch {
                val error = BackendRepository.updateNote(note.id, note.kind, note.patientState, draft)
                if (error == null) editing = null else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            } }) },
            dismissButton = { CGButton("취소", enabled = !loading, onClick = { editing = null }) }
        )
    }

    deleting?.let { noteId ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("메모를 삭제할까요?", style = CGType.Heading, color = CGColor.Ink) },
            text = { Text("삭제한 대응 메모는 화면에서 제거됩니다.", style = CGType.Body, color = CGColor.Ink2) },
            confirmButton = {
                CGButton("삭제", kind = ButtonKind.Red, onClick = {
                    scope.launch {
                        val error = BackendRepository.deleteNote(noteId)
                        if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        deleting = null
                    }
                })
            },
            dismissButton = { CGButton("취소", kind = ButtonKind.Secondary, onClick = { deleting = null }) },
            containerColor = CGColor.Paper
        )
    }
}

@Composable
internal fun DetailSectionTitle(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(16.dp).clip(CircleShape).background(CGColor.Brand))
        Spacer(Modifier.width(8.dp))
        Text(title, style = CGType.LabelBold, color = CGColor.BrandInk)
    }
}

@Composable
internal fun NotFoundBlock(message: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(message, style = CGType.BodyBold, color = CGColor.Ink)
        CGButton("돌아가기", onClick = onBack, kind = ButtonKind.Brand)
    }
}
