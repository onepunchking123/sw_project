package com.example.careguardcamera

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.careguardcamera.data.ApiClient
import com.example.careguardcamera.data.ApiHttpException
import com.example.careguardcamera.data.BackendRepository
import com.example.careguardcamera.data.CameraDeviceRepository
import com.example.careguardcamera.data.DetectionEventUpload
import com.example.careguardcamera.data.DeviceCredentialStore
import com.example.careguardcamera.data.HttpLiveSessionApi
import com.example.careguardcamera.data.InferenceMode
import com.example.careguardcamera.data.LiveSignal
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.data.TimelinePeriod
import com.example.careguardcamera.data.timelineEntries
import com.example.careguardcamera.data.timelineSections
import com.example.careguardcamera.inference.FallInferenceEvent
import com.example.careguardcamera.inference.FallInferenceResult
import com.example.careguardcamera.inference.LiveFallModelEngine
import com.example.careguardcamera.video.FallModelInferenceAdapter
import com.example.careguardcamera.video.GuardianWebRtcViewer
import com.example.careguardcamera.video.LatestOverlayPose
import com.example.careguardcamera.video.LiveFrame
import com.example.careguardcamera.video.WebRtcBridgeVideoPeer
import com.example.careguardcamera.video.WebRtcFrameBridge
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP live-session test; run only against scripts/test-android-api.py disposable backend. */
@RunWith(AndroidJUnit4::class)
class LiveSessionIntegrationTest {
    @Test fun liveSessionApiRoundTripsPairingMailboxEventTimelineAndStop() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("disposableBackend") == "true")
        val backendUrl = args.getString("backendUrl") ?: "http://10.0.2.2:8080"
        val fixture = pairCameraWithGuardian(backendUrl)
        val cameraApi = CameraDeviceRepository.liveSessionApi()
        val guardianApi = BackendRepository.liveSessionApi()

        val session = cameraApi.createCameraSession(
            cameraId = fixture.cameraId,
            mode = InferenceMode.PHONE,
            privacyMode = PrivacyMode.VIDEO_SKELETON
        )
        assertEquals(fixture.cameraId, session.cameraId)
        assertEquals(fixture.patientId, session.patientId)
        assertEquals(PrivacyMode.VIDEO_SKELETON, session.privacyMode)
        assertTrue(session.leaseExpiresAt?.isNotBlank() == true)
        assertEquals(0, session.viewerCount)

        val discovered = guardianApi.discoverGuardianSession(fixture.patientId, fixture.cameraId, fixture.guardianUserId)
        assertEquals(session.sessionId, discovered.sessionId)
        assertEquals(session.cameraId, discovered.cameraId)
        assertEquals(session.patientId, discovered.patientId)
        assertEquals(session.privacyRevision, discovered.privacyRevision)

        val adapter = FallModelInferenceAdapter(FakeLowercaseFallEngine(), LatestOverlayPose())
        val adapterEvent = try {
            adapter.onFrame(syntheticLiveFrame()).single()
        } finally {
            adapter.close()
        }
        assertEquals("FALL", adapterEvent.kind)
        assertEquals("MODEL", adapterEvent.rule)
        assertEquals("FALL", adapterEvent.state)
        assertTrue(cameraApi.uploadDetectionEvent(session.sessionId, adapterEvent))

        val viewer = guardianApi.joinGuardianSession(fixture.patientId, session.sessionId, fixture.guardianUserId)
        assertEquals(session.sessionId, viewer.sessionId)
        assertEquals(fixture.patientId, viewer.patientId)
        assertTrue(viewer.viewerId.matches(Regex("[A-Za-z0-9_-]{22}")))
        assertTrue(viewer.generation > 0L)

        val joinedSignals = guardianApi.listGuardianSignals(fixture.patientId, session.sessionId, viewer.viewerId, 0L)
        val viewerJoined = joinedSignals.singleSignal("SYSTEM", "VIEWER_JOINED", viewer.generation)
        assertEquals(viewer.viewerId, viewerJoined.payload.getString("viewerId"))
        assertEquals(viewer.generation, viewerJoined.payload.getLong("generation"))

        val offerSequence = cameraApi.sendCameraSignal(
            fixture.cameraId,
            session.sessionId,
            "OFFER",
            JSONObject().put("type", "offer").put("sdp", "android-offer")
        )
        assertTrue(offerSequence > viewerJoined.sequence)

        val guardianSignals = guardianApi.listGuardianSignals(fixture.patientId, session.sessionId, viewer.viewerId, 0L)
        val offerSignal = guardianSignals.singleSignal("CAMERA", "OFFER", viewer.generation)
        assertEquals(offerSequence, offerSignal.sequence)
        assertEquals("android-offer", offerSignal.payload.getString("sdp"))
        assertEquals(offerSequence, guardianSignals.nextCursor)

        val answerSequence = guardianApi.sendGuardianSignal(
            fixture.patientId,
            session.sessionId,
            viewer.viewerId,
            "ANSWER",
            JSONObject().put("type", "answer").put("sdp", "android-answer")
        )
        assertTrue(answerSequence > offerSequence)

        val cameraSignals = cameraApi.listCameraSignals(fixture.cameraId, session.sessionId, viewerJoined.sequence)
        val answerSignal = cameraSignals.singleSignal("GUARDIAN", "ANSWER", viewer.generation)
        assertEquals(answerSequence, answerSignal.sequence)
        assertEquals("android-answer", answerSignal.payload.getString("sdp"))
        assertEquals(answerSequence, cameraSignals.nextCursor)

        guardianApi.leaveGuardianSession(fixture.patientId, session.sessionId, viewer.viewerId)
        val rejoinedViewer = guardianApi.joinGuardianSession(fixture.patientId, session.sessionId, fixture.guardianUserId)
        assertTrue(rejoinedViewer.generation > viewer.generation)
        val rejoinSignals = guardianApi.listGuardianSignals(fixture.patientId, session.sessionId, rejoinedViewer.viewerId, 0L)
        assertEquals(1, rejoinSignals.items.size)
        val rejoinViewerJoined = rejoinSignals.singleSignal("SYSTEM", "VIEWER_JOINED", rejoinedViewer.generation)
        assertEquals(rejoinedViewer.viewerId, rejoinViewerJoined.payload.getString("viewerId"))

        val eventId = UUID.randomUUID().toString()
        val event = eventUpload(eventId)
        val uploaded = cameraApi.uploadDetectionEvent(
            session.sessionId,
            event
        )
        assertTrue(uploaded)

        val duplicateUpload = cameraApi.uploadDetectionEvent(
            session.sessionId,
            event
        )
        assertFalse(duplicateUpload)
        assertApiConflict { cameraApi.uploadDetectionEvent(session.sessionId, event.copy(probFall = EVENT_PROB_FALL - 0.20f)) }

        val adapterStoredEventId = assertStoredAdapterEventCanonical(backendUrl, fixture.patientId, adapterEvent.clientEventId)
        val storedEventId = assertStoredEventProbabilities(backendUrl, fixture.patientId, eventId)
        assertNull(BackendRepository.refreshLiveEvents())
        val expectedLiveEvents = BackendRepository.events.value.filter { it.id == adapterStoredEventId || it.id == storedEventId }
        assertEquals(2, expectedLiveEvents.size)
        val liveEvent = expectedLiveEvents.single { it.id == storedEventId }
        assertEquals("09:15", liveEvent.time)
        assertFloatEquals(EVENT_PROB_FALL, liveEvent.probFall)
        assertFloatEquals(EVENT_PROB_FALLEN, liveEvent.probStill)
        assertEquals(
            TimelinePeriod.MORNING,
            timelineSections(timelineEntries(listOf(liveEvent), emptyList(), liveEvent.dayOffset)).single().period
        )

        val renewed = cameraApi.renewCameraLease(session.sessionId)
        assertEquals(PrivacyMode.VIDEO_SKELETON, renewed.privacyMode)
        assertEquals(session.privacyRevision, renewed.privacyRevision)
        assertFalse(renewed.terminated)

        assertNull(BackendRepository.setPrivacyMode(fixture.cameraId, PrivacyMode.SKELETON_ONLY))
        assertApiGone { cameraApi.renewCameraLease(session.sessionId) }
        assertFalse(cameraApi.uploadDetectionEvent(session.sessionId, event))

        val stoppableSession = cameraApi.createCameraSession(
            cameraId = fixture.cameraId,
            mode = InferenceMode.PHONE,
            privacyMode = PrivacyMode.SKELETON_ONLY
        )
        assertEquals(PrivacyMode.SKELETON_ONLY, stoppableSession.privacyMode)
        assertTrue(stoppableSession.privacyRevision > session.privacyRevision)
        assertApiForbidden { guardianApi.joinGuardianSession(fixture.patientId, stoppableSession.sessionId, fixture.guardianUserId) }
        val stoppedEventId = UUID.randomUUID().toString()
        val stoppedEvent = eventUpload(stoppedEventId)
        assertTrue(cameraApi.uploadDetectionEvent(stoppableSession.sessionId, stoppedEvent))
        cameraApi.endCameraSession(stoppableSession.sessionId)
        assertRawApiGone(backendUrl) {
            put("/api/dev/live/cameras/${fixture.cameraId}/sessions/${stoppableSession.sessionId}/lease", JSONObject(), fixture.cameraToken)
        }
        assertRawEventReplayCreatedFalse(backendUrl) {
            post(
                "/api/dev/live/cameras/${fixture.cameraId}/sessions/${stoppableSession.sessionId}/events",
                eventBody(stoppedEvent),
                fixture.cameraToken
            )
        }
        assertRawApiConflict(backendUrl) {
            post(
                "/api/dev/live/cameras/${fixture.cameraId}/sessions/${stoppableSession.sessionId}/events",
                eventBody(stoppedEvent.copy(probFall = EVENT_PROB_FALL - 0.20f)),
                fixture.cameraToken
            )
        }
        assertRawApiGone(backendUrl) {
            post(
                "/api/dev/live/cameras/${fixture.cameraId}/sessions/${stoppableSession.sessionId}/events",
                eventBody(eventUpload(UUID.randomUUID().toString())),
                fixture.cameraToken
            )
        }
        assertApiGone { guardianApi.discoverGuardianSession(fixture.patientId, fixture.cameraId, fixture.guardianUserId) }
    }

    @Test fun webRtcAdaptersExchangeRealFrameThroughServerMailboxAcrossRejoins() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("disposableBackend") == "true")
        val backendUrl = args.getString("backendUrl") ?: "http://10.0.2.2:8080"
        val fixture = rawPairCameraWithGuardian(backendUrl)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = WebRtcFrameBridge.createFactory(context)
        val bridge = WebRtcFrameBridge.create(factory)
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)
        val cameraApi = HttpLiveSessionApi(ApiClient(baseUrlProvider = { backendUrl })) { fixture.cameraToken }
        val guardianApi = HttpLiveSessionApi(ApiClient(baseUrlProvider = { backendUrl })) { null }
        val session = cameraApi.createCameraSession(
            cameraId = fixture.cameraId,
            mode = InferenceMode.PHONE,
            privacyMode = PrivacyMode.VIDEO_SKELETON
        )
        val cameraPeer = WebRtcBridgeVideoPeer(factory, bridge, scope, LatestOverlayPose())
        val leaseJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                runCatching { cameraApi.renewCameraLease(session.sessionId) }
            }
        }

        try {
            cameraPeer.onSessionStarted(cameraApi, session)
            var previousGeneration = 0L
            repeat(3) { index ->
                val frameLatch = CountDownLatch(1)
                val connectedLatch = CountDownLatch(1)
                val sinkFailure = AtomicReference<String?>()
                val leftLuma = AtomicInteger(0)
                val rightLuma = AtomicInteger(0)
                val viewer = GuardianWebRtcViewer(
                    api = guardianApi,
                    factory = factory,
                    patientId = fixture.patientId,
                    sessionId = session.sessionId,
                    guardianUserId = fixture.guardianUserId,
                    videoSink = patternSink(frameLatch, sinkFailure, leftLuma, rightLuma),
                    scope = scope
                ) { state ->
                    if (state == "CONNECTED") connectedLatch.countDown()
                }
                try {
                    viewer.start()
                    val generation = waitForJoinedGeneration(cameraApi, fixture.cameraId, session.sessionId, previousGeneration)
                    assertTrue("generation must increase on join ${index + 1}", generation > previousGeneration)
                    previousGeneration = generation

                    val bitmap = createLumaPatternBitmap()
                    try {
                        waitUntil(8_000L) {
                            bridge.captureBitmap(bitmap, rotationDegrees = 0, timestampNs = SystemClock.elapsedRealtimeNanos())
                            frameLatch.await(250L, TimeUnit.MILLISECONDS)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    assertTrue("WebRTC viewer did not connect on join ${index + 1}", connectedLatch.await(1L, TimeUnit.SECONDS))
                    sinkFailure.get()?.let { throw AssertionError(it) }
                    assertTrue("Viewer did not receive injected transport fixture frame on join ${index + 1}", frameLatch.await(1L, TimeUnit.SECONDS))
                    assertTrue("Received frame did not preserve luma fixture on join ${index + 1}", rightLuma.get() - leftLuma.get() > MIN_LUMA_DELTA)
                } finally {
                    viewer.stop()
                }
            }
        } finally {
            leaseJob.cancelAndJoin()
            runCatching { withTimeout(2_000L) { cameraApi.endCameraSession(session.sessionId) } }
            runCatching { cameraPeer.close() }
            bridge.close()
            factory.dispose()
            scopeJob.cancel()
        }
    }

    private suspend fun pairCameraWithGuardian(backendUrl: String): Fixture {
        val isolated = isolatedContext("live-test-${UUID.randomUUID()}-")

        BackendRepository.initialize(isolated)
        CameraDeviceRepository.initialize(isolated)

        assertNull(BackendRepository.configureServer(backendUrl))
        assertNull(BackendRepository.startNewPatient())
        assertNull(BackendRepository.savePatient("라이브 환자", "80", "여성", "서울"))
        assertNull(BackendRepository.saveContact(null, "라이브 보호자", "자녀", "01012345678"))
        val patientId = requireNotNull(BackendRepository.currentPatientId())
        val guardianUserId = requireNotNull(BackendRepository.currentGuardianUserId())

        assertNull(CameraDeviceRepository.configureServer(backendUrl))
        assertNull(CameraDeviceRepository.refreshSelfOrIdle())
        assertNull(CameraDeviceRepository.issuePairingCode())
        val code = requireNotNull(CameraDeviceRepository.state.value.pairingCode)
        assertTrue(code.matches(Regex("[0-9]{8}")))

        assertNull(BackendRepository.connectCamera(code, "라이브 카메라"))
        assertTrue(CameraDeviceRepository.pollSelf())
        assertNull(CameraDeviceRepository.heartbeat())
        assertNull(BackendRepository.refreshCameras())
        val camera = BackendRepository.cameras.value.single()
        assertEquals("라이브 카메라", camera.name)
        assertEquals(CameraDeviceRepository.state.value.cameraId, camera.id)
        assertNotEquals("", guardianUserId)
        val cameraToken = requireNotNull(DeviceCredentialStore(isolated).load(backendUrl)?.token)

        return Fixture(
            patientId = patientId,
            guardianUserId = guardianUserId,
            cameraId = camera.id,
            cameraToken = cameraToken
        )
    }

    private suspend fun rawPairCameraWithGuardian(backendUrl: String): Fixture {
        val api = ApiClient(baseUrlProvider = { backendUrl })
        val nonce = UUID.randomUUID().toString()
        val cameraToken = randomCameraToken()
        val cameraId = JSONObject(
            api.post("/api/camera-devices/register", JSONObject().put("model", "Android integration"), cameraToken)
        ).getString("cameraId")
        val guardianUserId = JSONObject(
            api.post(
                "/api/users",
                JSONObject()
                    .put("name", "라이브 보호자")
                    .put("email", "live-$nonce@careguard.local")
                    .put("phone", "01000000000")
            )
        ).getString("id")
        val patientId = JSONObject(
            api.post(
                "/api/patients",
                JSONObject()
                    .put("name", "라이브 환자")
                    .put("age", 80)
                    .put("sex", "F")
                    .put("address", "서울")
                    .put("dementiaStage", JSONObject.NULL)
            )
        ).getString("id")
        api.post(
            "/api/patients/$patientId/caregivers",
            JSONObject()
                .put("userId", guardianUserId)
                .put("relation", "CHILD")
                .put("role", "OWNER")
                .put("notifyFall", true)
                .put("notifyStillMin", 5)
                .put("notifyOffline", true)
        )
        val code = JSONObject(api.post("/api/camera-devices/me/pairing-codes", JSONObject(), cameraToken)).getString("code")
        api.post(
            "/api/patients/$patientId/cameras/pair",
            JSONObject()
                .put("code", code)
                .put("name", "라이브 카메라")
                .put("guardianUserId", guardianUserId)
        )
        api.post("/api/camera-devices/me/heartbeat", JSONObject(), cameraToken)
        return Fixture(
            patientId = patientId,
            guardianUserId = guardianUserId,
            cameraId = cameraId,
            cameraToken = cameraToken
        )
    }

    private fun isolatedContext(prefix: String): Context {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        return object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)

            override fun getNoBackupFilesDir(): File =
                File(super.getNoBackupFilesDir(), prefix).also { it.mkdirs() }
        }
    }

    private suspend fun assertApiGone(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiHttpException) {
            assertEquals(410, e.code)
            return
        }
        throw AssertionError("Expected HTTP 410 Gone")
    }

    private suspend fun assertApiForbidden(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiHttpException) {
            assertEquals(403, e.code)
            return
        }
        throw AssertionError("Expected HTTP 403 Forbidden")
    }

    private suspend fun assertApiConflict(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiHttpException) {
            assertEquals(409, e.code)
            return
        }
        throw AssertionError("Expected HTTP 409 Conflict")
    }

    private suspend fun assertRawApiGone(backendUrl: String, block: suspend ApiClient.() -> Unit) {
        try {
            ApiClient(baseUrlProvider = { backendUrl }).block()
        } catch (e: ApiHttpException) {
            assertEquals(410, e.code)
            return
        }
        throw AssertionError("Expected HTTP 410 Gone")
    }

    private suspend fun assertRawApiConflict(backendUrl: String, block: suspend ApiClient.() -> Unit) {
        try {
            ApiClient(baseUrlProvider = { backendUrl }).block()
        } catch (e: ApiHttpException) {
            assertEquals(409, e.code)
            return
        }
        throw AssertionError("Expected HTTP 409 Conflict")
    }

    private suspend fun assertRawEventReplayCreatedFalse(
        backendUrl: String,
        block: suspend ApiClient.() -> String
    ) {
        val json = JSONObject(ApiClient(baseUrlProvider = { backendUrl }).block())
        assertFalse(json.getBoolean("created"))
    }

    private suspend fun assertStoredAdapterEventCanonical(backendUrl: String, patientId: String, clientEventId: String): String {
        val api = ApiClient(baseUrlProvider = { backendUrl })
        val events = JSONArray(api.get("/api/patients/$patientId/events"))
        val stored = (0 until events.length())
            .map { events.getJSONObject(it) }
            .single { it.getString("clientEventId") == clientEventId }
        assertEquals("FALL", stored.getString("kind"))
        assertEquals("MODEL", stored.getString("rule"))
        assertEquals("FALL", stored.getString("state"))
        assertFloatEquals(0.03f, stored.getDouble("probNormal").toFloat())
        assertFloatEquals(0.87f, stored.getDouble("probFall").toFloat())
        assertFloatEquals(0.10f, stored.getDouble("probFallen").toFloat())
        return stored.getString("id")
    }

    private suspend fun assertStoredEventProbabilities(backendUrl: String, patientId: String, clientEventId: String): String {
        val api = ApiClient(baseUrlProvider = { backendUrl })
        val events = JSONArray(api.get("/api/patients/$patientId/events"))
        val stored = (0 until events.length())
            .map { events.getJSONObject(it) }
            .single { it.getString("clientEventId") == clientEventId }
        assertEquals(EVENT_KIND, stored.getString("kind"))
        assertEquals(EVENT_RULE, stored.getString("rule"))
        assertEquals(EVENT_STATE, stored.getString("state"))
        assertFloatEquals(EVENT_PROB_NORMAL, stored.getDouble("probNormal").toFloat())
        assertFloatEquals(EVENT_PROB_FALL, stored.getDouble("probFall").toFloat())
        assertFloatEquals(EVENT_PROB_FALLEN, stored.getDouble("probFallen").toFloat())
        assertFloatEquals(EVENT_DURATION_SEC, stored.getDouble("durationSec").toFloat())
        return stored.getString("id")
    }

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected, actual, 0.0001f)
    }

    private suspend fun waitForJoinedGeneration(
        api: com.example.careguardcamera.data.LiveSessionApi,
        cameraId: String,
        sessionId: String,
        previousGeneration: Long
    ): Long {
        var cursor = 0L
        var latest = previousGeneration
        withTimeout(8_000L) {
            while (latest <= previousGeneration) {
                val signals = api.listCameraSignals(cameraId, sessionId, cursor)
                cursor = signals.nextCursor
                signals.items
                    .filter { it.from == "SYSTEM" && it.kind == "VIEWER_JOINED" }
                    .forEach { signal ->
                        latest = maxOf(latest, signal.generation)
                    }
                if (latest <= previousGeneration) delay(250L)
            }
        }
        return latest
    }

    private fun patternSink(
        frameLatch: CountDownLatch,
        sinkFailure: AtomicReference<String?>,
        leftLuma: AtomicInteger,
        rightLuma: AtomicInteger
    ): VideoSink =
        VideoSink { frame: VideoFrame ->
            val i420 = frame.buffer.toI420()
            if (i420 == null) {
                sinkFailure.compareAndSet(null, "Receiver frame could not convert to I420")
                return@VideoSink
            }
            try {
                val left = sampleLuma(i420, x = i420.width / 4, y = i420.height / 2)
                val right = sampleLuma(i420, x = (i420.width * 3) / 4, y = i420.height / 2)
                if (i420.width == FRAME_WIDTH && i420.height == FRAME_HEIGHT && right - left > MIN_LUMA_DELTA) {
                    leftLuma.set(left)
                    rightLuma.set(right)
                    frameLatch.countDown()
                }
            } catch (e: RuntimeException) {
                sinkFailure.compareAndSet(null, e.message ?: "Receiver sink failed while reading frame")
            } finally {
                i420.release()
            }
        }

    private fun createLumaPatternBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(FRAME_WIDTH, FRAME_HEIGHT, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT)
        for (y in 0 until FRAME_HEIGHT) {
            for (x in 0 until FRAME_WIDTH) {
                pixels[(y * FRAME_WIDTH) + x] = if (x < FRAME_WIDTH / 2) {
                    Color.rgb(24, 32, 48)
                } else {
                    Color.rgb(224, 232, 240)
                }
            }
        }
        bitmap.setPixels(pixels, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT)
        return bitmap
    }

    private fun sampleLuma(buffer: VideoFrame.I420Buffer, x: Int, y: Int): Int {
        val data = buffer.dataY.duplicate()
        val offset = (y * buffer.strideY) + x
        return data.get(offset).toInt() and 0xff
    }

    private fun randomCameraToken(): String =
        UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private fun syntheticLiveFrame(): LiveFrame {
        val width = 16
        val height = 16
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        return LiveFrame(
            timestampNanos = SystemClock.elapsedRealtimeNanos(),
            capturedAtMillis = System.currentTimeMillis(),
            width = width,
            height = height,
            rotationDegrees = 0,
            format = ImageFormat.YUV_420_888,
            planes = listOf(
                LiveFrame.Plane(ByteArray(width * height) { 96.toByte() }, rowStride = width, pixelStride = 1),
                LiveFrame.Plane(ByteArray(chromaWidth * chromaHeight) { 128.toByte() }, rowStride = chromaWidth, pixelStride = 1),
                LiveFrame.Plane(ByteArray(chromaWidth * chromaHeight) { 128.toByte() }, rowStride = chromaWidth, pixelStride = 1)
            )
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
        }
        assertTrue("Timed out waiting for condition", condition())
    }

    private fun eventUpload(
        eventId: String,
        probNormal: Float = EVENT_PROB_NORMAL,
        probFall: Float = EVENT_PROB_FALL,
        probFallen: Float = EVENT_PROB_FALLEN
    ): DetectionEventUpload =
        DetectionEventUpload(
            clientEventId = eventId,
            enqueuedAtElapsedMillis = android.os.SystemClock.elapsedRealtime(),
            occurredAt = EVENT_OCCURRED_AT,
            kind = EVENT_KIND,
            rule = EVENT_RULE,
            state = EVENT_STATE,
            probNormal = probNormal,
            probFall = probFall,
            probFallen = probFallen,
            durationSec = EVENT_DURATION_SEC
        )

    private fun eventBody(event: DetectionEventUpload): JSONObject =
        JSONObject()
            .put("eventId", event.clientEventId)
            .put("occurredAt", event.occurredAt)
            .put("eventType", event.kind)
            .put("kind", event.kind)
            .put("rule", event.rule)
            .put("state", event.state)
            .put("probNormal", event.probNormal)
            .put("probFall", event.probFall)
            .put("probFallen", event.probFallen)
            .put("durationSec", event.durationSec)
            .put("score", (event.probFall + event.probFallen).coerceIn(0f, 1f))
            .put("severity", "CRITICAL")
            .put("keypointsJson", "{}")

    private fun com.example.careguardcamera.data.LiveSignalList.singleSignal(
        from: String,
        kind: String,
        generation: Long
    ): LiveSignal =
        items.single { it.from == from && it.kind == kind && it.generation == generation }

    private class FakeLowercaseFallEngine : LiveFallModelEngine {
        override fun analyze(bitmap: Bitmap, captureTimestampMs: Long): FallInferenceResult =
            FallInferenceResult(
                captureTimestampMs = captureTimestampMs,
                state = "fall",
                probabilities = floatArrayOf(0.03f, 0.87f, 0.10f),
                events = listOf(
                    FallInferenceEvent(
                        kind = "fall",
                        rule = "model",
                        timestampMs = captureTimestampMs,
                        state = "fall",
                        probabilities = floatArrayOf(0.03f, 0.87f, 0.10f)
                    )
                ),
                keypoints = null
            )

        override fun close() = Unit
    }

    private data class Fixture(
        val patientId: String,
        val guardianUserId: String,
        val cameraId: String,
        val cameraToken: String
    )

    private companion object {
        const val EVENT_OCCURRED_AT = "2026-10-03T09:15:00+09:00"
        const val EVENT_KIND = "FALLEN_IMMOBILE"
        const val EVENT_RULE = "R1"
        const val EVENT_STATE = "FALLEN"
        const val EVENT_PROB_NORMAL = 0.04f
        const val EVENT_PROB_FALL = 0.81f
        const val EVENT_PROB_FALLEN = 0.10f
        const val EVENT_DURATION_SEC = 3.0f
        const val FRAME_WIDTH = 160
        const val FRAME_HEIGHT = 120
        const val MIN_LUMA_DELTA = 40
    }
}
