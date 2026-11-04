package com.example.careguardcamera.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.careguardcamera.data.timelineSections
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.text.SimpleDateFormat
import com.example.careguardcamera.data.EventKind
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.Severity
import com.example.careguardcamera.data.TimelineEntry
import com.example.careguardcamera.data.timelineEntries
import com.example.careguardcamera.ui.components.CGTopBar
import com.example.careguardcamera.ui.components.color
import com.example.careguardcamera.ui.components.softColor
import com.example.careguardcamera.ui.theme.CGColor
import com.example.careguardcamera.ui.theme.CGType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
fun TimelineScreen(onOpenEvent: (String) -> Unit, onOpenRecord: (String) -> Unit = {}) {
    val repository = BackendRepository
    val events by repository.events.collectAsState()
    val records by repository.records.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedOffset by rememberSaveable { mutableStateOf(0) }
    val entriesForDay = timelineEntries(events, records, selectedOffset)
    val sections = timelineSections(entriesForDay)
    val baseDate = GregorianCalendar(repository.todayYear, repository.todayMonth - 1, repository.today)
    val selectedDate = (baseDate.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, selectedOffset) }
    val mondayOffset = -((baseDate.get(Calendar.DAY_OF_WEEK) + 5) % 7)

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repository.refreshLiveEvents()
                delay(5_000L)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(CGColor.Surface).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        CGTopBar(title = "타임라인")
        Text("하루의 돌봄 흐름을 한눈에", style = CGType.Label, color = CGColor.BrandInk)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(CGColor.BrandSoft).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("yyyy년 M월", Locale.KOREAN).format(selectedDate.time), style = CGType.Caption, color = CGColor.BrandInk)
                    Text(SimpleDateFormat("d일 EEEE", Locale.KOREAN).format(selectedDate.time), style = CGType.Heading, color = CGColor.Ink)
                }
                Text(
                    if (selectedOffset == 0) "오늘" else "선택일", style = CGType.LabelBold, color = CGColor.BrandInk,
                    modifier = Modifier.clip(CircleShape).background(CGColor.Paper)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (mondayOffset..mondayOffset + 6).forEach { offset ->
                    val date = (baseDate.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
                    val weekday = SimpleDateFormat("E", Locale.KOREAN).format(date.time)
                    val day = date.get(Calendar.DAY_OF_MONTH)
                    val on = offset == selectedOffset
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (on) CGColor.Brand else Color.Transparent)
                            .semantics { selected = on }
                            .clickable { selectedOffset = offset }
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(weekday, style = CGType.Caption, color = if (on) Color.White else CGColor.Ink2)
                        Text("$day", style = CGType.BodyBold, color = if (on) Color.White else CGColor.Ink)
                        Box(Modifier.size(4.dp).clip(CircleShape).background(if (on) Color.White else Color.Transparent))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimelineSummary("AI 감지", events.count { it.dayOffset == selectedOffset && (it.kind == EventKind.FALL || it.kind == EventKind.STILL) }, Modifier.weight(1f))
                TimelineSummary("보호자 기록", records.count { it.dayOffset == selectedOffset }, Modifier.weight(1f))
            }
        }
        Text(
            "직접 기록은 기록 탭의 ‘기록하기’에서 남겨 주세요",
            style = CGType.Caption, color = CGColor.Ink2,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        if (entriesForDay.isEmpty()) {
            Text("아직 감지된 상황이나 보호자 기록이 없어요", style = CGType.Body, color = CGColor.Ink2, modifier = Modifier.padding(vertical = 24.dp))
        }
        sections.forEach { section ->
            val entries = section.entries
            if (entries.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(3.dp).height(16.dp).clip(CircleShape).background(CGColor.Brand))
                    Spacer(Modifier.width(8.dp))
                    Text(section.period.label, style = CGType.LabelBold, color = CGColor.BrandInk)
                    Spacer(Modifier.width(8.dp))
                    Text("${entries.size}건 · ${section.period.hours}", style = CGType.Caption, color = CGColor.Ink2)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(CGColor.Cat3.copy(alpha = 0.5f)))
                }
                entries.forEachIndexed { index, entry ->
                    TimelineCard(entry, index == entries.lastIndex, onOpenEvent, onOpenRecord)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TimelineSummary(label: String, count: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(CGColor.Paper).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = CGType.Caption, color = CGColor.Ink2)
        Text("${count}건", style = CGType.Heading, color = CGColor.BrandInk)
    }
}

@Composable
private fun TimelineCard(entry: TimelineEntry, last: Boolean, onOpenEvent: (String) -> Unit, onOpenRecord: (String) -> Unit) {
    val tone = if (entry.severity == Severity.NEUTRAL) CGColor.Brand else entry.severity.color()
    val status = when (entry.severity) {
        Severity.CRITICAL -> "위험"
        Severity.WARNING -> "주의"
        Severity.SAFE -> "정상"
        Severity.NEUTRAL -> "정보"
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(18.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            if (!last) Box(Modifier.padding(top = 22.dp).width(2.dp).fillMaxHeight().background(CGColor.Cat3))
            Box(Modifier.padding(top = 20.dp).size(10.dp).clip(CircleShape).background(CGColor.Brand))
        }
        Spacer(Modifier.width(8.dp))
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.weight(1f).padding(bottom = 10.dp).clip(shape)
                .background(CGColor.Paper).border(1.dp, CGColor.BrandSoft, shape)
                .then(entry.eventId?.let { id -> Modifier.clickable { onOpenEvent(id) } }
                    ?: entry.recordId?.let { id -> Modifier.clickable { onOpenRecord(id) } } ?: Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.time, style = CGType.LabelBold, color = CGColor.BrandInk, modifier = Modifier.weight(1f))
                Text(
                    status, style = CGType.Caption.copy(fontWeight = FontWeight.Bold), color = tone,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (entry.severity == Severity.NEUTRAL) CGColor.BrandSoft else entry.severity.softColor())
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title, style = CGType.BodyBold,
                    color = if (entry.severity == Severity.CRITICAL) CGColor.Critical else CGColor.Ink,
                    modifier = Modifier.weight(1f)
                )
                if (entry.eventId != null || entry.recordId != null) Text("›", fontSize = 22.sp, color = CGColor.Brand)
            }
            entry.detail?.let { Text(it, style = CGType.Caption, color = CGColor.Ink2) }
        }
    }
}
