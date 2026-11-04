package com.example.careguardcamera.video

import android.os.SystemClock
import com.example.careguardcamera.data.ApiHttpException
import com.example.careguardcamera.data.DetectionEventUpload
import com.example.careguardcamera.data.InferenceMode
import com.example.careguardcamera.data.LiveSessionApi
import com.example.careguardcamera.data.LiveSessionIdentity
import com.example.careguardcamera.data.LiveSessionStatus
import com.example.careguardcamera.data.PrivacyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class LiveCameraSessionState(
    val status: LiveSessionStatus = LiveSessionStatus.IDLE,
    val sessionId: String? = null,
    val privacyMode: PrivacyMode = PrivacyMode.VIDEO_SKELETON,
    val privacyRevision: Long = 0L,
    val viewerConnected: Boolean = false,
    val framesSeen: Long = 0L,
    val lastFrameTimestampNanos: Long? = null,
    val droppedVideoFrames: Int = 0,
    val droppedEventUploads: Int = 0,
    val lastError: String? = null
)

interface LocalInferenceEngine {
    suspend fun onFrame(frame: LiveFrame): List<DetectionEventUpload>
}

/** 컨트롤러가 쓰는 프레임 공급원. 실제 구현은 [LiveCaptureSession]이다. */
interface LiveFrameSource {
    val frames: SharedFlow<LiveFrame>
    suspend fun start()
    suspend fun stop()
}

interface LiveVideoPeer {
    fun setStatusListener(listener: (LiveSessionStatus) -> Unit) = Unit
    suspend fun onSessionStarted(api: LiveSessionApi, identity: LiveSessionIdentity) = Unit
    suspend fun onFrame(frame: LiveFrame, privacyMode: PrivacyMode): Boolean
    suspend fun close()
}

class EventRetryPolicy(
    private val maxAttempts: Int = 5,
    private val maxAgeMillis: Long = 120_000L,
    private val delaysMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L)
) {
    fun shouldRetry(attempt: Int, ageMillis: Long): Boolean =
        attempt <= maxAttempts && ageMillis <= maxAgeMillis

    fun delayForAttempt(attempt: Int): Long =
        (delaysMillis.getOrElse((attempt - 1).coerceAtLeast(0)) { delaysMillis.last() } + Random.nextLong(0L, 250L))
}

class LiveSessionController(
    private val api: LiveSessionApi,
    private val captureSession: LiveFrameSource,
    private val inference: LocalInferenceEngine,
    private val peer: LiveVideoPeer? = null,
    private val scope: CoroutineScope,
    private val retryPolicy: EventRetryPolicy = EventRetryPolicy(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val nowElapsedMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val _state = MutableStateFlow(LiveCameraSessionState())
    val state: StateFlow<LiveCameraSessionState> = _state

    private val _fallIncidents = MutableSharedFlow<DetectionEventUpload>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** 쓰러진 채 3초가 지난(R1) 낙상. 서버는 같은 이벤트로 긴급 상황을 연다. */
    val fallIncidents: SharedFlow<DetectionEventUpload> = _fallIncidents

    private var jobs: List<Job> = emptyList()
    private var identity: LiveSessionIdentity? = null
    private var cameraId: String? = null
    private var epoch: Long = 0L
    private var lastLeaseSuccessElapsedMillis: Long = 0L
    private var pendingEventUploads: Int = 0
    private var videoPeerOpen: Boolean = false
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mediaDispatcher = Dispatchers.Default.limitedParallelism(1)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var eventUploads = Channel<DetectionEventUpload>(
        capacity = 20,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    suspend fun start(cameraId: String, privacyMode: PrivacyMode) {
        if (identity != null || jobs.isNotEmpty()) stop()
        val sessionEpoch = ++epoch
        if (privacyMode == PrivacyMode.SKELETON_ONLY) {
            _state.value = LiveCameraSessionState(
                status = LiveSessionStatus.PRIVACY_MODE_UNSUPPORTED,
                privacyMode = privacyMode,
                lastError = "골격만 모드는 현재 원본 영상 송출을 차단하고 감지만 유지합니다."
            )
        }
        val session = api.createCameraSession(cameraId, InferenceMode.PHONE, privacyMode)
        if (sessionEpoch != epoch) {
            runCatching { api.endCameraSession(session.sessionId) }
            return
        }
        this.cameraId = cameraId
        pendingEventUploads = 0
        videoPeerOpen = false
        eventUploads = Channel(capacity = 20, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        activate(session)
        captureSession.start()
        peer?.setStatusListener { status ->
            scope.launch {
                _state.value = _state.value.copy(
                    status = status,
                    viewerConnected = status == LiveSessionStatus.CONNECTED
                )
            }
        }
        val peerStartJob = startTransportIfAllowed(sessionEpoch, session)
        jobs = listOfNotNull(
            peerStartJob,
            scope.launch { observeFrames(sessionEpoch) },
            scope.launch { consumeMediaFrames(sessionEpoch) },
            scope.launch { consumeInferenceFrames(sessionEpoch) },
            scope.launch { uploadEvents(sessionEpoch) },
            scope.launch { renewLeaseLoop(sessionEpoch) },
            scope.launch { leaseWatchdog(sessionEpoch) }
        )
    }

    suspend fun stop() {
        epoch++
        eventUploads.close()
        val currentJob = coroutineContext[Job]
        val toJoin = jobs.filter { it != currentJob }
        toJoin.forEach { it.cancel() }
        jobs = emptyList()
        captureSession.stop()
        closeTransport(force = true)
        toJoin.forEach { runCatching { it.cancelAndJoin() } }
        val session = identity
        identity = null
        if (session != null) {
            runCatching { withTimeout(2_000L) { api.endCameraSession(session.sessionId) } }
        }
        _state.value = _state.value.copy(status = LiveSessionStatus.STOPPED, sessionId = null, viewerConnected = false)
    }

    private suspend fun observeFrames(sessionEpoch: Long) {
        try {
            captureSession.frames.collect { frame ->
                if (sessionEpoch != epoch) return@collect
                _state.value = _state.value.copy(
                    framesSeen = _state.value.framesSeen + 1,
                    lastFrameTimestampNanos = frame.timestampNanos
                )
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    private suspend fun consumeMediaFrames(sessionEpoch: Long) {
        try {
            captureSession.frames.collect { frame ->
                if (sessionEpoch != epoch) return@collect
                val mode = _state.value.privacyMode
                if (mode != PrivacyMode.SKELETON_ONLY && videoPeerOpen) {
                    val sent = withContext(mediaDispatcher) { peer?.onFrame(frame, mode) ?: false }
                    if (sent && _state.value.status == LiveSessionStatus.PRIVACY_MODE_UNSUPPORTED) {
                        _state.value = _state.value.copy(
                            status = if (_state.value.viewerConnected) LiveSessionStatus.CONNECTED else LiveSessionStatus.NO_VIEWER,
                            lastError = null
                        )
                    } else if (!sent && mode == PrivacyMode.VIDEO_SKELETON) {
                        _state.value = _state.value.copy(
                            droppedVideoFrames = _state.value.droppedVideoFrames + 1,
                            status = LiveSessionStatus.PRIVACY_MODE_UNSUPPORTED,
                            lastError = "최근 골격 좌표가 없어 원본 영상 프레임을 전송하지 않았습니다."
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            videoPeerOpen = false
            _state.value = _state.value.copy(status = LiveSessionStatus.RETRYING, lastError = readable(e))
        }
    }

    private suspend fun consumeInferenceFrames(sessionEpoch: Long) {
        try {
            captureSession.frames.collect { frame ->
                if (sessionEpoch != epoch) return@collect
                withContext(inferenceDispatcher) { inference.onFrame(frame) }.forEach { event ->
                    enqueueEventUpload(event)
                    if (event.kind == "FALLEN_IMMOBILE" && event.rule == "R1") _fallIncidents.tryEmit(event)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = _state.value.copy(status = LiveSessionStatus.MODEL_INCOMPATIBLE, lastError = readable(e))
        }
    }

    private suspend fun renewLeaseLoop(sessionEpoch: Long) {
        while (scope.isActive) {
            delay(5_000L)
            if (sessionEpoch != epoch) return
            val session = identity
            if (session == null) {
                if (!recreateSession(sessionEpoch)) return
                continue
            }
            try {
                val lease = withTimeout(10_000L) { api.renewCameraLease(session.sessionId) }
                if (sessionEpoch != epoch) return
                if (lease.terminated) {
                    _state.value = _state.value.copy(status = LiveSessionStatus.STOPPED, lastError = "서버에서 세션을 종료했습니다.")
                    stop()
                    return
                }
                val currentRevision = _state.value.privacyRevision
                if (lease.privacyRevision != currentRevision) {
                    closeTransport(force = true)
                    _state.value = _state.value.copy(
                        status = LiveSessionStatus.STOPPED,
                        lastError = "개인정보 설정이 변경되어 라이브 세션을 다시 시작해야 합니다."
                    )
                    stop()
                    return
                }
                if (lease.privacyMode == PrivacyMode.SKELETON_ONLY) {
                    closeTransport()
                } else if (!videoPeerOpen) {
                    startTransportIfAllowed(sessionEpoch, session)
                }
                lastLeaseSuccessElapsedMillis = nowElapsedMillis()
                val current = _state.value
                val recovered = current.status == LiveSessionStatus.RETRYING
                _state.value = current.copy(
                    privacyMode = lease.privacyMode,
                    privacyRevision = lease.privacyRevision,
                    status = if (recovered) settledStatus(lease.privacyMode, current.viewerConnected) else current.status,
                    lastError = if (recovered) null else current.lastError
                )
            } catch (e: TimeoutCancellationException) {
                // withTimeout의 시간 초과도 CancellationException이라 먼저 잡지 않으면 갱신 루프가 조용히 끝난다.
                markRetrying("서버 응답이 늦어 연결을 다시 확인하고 있습니다.")
                closeTransport()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiHttpException) {
                markRetrying(readable(e))
                when (e.code) {
                    401, 403, 409 -> {
                        stop()
                        return
                    }
                    // 서버가 세션을 만료·폐기했다. 촬영과 감지는 유지하고 다음 주기에 새 세션을 만든다.
                    404, 410 -> {
                        identity = null
                        closeTransport(force = true)
                    }
                    else -> closeTransport()
                }
            } catch (e: Exception) {
                markRetrying(readable(e))
                closeTransport()
            }
        }
    }

    /** 서버에서 사라진 세션을 다시 만든다. 인증이 거부돼 더 시도할 수 없으면 false. */
    private suspend fun recreateSession(sessionEpoch: Long): Boolean {
        val id = cameraId ?: return false
        try {
            val session = withTimeout(10_000L) { api.createCameraSession(id, InferenceMode.PHONE, _state.value.privacyMode) }
            if (sessionEpoch != epoch) {
                runCatching { api.endCameraSession(session.sessionId) }
                return false
            }
            activate(session)
            startTransportIfAllowed(sessionEpoch, session)
        } catch (e: TimeoutCancellationException) {
            markRetrying("서버 응답이 늦어 연결을 다시 확인하고 있습니다.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiHttpException) {
            markRetrying(readable(e))
            if (e.code == 401 || e.code == 403) {
                stop()
                return false
            }
        } catch (e: Exception) {
            markRetrying(readable(e))
        }
        return true
    }

    private fun activate(session: LiveSessionIdentity) {
        identity = session
        lastLeaseSuccessElapsedMillis = nowElapsedMillis()
        _state.value = _state.value.copy(
            status = settledStatus(session.privacyMode, viewerConnected = false),
            sessionId = session.sessionId,
            privacyMode = session.privacyMode,
            privacyRevision = session.privacyRevision,
            viewerConnected = false,
            lastError = if (session.privacyMode == PrivacyMode.SKELETON_ONLY) {
                "골격만 모드는 현재 원본 영상 송출을 차단하고 감지만 유지합니다."
            } else {
                null
            }
        )
    }

    private fun settledStatus(privacyMode: PrivacyMode, viewerConnected: Boolean): LiveSessionStatus = when {
        privacyMode == PrivacyMode.SKELETON_ONLY -> LiveSessionStatus.PRIVACY_MODE_UNSUPPORTED
        viewerConnected -> LiveSessionStatus.CONNECTED
        else -> LiveSessionStatus.NO_VIEWER
    }

    private fun markRetrying(message: String) {
        _state.value = _state.value.copy(status = LiveSessionStatus.RETRYING, lastError = message)
    }

    private suspend fun leaseWatchdog(sessionEpoch: Long) {
        while (scope.isActive) {
            delay(500L)
            if (sessionEpoch != epoch) return
            // Reserve one watchdog interval so closure begins within the 10-second lease.
            if (nowElapsedMillis() - lastLeaseSuccessElapsedMillis >= 9_500L) {
                _state.value = _state.value.copy(
                    status = LiveSessionStatus.RETRYING,
                    lastError = "서버 연결을 확인할 수 없어 영상 전송을 중단했습니다. 로컬 감지는 계속됩니다."
                )
                closeTransport()
            }
        }
    }

    private suspend fun closeTransport(force: Boolean = false) {
        if (!videoPeerOpen && !force) return
        videoPeerOpen = false
        runCatching { peer?.close() }
        _state.value = _state.value.copy(viewerConnected = false)
    }

    private fun startTransportIfAllowed(sessionEpoch: Long, session: LiveSessionIdentity): Job? {
        if (sessionEpoch != epoch || peer == null || videoPeerOpen || _state.value.privacyMode == PrivacyMode.SKELETON_ONLY) return null
        videoPeerOpen = true
        return scope.launch {
            runCatching { peer.onSessionStarted(api, session) }
                .onFailure {
                    if (sessionEpoch != epoch) return@onFailure
                    videoPeerOpen = false
                    _state.value = _state.value.copy(status = LiveSessionStatus.RETRYING, lastError = readable(Exception(it)))
                }
        }
    }

    private fun enqueueEventUpload(event: DetectionEventUpload) {
        if (pendingEventUploads >= 20) {
            _state.value = _state.value.copy(
                status = LiveSessionStatus.EVENT_UPLOAD_FAILED,
                droppedEventUploads = _state.value.droppedEventUploads + 1,
                lastError = "감지 이벤트 업로드 대기열이 가득 차 오래된 이벤트를 버렸습니다."
            )
        } else {
            pendingEventUploads++
        }
        if (eventUploads.trySend(event).isFailure) {
            pendingEventUploads = (pendingEventUploads - 1).coerceAtLeast(0)
            _state.value = _state.value.copy(
                status = LiveSessionStatus.EVENT_UPLOAD_FAILED,
                droppedEventUploads = _state.value.droppedEventUploads + 1,
                lastError = "감지 이벤트 업로드 대기열에 이벤트를 추가하지 못했습니다."
            )
        }
    }

    private suspend fun uploadEvents(sessionEpoch: Long) {
        for (event in eventUploads) {
            if (sessionEpoch != epoch) return
            try {
                uploadEventWithRetry(event, sessionEpoch)
            } finally {
                pendingEventUploads = (pendingEventUploads - 1).coerceAtLeast(0)
            }
        }
    }

    private suspend fun uploadEventWithRetry(event: DetectionEventUpload, sessionEpoch: Long) {
        var attempt = 1
        while (retryPolicy.shouldRetry(attempt, nowElapsedMillis() - event.enqueuedAtElapsedMillis)) {
            if (sessionEpoch != epoch) return
            try {
                // 세션을 다시 만드는 중이면 버리지 않고 기다린다. 낙상 이벤트가 빠지면 서버가 긴급 상황을 열지 못한다.
                val session = identity ?: throw IllegalStateException("라이브 세션을 다시 연결하는 중입니다.")
                api.uploadDetectionEvent(session.sessionId, event)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiHttpException) {
                // 404/410은 세션이 만료된 것이라 새 세션으로 다시 보낸다.
                if (e.code !in setOf(404, 410, 429) && e.code !in 500..599) break
                if (!retryPolicy.shouldRetry(attempt + 1, nowElapsedMillis() - event.enqueuedAtElapsedMillis)) break
                delay(retryPolicy.delayForAttempt(attempt))
                attempt++
            } catch (_: Exception) {
                if (!retryPolicy.shouldRetry(attempt + 1, nowElapsedMillis() - event.enqueuedAtElapsedMillis)) break
                delay(retryPolicy.delayForAttempt(attempt))
                attempt++
            }
        }
        if (sessionEpoch != epoch) return
        _state.value = _state.value.copy(
            status = LiveSessionStatus.EVENT_UPLOAD_FAILED,
            lastError = "감지 이벤트 전송에 실패했습니다."
        )
    }

    private fun readable(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: "실시간 세션 오류가 발생했습니다."
}
