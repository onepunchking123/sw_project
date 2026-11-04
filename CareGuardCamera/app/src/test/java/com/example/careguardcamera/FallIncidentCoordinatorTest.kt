package com.example.careguardcamera

import com.example.careguardcamera.data.ApiHttpException
import com.example.careguardcamera.data.DetectionEventUpload
import com.example.careguardcamera.data.FallIncidentApi
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.video.CancelDelivery
import com.example.careguardcamera.video.ClipDelivery
import com.example.careguardcamera.video.clipPrivacyMode
import com.example.careguardcamera.video.ClipFrameWindow
import com.example.careguardcamera.video.EncodedClip
import com.example.careguardcamera.video.FallAlarm
import com.example.careguardcamera.video.FallAlertState
import com.example.careguardcamera.video.FallIncidentCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 카메라 폰의 낙상 경보: 카운트다운, 환자 취소, 클립 업로드 재시도. 시간은 가상 시계로 진행한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class FallIncidentCoordinatorTest {
    @Test
    fun withoutPatientResponseClipIsUploadedAfterCountdown() = runTest {
        val api = FakeIncidentApi()
        val alarm = FakeAlarm()
        val coordinator = coordinator(api, alarm)

        coordinator.onFallIncident(r1Event("e1"))
        advanceTimeBy(1_100L)
        assertEquals(FallAlertState.Countdown("e1", 9), coordinator.state.value)
        assertTrue(alarm.ringing)
        assertTrue(api.uploads.isEmpty())

        advanceTimeBy(9_100L)
        assertEquals(listOf("e1"), api.uploads)
        assertEquals(FallAlertState.Escalated(ClipDelivery.UPLOADED), coordinator.state.value)
        assertFalse(alarm.ringing)
        assertTrue(api.patientOks.isEmpty())
    }

    @Test
    fun patientOkStopsAlarmReportsAndSkipsClip() = runTest {
        val api = FakeIncidentApi()
        val alarm = FakeAlarm()
        val coordinator = coordinator(api, alarm)

        coordinator.onFallIncident(r1Event("e2"))
        advanceTimeBy(4_000L)
        coordinator.patientOk()
        advanceTimeBy(20_000L)

        assertEquals(listOf("e2"), api.patientOks)
        assertTrue(api.uploads.isEmpty())
        assertFalse(alarm.ringing)
        assertEquals(FallAlertState.Idle, coordinator.state.value)
    }

    @Test
    fun uploadRetriesWhileServerHasNotStoredTheEventYet() = runTest {
        val api = FakeIncidentApi(uploadFailures = mutableListOf(ApiHttpException(404, "not yet"), ApiHttpException(404, "not yet")))
        val coordinator = coordinator(api, FakeAlarm())

        coordinator.onFallIncident(r1Event("e3"))
        // 10초 카운트다운 + 재시도 대기 1초 + 2초
        advanceTimeBy(13_500L)

        assertEquals(3, api.uploadAttempts)
        assertEquals(FallAlertState.Escalated(ClipDelivery.UPLOADED), coordinator.state.value)
    }

    @Test
    fun conflictIsNotRetried() = runTest {
        val api = FakeIncidentApi(uploadFailures = mutableListOf(ApiHttpException(409, "patient ok")))
        val coordinator = coordinator(api, FakeAlarm())

        coordinator.onFallIncident(r1Event("e4"))
        advanceTimeBy(15_000L)

        assertEquals(1, api.uploadAttempts)
        assertEquals(FallAlertState.Escalated(ClipDelivery.FAILED), coordinator.state.value)
    }

    @Test
    fun noClipStillEscalatesWithoutUpload() = runTest {
        val api = FakeIncidentApi()
        val coordinator = coordinator(api, FakeAlarm(), clip = null)

        coordinator.onFallIncident(r1Event("e5"))
        advanceTimeBy(10_500L)

        assertEquals(0, api.uploadAttempts)
        assertEquals(FallAlertState.Escalated(ClipDelivery.NOT_AVAILABLE), coordinator.state.value)
    }

    @Test
    fun failedCancelIsShownSoPatientKnowsGuardianMayBeAlerted() = runTest {
        val api = FakeIncidentApi(patientOkFailure = ApiHttpException(401, "token"))
        val coordinator = coordinator(api, FakeAlarm())

        coordinator.onFallIncident(r1Event("e6"))
        advanceTimeBy(3_000L)
        coordinator.patientOk()
        advanceTimeBy(100L)

        assertEquals(FallAlertState.PatientOk(CancelDelivery.FAILED), coordinator.state.value)
    }

    @Test
    fun encoderErrorIsReportedAsFailureNotAsPrivacySetting() = runTest {
        val errors = mutableListOf<Throwable>()
        val coordinator = FallIncidentCoordinator(
            scope = backgroundScope,
            api = FakeIncidentApi(),
            cameraId = { "camera-1" },
            alarm = FakeAlarm(),
            captureClip = { throw IllegalStateException("codec") },
            onClipError = { errors += it }
        )

        coordinator.onFallIncident(r1Event("e7"))
        advanceTimeBy(10_500L)

        assertEquals(FallAlertState.Escalated(ClipDelivery.FAILED), coordinator.state.value)
        assertEquals(1, errors.size)
    }

    @Test
    fun newFallWhileEscalatingDoesNotOverwriteCurrentAlert() = runTest {
        val api = FakeIncidentApi()
        val coordinator = coordinator(api, FakeAlarm())

        coordinator.onFallIncident(r1Event("first"))
        advanceTimeBy(10_500L)
        coordinator.onFallIncident(r1Event("second"))
        advanceTimeBy(1_000L)

        assertEquals(FallAlertState.Escalated(ClipDelivery.UPLOADED), coordinator.state.value)
        assertEquals(listOf("first"), api.uploads)
    }

    @Test
    fun clipUsesTheStricterPrivacyMode() {
        assertEquals(PrivacyMode.SKELETON_ONLY, clipPrivacyMode(PrivacyMode.SKELETON_ONLY, PrivacyMode.NO_OVERLAY))
        assertEquals(PrivacyMode.SKELETON_ONLY, clipPrivacyMode(PrivacyMode.NO_OVERLAY, PrivacyMode.SKELETON_ONLY))
        assertEquals(PrivacyMode.VIDEO_SKELETON, clipPrivacyMode(PrivacyMode.VIDEO_SKELETON, PrivacyMode.NO_OVERLAY))
        assertEquals(PrivacyMode.NO_OVERLAY, clipPrivacyMode(null, PrivacyMode.NO_OVERLAY))
    }

    @Test
    fun clipWindowRateLimitsPrunesAndSlices() {
        val window = ClipFrameWindow<Int>(keepNanos = 10_000L, minIntervalNanos = 100L)
        window.add(0L, 0)
        window.add(50L, 1) // 간격이 짧아 버린다
        for (t in 100L..20_000L step 100L) window.add(t, t.toInt())

        assertEquals(20_000L, window.latestTimestamp())
        assertEquals(listOf(10_000, 10_100, 10_200), window.between(9_950L, 10_250L))
        assertTrue(window.between(0L, 9_999L).isEmpty())
    }

    private fun TestScope.coordinator(
        api: FakeIncidentApi,
        alarm: FakeAlarm,
        clip: EncodedClip? = EncodedClip(byteArrayOf(1, 2, 3), 10f, 0L)
    ) = FallIncidentCoordinator(
        scope = backgroundScope,
        api = api,
        cameraId = { "camera-1" },
        alarm = alarm,
        captureClip = { clip }
    )

    private fun r1Event(id: String) = DetectionEventUpload(
        clientEventId = id,
        occurredAt = "2026-10-05T00:00:00.000Z",
        enqueuedAtElapsedMillis = 0L,
        kind = "FALLEN_IMMOBILE",
        rule = "R1",
        state = "FALLEN",
        probNormal = 0.1f,
        probFall = 0.2f,
        probFallen = 0.7f,
        frameTimestampNanos = 1L
    )

    private class FakeAlarm : FallAlarm {
        var ringing = false
        override fun start() {
            ringing = true
        }
        override fun stop() {
            ringing = false
        }
    }

    private class FakeIncidentApi(
        private val uploadFailures: MutableList<Exception> = mutableListOf(),
        private val patientOkFailure: Exception? = null
    ) : FallIncidentApi {
        val patientOks = mutableListOf<String>()
        val uploads = mutableListOf<String>()
        var uploadAttempts = 0

        override suspend fun reportPatientOk(cameraId: String, clientEventId: String) {
            patientOkFailure?.let { throw it }
            patientOks += clientEventId
        }

        override suspend fun uploadClip(cameraId: String, clientEventId: String, mp4: ByteArray, startedAt: String, durationSec: Float) {
            uploadAttempts++
            uploadFailures.removeFirstOrNull()?.let { throw it }
            uploads += clientEventId
        }
    }
}
