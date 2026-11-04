package com.example.careguardcamera.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.Condition
import com.example.careguardcamera.data.RecordCategory
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGChip
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.GroupLabel
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordDetailScreen(recordId: String, onBack: () -> Unit, onAddNote: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val records by BackendRepository.records.collectAsState()
    val notesByTarget by BackendRepository.notes.collectAsState()
    val record = records.firstOrNull { it.id == recordId }
    val notes = notesByTarget[recordId].orEmpty()

    var editing by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable(record?.id) { mutableStateOf(record?.category ?: RecordCategory.SYMPTOM) }
    var condition by rememberSaveable(record?.id) { mutableStateOf(record?.condition) }
    var memo by rememberSaveable(record?.id) { mutableStateOf(record?.memo.orEmpty()) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp)
    ) {
        CGTopBar(title = "기록 상세", onBack = onBack)
        if (record == null) {
            NotFoundBlock("기록을 찾을 수 없어요", onBack)
            return@Column
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(22.dp)).background(CGColor.Paper)
                .border(1.dp, CGColor.BrandSoft, RoundedCornerShape(22.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("${record.dateLabel} · ${record.time}", style = CGType.Caption, color = CGColor.BrandInk)
            Text(record.title, style = CGType.Headline.copy(fontSize = 24.sp), color = CGColor.Ink)
            Text("${record.category.label} · ${record.author}", style = CGType.Label, color = CGColor.Ink2)
            if (!editing) {
                Text(record.memo, style = CGType.Body, color = CGColor.Ink)
                record.condition?.let { Text("상태: ${it.label}", style = CGType.Caption, color = CGColor.BrandInk) }
            }
        }

        if (editing) {
            DetailSectionTitle("기록 수정")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CGColor.Paper).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("분류", style = CGType.LabelBold, color = CGColor.BrandInk)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordCategory.entries.forEach { c -> CGChip(c.label, category == c, { category = c }) }
                }
                GroupLabel("오늘 상태")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Condition.entries.forEach { c ->
                        CGChip(c.label, condition == c, { condition = if (condition == c) null else c }, critical = c == Condition.FALL)
                    }
                }
                GroupLabel("메모")
                CGTextField(memo, { memo = it }, placeholder = "메모", singleLine = false, minHeight = 110.dp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CGButton("취소", onClick = {
                        category = record.category
                        condition = record.condition
                        memo = record.memo
                        editing = false
                    }, kind = ButtonKind.Secondary, enabled = !saving, modifier = Modifier.weight(1f))
                    CGButton(
                        if (saving) "저장 중" else "저장",
                        onClick = {
                            if (memo.isBlank()) {
                                Toast.makeText(context, "메모를 입력해주세요", Toast.LENGTH_SHORT).show()
                                return@CGButton
                            }
                            scope.launch {
                                saving = true
                                val error = BackendRepository.updateRecord(record.id, category, condition, memo)
                                saving = false
                                if (error == null) editing = false else Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        },
                        kind = ButtonKind.Brand,
                        enabled = !saving,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        DetailSectionTitle("대응 내역 · ${notes.size}건")
        NoteList(targetId = recordId, notes = notes)
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CGButton(if (editing) "수정 중" else "수정", onClick = { editing = true }, enabled = !editing, modifier = Modifier.weight(1f), kind = ButtonKind.Secondary)
            CGButton("대응 메모 추가", onClick = onAddNote, modifier = Modifier.weight(1f), kind = ButtonKind.Brand)
        }
        CGButton("기록 삭제", onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp), kind = ButtonKind.Red)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("기록을 삭제할까요?", style = CGType.Heading, color = CGColor.Ink) },
            text = { Text("삭제하면 이 기록 상세와 연결된 대응 메모를 더 이상 볼 수 없습니다.", style = CGType.Body, color = CGColor.Ink2) },
            confirmButton = {
                CGButton("삭제", kind = ButtonKind.Red, onClick = {
                    scope.launch {
                        val error = BackendRepository.deleteRecord(recordId)
                        if (error == null) {
                            confirmDelete = false
                            onBack()
                        } else {
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            confirmDelete = false
                        }
                    }
                })
            },
            dismissButton = { CGButton("취소", kind = ButtonKind.Secondary, onClick = { confirmDelete = false }) },
            containerColor = CGColor.Paper
        )
    }
}
