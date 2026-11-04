package com.example.careguardcamera.video

import com.example.careguardcamera.data.LiveSessionApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.VideoSink
import org.json.JSONObject

class GuardianWebRtcViewer(
    private val api: LiveSessionApi,
    private val factory: PeerConnectionFactory,
    private val patientId: String,
    private val sessionId: String,
    private val guardianUserId: String,
    private val videoSink: VideoSink,
    private val scope: CoroutineScope,
    private val onState: (String) -> Unit
) {
    private var peer: WebRtcPeer? = null
    private var viewerId: String? = null
    private var generation: Long = 0L
    private var signalJob: Job? = null

    suspend fun start() {
        val join = api.joinGuardianSession(patientId, sessionId, guardianUserId)
        viewerId = join.viewerId
        generation = join.generation
        val callbacks = object : WebRtcPeerCallbacks {
            override fun onLocalIceCandidate(candidate: WebRtcIceCandidate) {
                val currentViewer = viewerId ?: return
                scope.launch {
                    try {
                        api.sendGuardianSignal(
                            patientId,
                            sessionId,
                            currentViewer,
                            "ICE",
                            JSONObject()
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                .put("candidate", candidate.candidate)
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        onState("RETRYING")
                    }
                }
            }

            override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
                onState(state.name)
            }
        }
        peer = WebRtcPeer.createReceiver(factory, videoSink, callbacks)
        signalJob = scope.launch { pollSignals() }
    }

    suspend fun stop() {
        signalJob?.cancel()
        signalJob = null
        peer?.close()
        peer = null
        val currentViewer = viewerId
        viewerId = null
        if (currentViewer != null) {
            runCatching { api.leaveGuardianSession(patientId, sessionId, currentViewer) }
        }
    }

    private suspend fun pollSignals() {
        var cursor = 0L
        while (scope.isActive) {
            try {
                val currentViewer = viewerId ?: return
                val signals = api.listGuardianSignals(patientId, sessionId, currentViewer, cursor)
                cursor = signals.nextCursor
                signals.items.filter { it.from == "CAMERA" && it.generation == generation }.forEach { signal ->
                    when (signal.kind) {
                        "OFFER" -> {
                            val currentPeer = peer ?: return@forEach
                            currentPeer.setRemoteDescription(signal.payload.optString("type", "offer"), signal.payload.optString("sdp"))
                            val answer = currentPeer.createAnswer()
                            api.sendGuardianSignal(
                                patientId,
                                sessionId,
                                currentViewer,
                                "ANSWER",
                                JSONObject().put("type", answer.type).put("sdp", answer.sdp)
                            )
                        }
                        "ICE" -> peer?.addRemoteIce(
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
            } catch (_: Exception) {
                onState("RETRYING")
            }
            delay(2_000L)
        }
    }
}
