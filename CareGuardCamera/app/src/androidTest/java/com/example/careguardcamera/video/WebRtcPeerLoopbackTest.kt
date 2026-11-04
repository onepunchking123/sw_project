package com.example.careguardcamera.video

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRtcPeerLoopbackTest {
    @Test
    fun sendsRealFrameBetweenLocalPeers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = WebRtcFrameBridge.createFactory(context)
        val bridge = WebRtcFrameBridge.create(factory)
        val receivedFrame = CountDownLatch(1)
        val connectedState = CountDownLatch(1)
        val receivedWidth = AtomicInteger(0)
        val receivedHeight = AtomicInteger(0)
        val leftLuma = AtomicInteger(0)
        val rightLuma = AtomicInteger(0)
        val sinkFailure = AtomicReference<String?>()

        val receiverSink = VideoSink { frame: VideoFrame ->
            val i420 = frame.buffer.toI420()
            if (i420 == null) {
                sinkFailure.compareAndSet(null, "Receiver frame could not convert to I420")
                return@VideoSink
            }
            try {
                val width = i420.width
                val height = i420.height
                val left = sampleLuma(i420, x = width / 4, y = height / 2)
                val right = sampleLuma(i420, x = (width * 3) / 4, y = height / 2)
                if (width == FRAME_WIDTH && height == FRAME_HEIGHT && right - left > MIN_LUMA_DELTA) {
                    receivedWidth.set(width)
                    receivedHeight.set(height)
                    leftLuma.set(left)
                    rightLuma.set(right)
                    receivedFrame.countDown()
                }
            } catch (e: RuntimeException) {
                sinkFailure.compareAndSet(null, e.message ?: "Receiver sink failed while reading frame")
            } finally {
                i420.release()
            }
        }

        val receiverPeerRef = AtomicReference<WebRtcPeer>()
        val senderPeerRef = AtomicReference<WebRtcPeer>()
        val senderCallbacks = loopbackCallbacks(receiverPeerRef, connectedState)
        val receiverCallbacks = loopbackCallbacks(senderPeerRef, connectedState)

        val sender = WebRtcPeer.createSender(factory, bridge.videoSource, senderCallbacks)
        val receiver = WebRtcPeer.createReceiver(factory, receiverSink, receiverCallbacks)
        receiverPeerRef.set(receiver)
        senderPeerRef.set(sender)

        try {
            val offer = sender.createOffer()
            receiver.setRemoteDescription(offer.type, offer.sdp)
            val answer = receiver.createAnswer()
            sender.setRemoteDescription(answer.type, answer.sdp)

            val bitmap = createLumaPatternBitmap()
            try {
                waitUntil(timeoutMs = 10_000L) {
                    bridge.captureBitmap(bitmap, rotationDegrees = 0, timestampNs = SystemClock.elapsedRealtimeNanos())
                    receivedFrame.await(250L, TimeUnit.MILLISECONDS)
                }
            } finally {
                bitmap.recycle()
            }

            assertTrue("Local WebRTC peers did not connect", connectedState.await(10L, TimeUnit.SECONDS))
            sinkFailure.get()?.let { throw AssertionError(it) }
            assertTrue("Receiver did not observe a video frame", receivedFrame.await(1L, TimeUnit.SECONDS))
            assertEquals(FRAME_WIDTH, receivedWidth.get())
            assertEquals(FRAME_HEIGHT, receivedHeight.get())
            assertTrue("Received frame did not preserve luma pattern", rightLuma.get() - leftLuma.get() > MIN_LUMA_DELTA)
        } finally {
            sender.close()
            receiver.close()
            bridge.close()
            factory.dispose()
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

    private fun loopbackCallbacks(
        remotePeer: AtomicReference<WebRtcPeer>,
        connectedState: CountDownLatch
    ): WebRtcPeerCallbacks =
        object : WebRtcPeerCallbacks {
            override fun onLocalIceCandidate(candidate: WebRtcIceCandidate) {
                remotePeer.get()?.addRemoteIce(candidate)
            }

            override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                    connectedState.countDown()
                }
            }
        }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
        }
        assertTrue("Timed out waiting for condition", condition())
    }

    private companion object {
        const val FRAME_WIDTH = 160
        const val FRAME_HEIGHT = 120
        const val MIN_LUMA_DELTA = 40
    }
}
