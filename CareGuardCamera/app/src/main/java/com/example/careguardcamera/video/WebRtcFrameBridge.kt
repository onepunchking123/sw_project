package com.example.careguardcamera.video

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.DefaultVideoDecoderFactory
import livekit.org.webrtc.DefaultVideoEncoderFactory
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSource

/**
 * External frame source for the live-video spike.
 *
 * CameraX remains the only camera owner. Its analyzer can copy timestamped frames into this bridge
 * and keep local AI inference independent from any peer/viewer lifecycle.
 */
class WebRtcFrameBridge private constructor(
    val videoSource: VideoSource,
    private val capturerObserver: CapturerObserver
) : Closeable {
    private val closed = AtomicBoolean(false)

    init {
        capturerObserver.onCapturerStarted(true)
    }

    @Synchronized
    fun captureI420(
        width: Int,
        height: Int,
        dataY: ByteBuffer,
        strideY: Int,
        dataU: ByteBuffer,
        strideU: Int,
        dataV: ByteBuffer,
        strideV: Int,
        rotationDegrees: Int,
        timestampNs: Long
    ) {
        checkOpen()
        require(width > 0 && height > 0) { "width and height must be positive" }
        requireValidRotation(rotationDegrees)
        require(strideY >= width) { "strideY must be >= width" }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        require(strideU >= chromaWidth) { "strideU must be >= chroma width" }
        require(strideV >= chromaWidth) { "strideV must be >= chroma width" }

        val buffer = JavaI420Buffer.allocate(width, height)
        var delivered = false
        try {
            copyPlane(dataY, width, height, strideY, buffer.dataY, buffer.strideY)
            copyPlane(dataU, chromaWidth, chromaHeight, strideU, buffer.dataU, buffer.strideU)
            copyPlane(dataV, chromaWidth, chromaHeight, strideV, buffer.dataV, buffer.strideV)
            delivered = true
            deliver(buffer, rotationDegrees, timestampNs)
        } finally {
            if (!delivered) buffer.release()
        }
    }

    @Synchronized
    fun captureBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        timestampNs: Long
    ) {
        checkOpen()
        requireValidRotation(rotationDegrees)
        require(!bitmap.isRecycled) { "bitmap must not be recycled" }
        require(bitmap.width > 0 && bitmap.height > 0) { "bitmap must be non-empty" }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val buffer = JavaI420Buffer.allocate(width, height)
        var delivered = false
        try {
            writeBitmapAsI420(pixels, width, height, buffer)
            delivered = true
            deliver(buffer, rotationDegrees, timestampNs)
        } finally {
            if (!delivered) buffer.release()
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            capturerObserver.onCapturerStopped()
            videoSource.dispose()
        }
    }

    private fun deliver(buffer: VideoFrame.Buffer, rotationDegrees: Int, timestampNs: Long) {
        val frame = VideoFrame(buffer, rotationDegrees, timestampNs)
        try {
            capturerObserver.onFrameCaptured(frame)
        } finally {
            frame.release()
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "WebRtcFrameBridge is closed" }
    }

    companion object {
        private val initialized = AtomicBoolean(false)
        private val eglBase by lazy { EglBase.create() }

        /** 디코더가 만든 텍스처 프레임을 그리려면 렌더러도 이 컨텍스트로 초기화해야 한다. 다른 컨텍스트면 검은 화면만 나온다. */
        val sharedEglContext: EglBase.Context
            get() = eglBase.eglBaseContext

        fun initialize(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
            }
        }

        fun createFactory(context: Context): PeerConnectionFactory {
            initialize(context)
            val eglContext = eglBase.eglBaseContext
            return PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
                .createPeerConnectionFactory()
        }

        fun create(factory: PeerConnectionFactory, isScreencast: Boolean = false): WebRtcFrameBridge {
            val source = factory.createVideoSource(isScreencast)
            return WebRtcFrameBridge(source, source.capturerObserver)
        }

        private fun requireValidRotation(rotationDegrees: Int) {
            require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
                "rotationDegrees must be one of 0, 90, 180, or 270"
            }
        }

        private fun copyPlane(
            source: ByteBuffer,
            width: Int,
            height: Int,
            sourceStride: Int,
            destination: ByteBuffer,
            destinationStride: Int
        ) {
            val sourceCopy = source.duplicate()
            val sourceStart = sourceCopy.position()
            val sourceLimit = sourceCopy.limit()
            val requiredSourceLimit = sourceStart + ((height - 1) * sourceStride) + width
            require(requiredSourceLimit <= sourceLimit) { "source plane is smaller than width/height/stride" }
            require(destination.capacity() >= ((height - 1) * destinationStride) + width) {
                "destination plane is smaller than width/height/stride"
            }

            for (row in 0 until height) {
                val sourceOffset = sourceStart + (row * sourceStride)
                val destinationOffset = row * destinationStride
                for (col in 0 until width) {
                    destination.put(destinationOffset + col, sourceCopy.get(sourceOffset + col))
                }
            }
        }

        private fun writeBitmapAsI420(
            pixels: IntArray,
            width: Int,
            height: Int,
            buffer: JavaI420Buffer
        ) {
            val dataY = buffer.dataY
            val dataU = buffer.dataU
            val dataV = buffer.dataV
            val strideY = buffer.strideY
            val strideU = buffer.strideU
            val strideV = buffer.strideV

            for (row in 0 until height) {
                for (col in 0 until width) {
                    val color = pixels[(row * width) + col]
                    val red = (color shr 16) and 0xff
                    val green = (color shr 8) and 0xff
                    val blue = color and 0xff

                    val y = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                    dataY.put((row * strideY) + col, clampToByte(y))

                    if (row % 2 == 0 && col % 2 == 0) {
                        val u = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                        val v = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                        val chromaRow = row / 2
                        val chromaCol = col / 2
                        dataU.put((chromaRow * strideU) + chromaCol, clampToByte(u))
                        dataV.put((chromaRow * strideV) + chromaCol, clampToByte(v))
                    }
                }
            }
        }

        private fun clampToByte(value: Int): Byte =
            value.coerceIn(0, 255).toByte()
    }
}
