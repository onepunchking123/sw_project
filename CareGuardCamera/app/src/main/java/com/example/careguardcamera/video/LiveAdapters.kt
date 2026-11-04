package com.example.careguardcamera.video

import android.os.SystemClock
import com.example.careguardcamera.data.DetectionEventUpload
import com.example.careguardcamera.data.LiveSessionApi
import com.example.careguardcamera.data.LiveSessionIdentity
import com.example.careguardcamera.data.LiveSessionStatus
import com.example.careguardcamera.data.PrivacyMode
import com.example.careguardcamera.Keypoint
import com.example.careguardcamera.inference.LiveFallModelEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class OverlayPose(
    val keypoints: Array<Keypoint>,
    val timestampNanos: Long,
    val width: Int,
    val height: Int
)

class LatestOverlayPose {
    @Volatile
    private var pose: OverlayPose? = null

    fun update(next: OverlayPose?) {
        pose = next
    }

    fun freshFor(frame: LiveFrame, maxAgeNanos: Long = 1_000_000_000L): OverlayPose? {
        val current = pose ?: return null
        val uprightWidth = if (frame.rotationDegrees % 180 == 0) frame.width else frame.height
        val uprightHeight = if (frame.rotationDegrees % 180 == 0) frame.height else frame.width
        if (current.width != uprightWidth || current.height != uprightHeight) return null
        if (frame.timestampNanos - current.timestampNanos !in 0..maxAgeNanos) return null
        return current
    }
}

class FallModelInferenceAdapter(
    private val engine: LiveFallModelEngine,
    private val overlayPose: LatestOverlayPose
) : LocalInferenceEngine {
    override suspend fun onFrame(frame: LiveFrame): List<DetectionEventUpload> {
        val bitmap = frame.toBitmap()
        return try {
            val frameMonotonicMs = frame.timestampNanos / 1_000_000L
            val result = engine.analyze(bitmap, frameMonotonicMs)
            overlayPose.update(result.keypoints?.takeIf { keypoints -> keypoints.any { it.conf > 0f } }?.let {
                OverlayPose(it, frame.timestampNanos, bitmap.width, bitmap.height)
            })
            result.events.map { event ->
                val probabilities = event.probabilities
                DetectionEventUpload(
                    clientEventId = UUID.randomUUID().toString(),
                    occurredAt = iso(frame.capturedAtMillis + (event.timestampMs - frameMonotonicMs)),
                    enqueuedAtElapsedMillis = SystemClock.elapsedRealtime(),
                    kind = event.kind.uppercase(Locale.ROOT),
                    rule = event.rule.uppercase(Locale.ROOT),
                    state = event.state.uppercase(Locale.ROOT),
                    probNormal = probabilities.getOrElse(0) { 0f },
                    probFall = probabilities.getOrElse(1) { 0f },
                    probFallen = probabilities.getOrElse(2) { 0f },
                    frameTimestampNanos = frame.timestampNanos
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun close() {
        engine.close()
    }

    private fun iso(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))
}

class WebRtcBridgeVideoPeer(
    private val factory: PeerConnectionFactory,
    private val bridge: WebRtcFrameBridge,
    private val scope: CoroutineScope,
    private val overlayPose: LatestOverlayPose,
    private val onError: (String) -> Unit = {}
) : LiveVideoPeer {
    private var peer: WebRtcPeer? = null
    private var signalJob: Job? = null
    private var api: LiveSessionApi? = null
    private var identity: LiveSessionIdentity? = null
    private var generation: Long = 0L
    @Volatile
    private var suppressPeerState: Boolean = false
    private var statusListener: (LiveSessionStatus) -> Unit = {}

    override fun setStatusListener(listener: (LiveSessionStatus) -> Unit) {
        statusListener = listener
    }

    override suspend fun onSessionStarted(api: LiveSessionApi, identity: LiveSessionIdentity) {
        this.api = api
        this.identity = identity
        signalJob = scope.launch { pollCameraSignals() }
    }

    private suspend fun startNegotiation(nextGeneration: Long) {
        val currentApi = api ?: return
        val currentIdentity = identity ?: return
        if (nextGeneration <= generation) return
        generation = nextGeneration
        statusListener(LiveSessionStatus.NEGOTIATING)
        suppressPeerState = true
        peer?.close()
        suppressPeerState = false
        val callbacks = object : WebRtcPeerCallbacks {
            override fun onLocalIceCandidate(candidate: WebRtcIceCandidate) {
                scope.launch {
                    try {
                        currentApi.sendCameraSignal(
                            currentIdentity.cameraId,
                            currentIdentity.sessionId,
                            "ICE",
                            JSONObject()
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                .put("candidate", candidate.candidate)
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(readable(e))
                    }
                }
            }

            override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
                if (suppressPeerState) return
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> statusListener(LiveSessionStatus.CONNECTED)
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> statusListener(LiveSessionStatus.NO_VIEWER)
                    else -> Unit
                }
            }
        }
        val createdPeer = WebRtcPeer.createSender(factory, bridge.videoSource, callbacks)
        peer = createdPeer
        try {
            val offer = createdPeer.createOffer()
            currentApi.sendCameraSignal(
                currentIdentity.cameraId,
                currentIdentity.sessionId,
                "OFFER",
                JSONObject().put("type", offer.type).put("sdp", offer.sdp)
            )
        } catch (e: CancellationException) {
            createdPeer.close()
            if (peer === createdPeer) peer = null
            statusListener(LiveSessionStatus.NO_VIEWER)
            throw e
        } catch (e: Exception) {
            createdPeer.close()
            if (peer === createdPeer) peer = null
            statusListener(LiveSessionStatus.NO_VIEWER)
            throw e
        }
    }

    override suspend fun onFrame(frame: LiveFrame, privacyMode: PrivacyMode): Boolean {
        if (privacyMode == PrivacyMode.SKELETON_ONLY) return false
        val pose = overlayPose.freshFor(frame)
        if (privacyMode == PrivacyMode.VIDEO_SKELETON && pose == null) return false
        val bitmap = frame.toBitmap()
        try {
            val output = when (privacyMode) {
                PrivacyMode.NO_OVERLAY -> bitmap
                PrivacyMode.VIDEO_SKELETON -> bitmap.withSkeletonOverlay(requireNotNull(pose))
                PrivacyMode.SKELETON_ONLY -> return false
            }
            try {
                bridge.captureBitmap(output, 0, frame.timestampNanos)
            } finally {
                if (output !== bitmap) output.recycle()
            }
            return true
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun close() {
        signalJob?.cancel()
        signalJob = null
        suppressPeerState = true
        peer?.close()
        suppressPeerState = false
        peer = null
        generation = 0L
    }

    fun dispose() {
        bridge.close()
    }

    private suspend fun pollCameraSignals() {
        var cursor = 0L
        while (scope.isActive) {
            try {
                val currentApi = api ?: return
                val currentIdentity = identity ?: return
                val signals = currentApi.listCameraSignals(currentIdentity.cameraId, currentIdentity.sessionId, cursor)
                cursor = signals.nextCursor
                signals.items.forEach { signal ->
                    when (signal.kind) {
                        "VIEWER_JOINED" -> if (signal.generation > generation) {
                            startNegotiation(signal.generation)
                        }
                        "VIEWER_LEFT" -> if (signal.generation == generation) {
                            suppressPeerState = true
                            peer?.close()
                            suppressPeerState = false
                            peer = null
                            statusListener(LiveSessionStatus.NO_VIEWER)
                        }
                        "ANSWER" -> if (signal.from == "GUARDIAN" && signal.generation == generation) {
                            peer?.setRemoteDescription(signal.payload.optString("type", "answer"), signal.payload.optString("sdp"))
                        }
                        "ICE" -> if (signal.from == "GUARDIAN" && signal.generation == generation) peer?.addRemoteIce(
                            WebRtcIceCandidate(
                                sdpMid = signal.payload.optString("sdpMid").takeIf { it.isNotBlank() && it != "null" },
                                sdpMLineIndex = signal.payload.optInt("sdpMLineIndex"),
                                candidate = signal.payload.optString("candidate")
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(readable(e))
            }
            delay(2_000L)
        }
    }

    private fun readable(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: "WebRTC 시그널링 오류가 발생했습니다."
}
