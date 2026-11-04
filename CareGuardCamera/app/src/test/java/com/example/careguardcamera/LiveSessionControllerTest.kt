package com.example.careguardcamera

import com.example.careguardcamera.data.ApiHttpException
import com.example.careguardcamera.data.DetectionEventUpload
import com.example.careguardcamera.data.InferenceMode
import com.example.careguardcamera.data.LiveControlLease
import com.example.careguardcamera.data.LiveSessionApi
import com.example.careguardcamera.data.LiveSessionIdentity
import com.example.careguardcamera.data.LiveSessionStatus
import com.example.careguardcamera.data.LiveSignalList
import com.example.careguardcamera.data.LiveViewerJoin
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.video.LiveFrame
import com.example.careguardcamera.video.LiveFrameSource
import com.example.careguardcamera.video.LiveSessionController
import com.example.careguardcamera.video.LocalInferenceEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** 세션 갱신이 실패한 뒤에도 카메라가 라이브 세션을 되살리는지 확인한다. 시간은 가상 시계로 진행한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionControllerTest {
    @Test
    fun leaseTimeoutDoesNotEndRenewLoop() = runTest {
        val api = FakeLiveApi(renewResults = mutableListOf(Hang))
        val controller = startController(api)

        // 5초: 첫 갱신이 응답 없이 멈춤 → 15초: 10초 시간 초과 → 20초: 다음 갱신
        advanceTimeBy(20_100L)

        assertEquals(listOf("s1", "s1"), api.renewedSessions)
        assertEquals(LiveSessionStatus.NO_VIEWER, controller.state.value.status)
        assertNull(controller.state.value.lastError)
    }

    @Test
    fun expiredSessionIsRecreatedWithoutStoppingCapture() = runTest {
        val api = FakeLiveApi(renewResults = mutableListOf(Fail(ApiHttpException(410, "라이브 세션이 종료되었습니다."))))
        val frames = FakeFrameSource()
        val controller = startController(api, frames)

        // 5초: 410 → 10초: 새 세션 생성 → 15초: 새 세션으로 갱신
        advanceTimeBy(15_100L)

        assertEquals(2, api.createdSessions)
        assertEquals(listOf("s1", "s2"), api.renewedSessions)
        assertEquals("s2", controller.state.value.sessionId)
        assertEquals(LiveSessionStatus.NO_VIEWER, controller.state.value.status)
        assertEquals(0, frames.stops)
    }

    @Test
    fun recreateKeepsRetryingWhileServerIsUnreachable() = runTest {
        val api = FakeLiveApi(
            renewResults = mutableListOf(Fail(ApiHttpException(404, "라이브 세션을 찾을 수 없습니다."))),
            failingCreateAttempts = mapOf(2 to IOException("connection refused"))
        )
        val controller = startController(api)

        // 5초: 404 → 10초: 생성 실패 → 15초: 생성 성공 → 20초: 갱신
        advanceTimeBy(20_100L)

        assertEquals(3, api.createAttempts)
        assertEquals(listOf("s1", "s2"), api.renewedSessions)
        assertEquals("s2", controller.state.value.sessionId)
        assertEquals(LiveSessionStatus.NO_VIEWER, controller.state.value.status)
    }

    @Test
    fun rejectedCredentialsStillStopTheSession() = runTest {
        val api = FakeLiveApi(renewResults = mutableListOf(Fail(ApiHttpException(401, "카메라 인증 정보가 필요합니다."))))
        val frames = FakeFrameSource()
        val controller = startController(api, frames)

        advanceTimeBy(30_000L)

        assertEquals(1, api.createdSessions)
        assertEquals(LiveSessionStatus.STOPPED, controller.state.value.status)
        assertEquals(1, frames.stops)
    }

    @Test
    fun onlyFallenImmobileR1EventsAreRaisedAsFallIncidents() = runTest {
        val frames = FakeFrameSource()
        val events = listOf(detection("fall", "FALL", "MODEL"), detection("r1", "FALLEN_IMMOBILE", "R1"), detection("r2", "STILL_HORIZONTAL", "R2"))
        val controller = startController(FakeLiveApi(), frames) { events }
        val raised = backgroundScope.async { controller.fallIncidents.first() }
        runCurrent()

        frames.frames.emit(LiveFrame(1L, 0L, 2, 2, 0, 35, emptyList()))
        // 추론은 실제 백그라운드 스레드에서 돌기 때문에 가상 시계가 아니라 실제로 결과를 기다린다.
        val first = withContext(Dispatchers.Default) { withTimeout(5_000L) { raised.await() } }

        assertEquals("r1", first.clientEventId)
    }

    private fun detection(id: String, kind: String, rule: String) = DetectionEventUpload(
        clientEventId = id,
        occurredAt = "2026-10-05T00:00:00.000Z",
        enqueuedAtElapsedMillis = 0L,
        kind = kind,
        rule = rule,
        state = "FALLEN",
        probNormal = 0.1f,
        probFall = 0.2f,
        probFallen = 0.7f
    )

    private suspend fun TestScope.startController(
        api: FakeLiveApi,
        frames: FakeFrameSource = FakeFrameSource(),
        detect: (LiveFrame) -> List<DetectionEventUpload> = { emptyList() }
    ): LiveSessionController {
        val controller = LiveSessionController(
            api = api,
            captureSession = frames,
            inference = object : LocalInferenceEngine {
                override suspend fun onFrame(frame: LiveFrame): List<DetectionEventUpload> = detect(frame)
            },
            scope = backgroundScope,
            nowElapsedMillis = { testScheduler.currentTime }
        )
        controller.start("camera-1", PrivacyMode.VIDEO_SKELETON)
        return controller
    }

    private sealed interface RenewResult
    private object Hang : RenewResult
    private class Fail(val error: Exception) : RenewResult

    /** 정해 둔 결과를 차례로 돌려주고, 다 쓰면 정상 갱신을 돌려주는 서버 대역. */
    private class FakeLiveApi(
        private val renewResults: MutableList<RenewResult> = mutableListOf(),
        private val failingCreateAttempts: Map<Int, Exception> = emptyMap()
    ) : LiveSessionApi {
        var createAttempts = 0
        var createdSessions = 0
        val renewedSessions = mutableListOf<String>()

        override suspend fun createCameraSession(cameraId: String, mode: InferenceMode, privacyMode: PrivacyMode): LiveSessionIdentity {
            createAttempts++
            failingCreateAttempts[createAttempts]?.let { throw it }
            createdSessions++
            return LiveSessionIdentity("s$createdSessions", cameraId, "patient-1", privacyMode, 1L, null)
        }

        override suspend fun renewCameraLease(sessionId: String): LiveControlLease {
            renewedSessions += sessionId
            when (val result = renewResults.removeFirstOrNull()) {
                Hang -> awaitCancellation()
                is Fail -> throw result.error
                null -> Unit
            }
            return LiveControlLease(PrivacyMode.VIDEO_SKELETON, 1L, null)
        }

        override suspend fun endCameraSession(sessionId: String) = Unit
        override suspend fun uploadDetectionEvent(sessionId: String, event: DetectionEventUpload) = true
        override suspend fun discoverGuardianSession(patientId: String, cameraId: String, guardianUserId: String) = unused()
        override suspend fun joinGuardianSession(patientId: String, sessionId: String, guardianUserId: String): LiveViewerJoin = unused()
        override suspend fun sendCameraSignal(cameraId: String, sessionId: String, kind: String, payload: JSONObject): Long = unused()
        override suspend fun listCameraSignals(cameraId: String, sessionId: String, after: Long): LiveSignalList = unused()
        override suspend fun sendGuardianSignal(patientId: String, sessionId: String, viewerId: String, kind: String, payload: JSONObject): Long = unused()
        override suspend fun listGuardianSignals(patientId: String, sessionId: String, viewerId: String, after: Long): LiveSignalList = unused()
        override suspend fun leaveGuardianSession(patientId: String, sessionId: String, viewerId: String) = Unit

        private fun unused(): Nothing = throw UnsupportedOperationException("카메라 세션 테스트에서는 쓰지 않는다")
    }

    private class FakeFrameSource : LiveFrameSource {
        override val frames = MutableSharedFlow<LiveFrame>()
        var stops = 0
        override suspend fun start() = Unit
        override suspend fun stop() {
            stops++
        }
    }
}
