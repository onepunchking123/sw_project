package com.example.careguardcamera

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.careguardcamera.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real HTTP test; run only against the disposable backend created by scripts/test-android-api.py. */
@RunWith(AndroidJUnit4::class)
class BackendCrudIntegrationTest {
    @Test fun serverCrudRoundTripFeedsScreenState() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("disposableBackend") == "true")
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "api-test-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        BackendRepository.initialize(isolated)
        assertNull(BackendRepository.configureServer(args.getString("backendUrl") ?: "http://10.0.2.2:8080"))
        assertNull(BackendRepository.savePatient("연동 환자", "80", "여성", "서울"))
        assertEquals("연동 환자", BackendRepository.patient.value.name)
        assertNotNull(BackendRepository.savePatient("오류", "131", "여성", "서울"))
        assertEquals("연동 환자", BackendRepository.patient.value.name)

        assertNull(BackendRepository.saveContact(null, "비가입 가족", "자녀", "01012345678"))
        val contact = BackendRepository.contacts.value.single()
        assertNull(BackendRepository.saveContact(contact.id, "수정 가족", "배우자", "01098765432"))
        assertNull(BackendRepository.registerCamera("거실"))
        val camera = BackendRepository.cameras.value.single()
        assertFalse(camera.online)
        assertNull(BackendRepository.renameCamera(camera.id, "안방"))
        assertNull(BackendRepository.setPrivacyMode(camera.id, PrivacyMode.SKELETON_ONLY))
        assertNull(BackendRepository.setStillMinutes(null))
        assertNull(BackendRepository.setFallAlert(false))
        assertNull(BackendRepository.refresh())
        assertEquals("수정 가족", BackendRepository.contacts.value.single().name)
        assertEquals("안방", BackendRepository.cameras.value.single().name)
        assertEquals(PrivacyMode.SKELETON_ONLY, BackendRepository.cameras.value.single().privacyMode)
        assertNull(BackendRepository.notifications.value.stillMinutes)
        assertFalse(BackendRepository.notifications.value.fallAlert)

        assertNull(BackendRepository.addRecord(RecordCategory.SYMPTOM, Condition.DIZZY, "어지러움"))
        val record = BackendRepository.records.value.single()
        assertNull(BackendRepository.addNote(record.id, true, "CALL", "FINE", "통화 확인"))
        assertNull(BackendRepository.addNote(record.id, true, "VISIT", null, "방문 확인"))
        assertEquals(2, BackendRepository.notes.value[record.id]?.size)
        assertNotNull(BackendRepository.addNote(UUID.randomUUID().toString(), true, "CALL", null, "없는 대상"))
        assertEquals(2, BackendRepository.notes.value[record.id]?.size)
        val note = BackendRepository.notes.value[record.id]!!.first()
        assertNull(BackendRepository.updateNote(note.id, "OBSERVE", null, "수정한 메모"))
        assertEquals("수정한 메모", BackendRepository.notes.value[record.id]!!.first { it.id == note.id }.detail)
        assertNull(BackendRepository.updateRecord(record.id, RecordCategory.SYMPTOM, Condition.GAIT, "보행 확인"))
        assertNull(BackendRepository.refresh())
        assertEquals("보행 확인", BackendRepository.records.value.single().memo)
        assertEquals(2, BackendRepository.notes.value[record.id]?.size)
        assertNull(BackendRepository.loadCalendar(BackendRepository.todayYear, BackendRepository.todayMonth))
        val month = "%04d-%02d".format(BackendRepository.todayYear, BackendRepository.todayMonth)
        assertEquals(DayCondition.LOW, BackendRepository.calendars.value[month]?.get(BackendRepository.today))
        assertNull(BackendRepository.finishOnboarding())
        assertTrue(BackendRepository.completed)
        assertNull(BackendRepository.deleteNote(BackendRepository.notes.value[record.id]!!.first().id))
        assertEquals(1, BackendRepository.notes.value[record.id]?.size)
        assertNull(BackendRepository.deleteRecord(record.id))
        assertTrue(BackendRepository.records.value.isEmpty())
        assertTrue(BackendRepository.notes.value[record.id].isNullOrEmpty())
        // Server-created AI data must feed the same timeline and incident screens.
        val api = ApiClient(baseUrlProvider = { BackendRepository.serverUrl })
        val patientId = BackendRepository.patient.value.id
        val date = "%04d-%02d-%02d".format(BackendRepository.todayYear, BackendRepository.todayMonth, BackendRepository.today)
        val event = org.json.JSONObject(api.post("/api/patients/$patientId/events", org.json.JSONObject()
            .put("clientEventId", UUID.randomUUID().toString()).put("cameraDeviceId", camera.id)
            .put("occurredAt", "${date}T06:00:00.123456+09:00")
            .put("kind", "FALLEN_IMMOBILE").put("rule", "R1").put("state", "FALLEN")
            .put("probNormal", 0.01).put("probFall", 0.09).put("probFallen", 0.90).put("durationSec", 30)))
        val eventId = event.getString("id")
        assertNull(BackendRepository.refresh())
        assertEquals("06:00", BackendRepository.events.value.single().time)
        assertEquals(TimelinePeriod.MORNING, timelineSections(timelineEntries(BackendRepository.events.value, emptyList())).single().period)
        assertNull(BackendRepository.addNote(eventId, false, "CALL", "FINE", "AI 대응"))
        assertEquals(1, BackendRepository.notes.value[eventId]?.size)
        api.post("/api/patients/$patientId/incidents", org.json.JSONObject()
            .put("detectionEventId", eventId).put("startedAt", "${date}T06:00:00+09:00")
            .put("countdownEndsAt", "${date}T06:01:00+09:00"))
        assertNull(BackendRepository.refresh())
        assertTrue(BackendRepository.demo.value.emergency)
        assertNull(BackendRepository.respondToIncident())
        assertEquals(ResponseStatus.RESPONDING, BackendRepository.caregivers.value.single { it.isMe }.responseStatus)
        assertNull(BackendRepository.resolveIncident())
        assertFalse(BackendRepository.demo.value.emergency)
        assertNull(BackendRepository.removeCamera(camera.id))
        assertTrue(BackendRepository.cameras.value.isEmpty())
        assertNull(BackendRepository.removeContact(contact.id))
        assertTrue(BackendRepository.contacts.value.isEmpty())
    }
}
