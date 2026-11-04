package com.example.careguardcamera

import com.example.careguardcamera.data.*
import org.junit.Assert.*
import org.junit.Test

class TimelineEntryTest {
    @Test
    fun mergesRecordsAndDetectionsByTimeWithoutMixingDatesOrDetailRoutes() {
        val events = listOf(
            CareEvent("event", 0, "9:30", EventKind.FALL, Severity.CRITICAL, "낙상 감지"),
            CareEvent("yesterday", -1, "23:00", EventKind.FALL, Severity.CRITICAL, "어제 감지")
        )
        val records = listOf(
            CareRecord("record", "오늘", "14:20", RecordCategory.SYMPTOM, "어지러움", "방문 확인", "보호자", Condition.DIZZY),
            CareRecord("old", "어제", "22:00", RecordCategory.OTHER, "어제 기록", "", "보호자", dayOffset = -1)
        )
        val rows = timelineEntries(events, records)
        assertEquals(listOf("어지러움", "낙상 감지"), rows.map { it.title })
        assertTrue(rows[0].detail!!.contains("보호자 기록 · 보호자 · 방문 확인"))
        assertNull(rows[0].eventId)
        assertEquals("event", rows[1].eventId)
        assertTrue(rows[1].detail!!.contains("AI 감지"))
        assertEquals(2, timelineEntries(events, records, -1).size)
    }


    @Test
    fun groupsByOccurrenceTimeAtEveryPeriodBoundary() {
        val times = listOf("00:00", "05:59", "06:00", "11:59", "12:00", "17:59", "18:00", "23:59")
        val sections = timelineSections(times.map { TimelineEntry(it, it, null, Severity.NEUTRAL) })
        assertEquals(TimelinePeriod.values().toList(), sections.map { it.period })
        assertEquals(listOf(2, 2, 2, 2), sections.map { it.entries.size })
        assertEquals(listOf("23:59", "18:00"), sections.first().entries.map { it.time })
        assertEquals(listOf("05:59", "00:00"), sections.last().entries.map { it.time })
        assertTrue(timelineSections(emptyList()).isEmpty())
    }

    @Test
    fun selectedDayCountsBothSourcesButNotResponseNotes() {
        val events = listOf(CareEvent("event", -1, "12:00", EventKind.FALL, Severity.CRITICAL, "감지",
            notes = listOf(ResponseNote("18:00", "대응", null), ResponseNote("19:00", "확인", null))))
        val records = listOf(CareRecord("record", "어제", "17:59", RecordCategory.SYMPTOM,
            "수동 기록", "", "보호자", dayOffset = -1))
        val sections = timelineSections(timelineEntries(events, records, -1))
        assertEquals(1, sections.size)
        assertEquals(TimelinePeriod.AFTERNOON, sections.single().period)
        assertEquals(2, sections.single().entries.size)
        assertTrue(timelineSections(timelineEntries(events, records, 0)).isEmpty())
    }
}
