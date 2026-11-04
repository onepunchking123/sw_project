package com.example.careguardcamera.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.ApiMapping
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CareRecord
import com.example.careguardcamera.data.Condition
import com.example.careguardcamera.data.DayCondition
import com.example.careguardcamera.data.RecordCategory
import com.example.careguardcamera.ui.components.ButtonKind
import com.example.careguardcamera.ui.components.CGButton
import com.example.careguardcamera.ui.components.CGChip
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.GroupLabel
import com.example.careguardcamera.ui.components.HairLine
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch

private val seoulTz: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

private fun todaySeoul(): Calendar = GregorianCalendar(seoulTz, Locale.KOREAN)

private fun RecordCategory.dot(): Color = when (this) {
    RecordCategory.SYMPTOM -> CGColor.Cat1
    RecordCategory.MEDICATION -> CGColor.Cat2
    RecordCategory.DOSE -> CGColor.Cat3
    RecordCategory.ENVIRONMENT -> CGColor.Cat4
    RecordCategory.VISIT -> CGColor.Cat5
    RecordCategory.OTHER -> CGColor.Ink3
}

@Composable
private fun Dot(color: Color, size: Int = 9, ring: Color? = null) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color)
            .then(if (ring != null) Modifier.border(1.5.dp, ring, CircleShape) else Modifier)
    )
}

@Composable
fun RecordsScreen(onOpenRecord: (String) -> Unit = {}) {
    val repository = BackendRepository
    val records by repository.records.collectAsState()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val todayCal = remember { todaySeoul() }
    // 캘린더에서 고른 날짜. 기본은 오늘이며, 선택된 날짜를 다시 누르면 전체 기록을 본다.
    var selectedDate by rememberSaveable {
        mutableStateOf<String?>("%04d-%02d-%02d".format(todayCal.get(Calendar.YEAR), todayCal.get(Calendar.MONTH) + 1, todayCal.get(Calendar.DAY_OF_MONTH)))
    }
    val selectedParts = selectedDate?.split("-")?.map { it.toInt() }
    val selectedOffset = selectedParts?.let { (y, m, d) -> ApiMapping.dayOffset(y, m, d) }
    val visibleRecords = if (selectedOffset == null) records else records.filter { it.dayOffset == selectedOffset }
    val listTitle = selectedParts?.let { (y, m, d) -> ApiMapping.dayLabel(y, m, d) } ?: "전체 기록"

    Column(
        Modifier.fillMaxSize().background(CGColor.Paper).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        CGTopBar(title = "기록")
        Spacer(Modifier.height(8.dp))
        CGButton(
            "기록하기",
            onClick = { showSheet = true },
            kind = ButtonKind.Brand,
            large = true,
            icon = Icons.Outlined.Add,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        ConditionCalendar(
            selectedDate = selectedDate,
            onSelectDate = { date -> selectedDate = if (date == selectedDate) null else date }
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listTitle, style = CGType.BodyBold.copy(fontSize = 15.sp), color = CGColor.Ink, modifier = Modifier.weight(1f))
            Text("기록 ${visibleRecords.size}건", style = CGType.Caption.copy(fontSize = 12.5.sp), color = CGColor.Ink2)
        }
        if (selectedDate != null) {
            Text("날짜를 다시 누르면 전체 기록을 볼 수 있어요", style = CGType.Caption.copy(fontSize = 12.sp), color = CGColor.Ink3)
        }
        val groups = LinkedHashMap<String, MutableList<CareRecord>>()
        visibleRecords.forEach { groups.getOrPut(it.dateLabel) { mutableListOf() }.add(it) }
        if (groups.isEmpty()) {
            Text(
                if (selectedDate != null) "이 날에는 남긴 기록이 없어요" else "아직 직접 남긴 기록이 없어요",
                style = CGType.Body, color = CGColor.Ink2, modifier = Modifier.padding(vertical = 24.dp)
            )
        }
        groups.forEach { (label, list) ->
            if (selectedDate == null) GroupLabel(label)
            list.forEachIndexed { i, r ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpenRecord(r.id) }
                        .padding(vertical = 13.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(Modifier.padding(top = 8.dp)) { Dot(r.category.dot()) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = CGType.BodyBold, color = CGColor.Ink)
                        Text("${r.memo} · ${r.author}", style = CGType.Caption, color = CGColor.Ink2)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(r.time, style = CGType.Caption, color = CGColor.Ink3, modifier = Modifier.padding(top = 3.dp))
                }
                if (i < list.lastIndex) HairLine()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(RecordCategory.SYMPTOM, RecordCategory.MEDICATION, RecordCategory.DOSE, RecordCategory.ENVIRONMENT, RecordCategory.VISIT).forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(c.dot(), 8)
                    Spacer(Modifier.width(4.dp))
                    Text(c.label, style = CGType.Caption.copy(fontSize = 12.sp), color = CGColor.Ink3)
                }
            }
        }
    }
    if (showSheet) RecordSheet(onClose = { showSheet = false })
}

@Composable
private fun ConditionCalendar(selectedDate: String?, onSelectDate: (String) -> Unit) {
    val repository = BackendRepository
    val todayCal = remember { todaySeoul() }
    var year by rememberSaveable { mutableStateOf(todayCal.get(Calendar.YEAR)) }
    var month by rememberSaveable { mutableStateOf(todayCal.get(Calendar.MONTH) + 1) }
    val calendars by repository.calendars.collectAsState()
    val key = "%04d-%02d".format(year, month)
    val cal = calendars[key].orEmpty()
    val isThisMonth = year == todayCal.get(Calendar.YEAR) && month == todayCal.get(Calendar.MONTH) + 1
    val today = if (isThisMonth) todayCal.get(Calendar.DAY_OF_MONTH) else if (year < todayCal.get(Calendar.YEAR) || (year == todayCal.get(Calendar.YEAR) && month < todayCal.get(Calendar.MONTH) + 1)) 32 else 0
    val gc = GregorianCalendar(year, month - 1, 1)
    val daysInMonth = gc.getActualMaximum(Calendar.DAY_OF_MONTH)
    val leading = (gc.get(Calendar.DAY_OF_WEEK) + 5) % 7

    LaunchedEffect(year, month) { repository.loadCalendar(year, month) }

    fun prev() {
        if (month == 1) {
            year--
            month = 12
        } else month--
    }

    fun next() {
        if (!isThisMonth) {
            if (month == 12) {
                year++
                month = 1
            } else month++
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MonthArrow(Icons.Outlined.ChevronLeft, "이전 달", enabled = true, onClick = ::prev)
            Text(
                if (year == todayCal.get(Calendar.YEAR)) "${month}월" else "${year}년 ${month}월",
                style = CGType.BodyBold.copy(fontSize = 15.sp),
                color = CGColor.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(96.dp)
            )
            MonthArrow(Icons.Outlined.ChevronRight, "다음 달", enabled = !isThisMonth, onClick = ::next)
            if (!isThisMonth) {
                Text(
                    "오늘",
                    style = CGType.Caption,
                    color = CGColor.Brand,
                    modifier = Modifier.clip(CircleShape).clickable {
                        year = todayCal.get(Calendar.YEAR)
                        month = todayCal.get(Calendar.MONTH) + 1
                    }.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val legend = CGType.Caption.copy(fontSize = 11.5.sp)
            Text("빈도", style = legend, color = CGColor.Ink2)
            Spacer(Modifier.width(8.dp))
            Dot(CGColor.BrandSoft, 9, CGColor.Cat3); Spacer(Modifier.width(4.dp)); Text("낮음", style = legend, color = CGColor.Ink2)
            Spacer(Modifier.width(10.dp))
            Dot(CGColor.Cat3, 9); Spacer(Modifier.width(4.dp)); Text("보통", style = legend, color = CGColor.Ink2)
            Spacer(Modifier.width(10.dp))
            Dot(CGColor.BrandDeep, 9); Spacer(Modifier.width(4.dp)); Text("높음", style = legend, color = CGColor.Ink2)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("월", "화", "수", "목", "금", "토", "일").forEach {
                Text(it, style = CGType.Caption.copy(fontSize = 11.sp), color = CGColor.Ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        val cells: List<Int?> = List(leading) { null } + (1..daysInMonth).toList()
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (d != null) {
                            val dateKey = "%04d-%02d-%02d".format(year, month, d)
                            DayCell(
                                day = d,
                                cond = cal[d] ?: DayCondition.NONE,
                                today = today,
                                selected = dateKey == selectedDate,
                                onClick = { onSelectDate(dateKey) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(CGColor.Surface).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = if (enabled) CGColor.Ink else CGColor.Line, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DayCell(day: Int, cond: DayCondition, today: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val future = day > today
    val (bg, fg) = when {
        future -> Color.Transparent to CGColor.Line
        cond == DayCondition.LOW -> CGColor.BrandSoft to CGColor.BrandInk
        cond == DayCondition.MEDIUM -> CGColor.Cat3 to CGColor.BrandInk
        cond == DayCondition.HIGH -> CGColor.BrandDeep to Color.White
        else -> Color.Transparent to CGColor.Ink3
    }
    val level = when {
        future -> "예정 날짜"
        cond == DayCondition.LOW -> "빈도 낮음"
        cond == DayCondition.MEDIUM -> "빈도 보통"
        cond == DayCondition.HIGH -> "빈도 높음"
        else -> "기록 없음"
    }
    val outline = when {
        selected -> CGColor.Brand
        day == today -> CGColor.Ink
        else -> null
    }
    Box(
        Modifier.fillMaxSize().clip(shape).background(bg)
            .then(if (outline != null) Modifier.border(2.dp, outline, shape) else Modifier)
            .clickable(enabled = !future, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${day}일, $level${if (day == today) ", 오늘" else ""}${if (selected) ", 선택됨" else ""}"
            },
        contentAlignment = Alignment.Center
    ) {
        Text("$day", style = CGType.Caption.copy(fontWeight = if (!future && cond == DayCondition.HIGH) FontWeight.Bold else FontWeight.Normal), color = fg)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecordSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var category by rememberSaveable { mutableStateOf(RecordCategory.SYMPTOM) }
    var condition by rememberSaveable { mutableStateOf<Condition?>(Condition.DIZZY) }
    var memo by rememberSaveable { mutableStateOf("") }
    val now = remember { todaySeoul() }
    var dateText by rememberSaveable { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN).format(now.time)) }
    var timeText by rememberSaveable { mutableStateOf(SimpleDateFormat("HH:mm", Locale.KOREAN).format(now.time)) }
    var saving by rememberSaveable { mutableStateOf(false) }
    val hide: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onClose() },
        sheetState = state,
        containerColor = CGColor.Paper,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp).width(40.dp).height(4.dp).clip(CircleShape).background(CGColor.Line)) }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("기록하기", style = CGType.Title.copy(fontSize = 20.sp), color = CGColor.Ink, modifier = Modifier.weight(1f))
                Text("$dateText $timeText", style = CGType.Caption, color = CGColor.Ink3)
            }
            GroupLabel("발생 시간")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CGTextField(dateText, { dateText = it }, placeholder = "2026-10-03", modifier = Modifier.weight(1f))
                CGTextField(timeText, { timeText = it }, placeholder = "17:00", modifier = Modifier.weight(1f))
            }
            GroupLabel("분류")
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
            CGTextField(memo, { memo = it }, placeholder = "예) 아침에 일어날 때 어지럽다고 하심", singleLine = false, minHeight = 84.dp)
            Spacer(Modifier.height(6.dp))
            Text("AI가 감지하지 못한 이상 상황을 남기면 타임라인에도 표시됩니다", style = CGType.Caption, color = CGColor.Brand)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CGButton("취소", onClick = hide, kind = ButtonKind.Secondary, large = true, enabled = !saving, modifier = Modifier.weight(1f))
                CGButton(
                    if (saving) "저장 중" else "저장",
                    onClick = {
                        if (memo.isBlank()) {
                            Toast.makeText(context, "메모를 입력해주세요", Toast.LENGTH_SHORT).show()
                            return@CGButton
                        }
                        val recordedAt = isoInstantOrNull(dateText, timeText)
                        if (recordedAt == null) {
                            Toast.makeText(context, "날짜와 시간을 확인해주세요", Toast.LENGTH_SHORT).show()
                            return@CGButton
                        }
                        scope.launch {
                            saving = true
                            val error = BackendRepository.addRecord(category, condition, memo, recordedAt)
                            saving = false
                            if (error == null) {
                                Toast.makeText(context, "기록을 저장했어요", Toast.LENGTH_SHORT).show()
                                hide()
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
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun isoInstantOrNull(dateText: String, timeText: String): String? = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREAN).apply {
        isLenient = false
        timeZone = seoulTz
    }
    val date = parser.parse("${dateText.trim()} ${timeText.trim()}") ?: return null
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(date)
}.getOrNull()
