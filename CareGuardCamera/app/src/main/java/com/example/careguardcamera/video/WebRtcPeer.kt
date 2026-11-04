package com.example.careguardcamera.video

import com.example.careguardcamera.BuildConfig
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoSource
import livekit.org.webrtc.VideoTrack

data class WebRtcSdp(
    val type: String,
    val sdp: String
)

data class WebRtcIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String
)

interface WebRtcPeerCallbacks {
    fun onLocalSdp(description: WebRtcSdp) = Unit
    fun onLocalIceCandidate(candidate: WebRtcIceCandidate) = Unit
    fun onConnectionState(state: PeerConnection.PeerConnectionState) = Unit
}

class WebRtcPeer private constructor(
    private val peerConnection: PeerConnection,
    private val localVideoTrack: VideoTrack?,
    private val localVideoSender: RtpSender?,
    private val remoteVideoSink: VideoSink?,
    private val callbacks: WebRtcPeerCallbacks
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val remoteIceBuffer = ArrayDeque<IceCandidate>()

    suspend fun createOffer(): WebRtcSdp {
        val offer = createSdp { observer -> peerConnection.createOffer(observer, MediaConstraints()) }
        return setLocalDescription(offer)
    }

    suspend fun createAnswer(): WebRtcSdp {
        val answer = createSdp { observer -> peerConnection.createAnswer(observer, MediaConstraints()) }
        return setLocalDescription(answer)
    }

    suspend fun setRemoteDescription(type: String, sdp: String) {
        checkOpen()
        val description = SessionDescription(type.toSdpType(), sdp)
        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection.setRemoteDescription(
                object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onSetFailure(error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException(error))
                        }
                    }
                },
                description
            )
        }
        flushRemoteIce()
    }

    fun addRemoteIce(candidate: WebRtcIceCandidate) {
        checkOpen()
        val ice = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        if (peerConnection.remoteDescription == null) {
            synchronized(remoteIceBuffer) {
                remoteIceBuffer.addLast(ice)
            }
        } else {
            require(peerConnection.addIceCandidate(ice)) { "Failed to add remote ICE candidate" }
        }
    }

    fun flushRemoteIce() {
        checkOpen()
        if (peerConnection.remoteDescription == null) return
        val buffered = synchronized(remoteIceBuffer) {
            val copy = remoteIceBuffer.toList()
            remoteIceBuffer.clear()
            copy
        }
        buffered.forEach { ice ->
            require(peerConnection.addIceCandidate(ice)) { "Failed to add buffered remote ICE candidate" }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            remoteVideoSink?.let { sink ->
                peerConnection.receivers
                    .mapNotNull { it.track() as? VideoTrack }
                    .forEach { it.removeSink(sink) }
            }
            localVideoSender?.let { sender ->
                runCatching { peerConnection.removeTrack(sender) }
            }
            localVideoTrack?.dispose()
            synchronized(remoteIceBuffer) {
                remoteIceBuffer.clear()
            }
            peerConnection.close()
            peerConnection.dispose()
        }
    }

    private suspend fun createSdp(block: (SdpObserver) -> Unit): SessionDescription {
        checkOpen()
        return suspendCancellableCoroutine { continuation ->
            block(
                object : SdpObserverAdapter() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        if (continuation.isActive) continuation.resume(description)
                    }

                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException(error))
                        }
                    }
                }
            )
        }
    }

    private suspend fun setLocalDescription(description: SessionDescription): WebRtcSdp {
        checkOpen()
        suspendCancellableCoroutine<Unit> { continuation ->
            peerConnection.setLocalDescription(
                object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onSetFailure(error: String) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException(error))
                        }
                    }
                },
                description
            )
        }
        val localDescription = peerConnection.localDescription.toWebRtcSdp()
        callbacks.onLocalSdp(localDescription)
        return localDescription
    }

    private fun checkOpen() {
        check(!closed.get()) { "WebRtcPeer is closed" }
    }

    private abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private const val LOCAL_VIDEO_TRACK_ID = "careguard-video"

        fun createSender(
            factory: PeerConnectionFactory,
            videoSource: VideoSource,
            callbacks: WebRtcPeerCallbacks
        ): WebRtcPeer {
            var localTrack: VideoTrack? = null
            var sender: RtpSender? = null
            val peer = createPeerConnection(factory, callbacks, null) { peerConnection ->
                val track = factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, videoSource)
                localTrack = track
                sender = peerConnection.addTrack(track, listOf(LOCAL_VIDEO_TRACK_ID))
            }
            return WebRtcPeer(peer, localTrack, sender, null, callbacks)
        }

        fun createReceiver(
            factory: PeerConnectionFactory,
            videoSink: VideoSink,
            callbacks: WebRtcPeerCallbacks
        ): WebRtcPeer {
            val peer = createPeerConnection(factory, callbacks, videoSink) { peerConnection ->
                val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                peerConnection.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, init)
            }
            return WebRtcPeer(peer, null, null, videoSink, callbacks)
        }

        private fun createPeerConnection(
            factory: PeerConnectionFactory,
            callbacks: WebRtcPeerCallbacks,
            remoteVideoSink: VideoSink?,
            configure: (PeerConnection) -> Unit
        ): PeerConnection {
            val configuration = PeerConnection.RTCConfiguration(devIceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val observer = object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                    callbacks.onConnectionState(state)
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: IceCandidate) {
                    callbacks.onLocalIceCandidate(candidate.toWebRtcIceCandidate())
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(dataChannel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                    addRemoteSink(receiver, remoteVideoSink)
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    addRemoteSink(transceiver.receiver, remoteVideoSink)
                }
            }
            val peerConnection = requireNotNull(factory.createPeerConnection(configuration, observer)) {
                "Failed to create WebRTC peer connection"
            }
            configure(peerConnection)
            return peerConnection
        }

        /** 같은 LAN에서는 ICE 서버 없이 직접 연결한다. 디버그 빌드에 개발용 TURN이 설정된 경우에만 중계를 추가한다. */
        private fun devIceServers(): List<PeerConnection.IceServer> {
            if (BuildConfig.DEV_TURN_URL.isBlank()) return emptyList()
            return listOf(
                PeerConnection.IceServer.builder(BuildConfig.DEV_TURN_URL)
                    .setUsername(BuildConfig.DEV_TURN_USERNAME)
                    .setPassword(BuildConfig.DEV_TURN_PASSWORD)
                    .createIceServer()
            )
        }

        private fun addRemoteSink(receiver: RtpReceiver, sink: VideoSink?) {
            val track = receiver.track() as? VideoTrack ?: return
            if (sink != null) track.addSink(sink)
        }

        private fun SessionDescription.toWebRtcSdp(): WebRtcSdp =
            WebRtcSdp(type.canonicalForm(), description)

        private fun IceCandidate.toWebRtcIceCandidate(): WebRtcIceCandidate =
            WebRtcIceCandidate(sdpMid, sdpMLineIndex, sdp)

        private fun String.toSdpType(): SessionDescription.Type =
            SessionDescription.Type.fromCanonicalForm(lowercase())
    }
}
