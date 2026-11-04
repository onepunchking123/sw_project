package com.example.careguardcamera.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.careguardcamera.data.PrivacyMode
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 클립에 적용할 개인정보 모드. 라이브 세션이 서버에서 받은 값과 기기에 저장된 값 중 더 엄격한 쪽을 쓴다.
 * 보호자가 방금 설정을 바꿔 한쪽만 갱신된 경우에도 원본 영상이 새지 않게 하기 위해서다.
 */
fun clipPrivacyMode(sessionMode: PrivacyMode?, deviceMode: PrivacyMode): PrivacyMode =
    listOfNotNull(sessionMode, deviceMode).maxByOrNull { it.strictness() } ?: deviceMode

private fun PrivacyMode.strictness(): Int = when (this) {
    PrivacyMode.NO_OVERLAY -> 0
    PrivacyMode.VIDEO_SKELETON -> 1
    PrivacyMode.SKELETON_ONLY -> 2
}

class EncodedClip(val mp4: ByteArray, val durationSec: Float, val startedAtMillis: Long)

/**
 * 클립 프레임을 H.264 MP4로 만든다. 각 프레임의 실제 촬영 시각을 그대로 재생 시각으로 쓴다.
 * 개인정보 설정은 실시간 송출과 같게 따른다. 골격 표시 모드에서는 관절이 없는 프레임을 넣지 않고, 골격만 모드에서는 클립을 만들지 않는다.
 */
class Mp4ClipEncoder(
    private val workDir: File,
    private val maxLongSide: Int = 640,
    private val bitRate: Int = 1_500_000
) {
    init {
        // 인코딩 중 프로세스가 죽으면 원본 영상이 담긴 임시 파일이 남을 수 있다.
        workDir.listFiles { file -> file.name.startsWith(TEMP_PREFIX) && file.name.endsWith(".mp4") }?.forEach { it.delete() }
    }

    fun encode(frames: List<ClipFrame>, privacyMode: PrivacyMode): EncodedClip? {
        if (privacyMode == PrivacyMode.SKELETON_ONLY) return null
        val usable = frames.filter { privacyMode != PrivacyMode.VIDEO_SKELETON || it.pose != null }
            .sortedBy { it.timestampNanos }
        if (usable.size < 2) return null

        val first = decodeJpegFrame(usable.first().jpeg, usable.first().rotationDegrees)
        val scale = minOf(1f, maxLongSide.toFloat() / max(first.width, first.height))
        val width = evenSize(first.width * scale)
        val height = evenSize(first.height * scale)
        first.recycle()

        val output = File.createTempFile(TEMP_PREFIX, ".mp4", workDir)
        try {
            encodeTo(output, usable, privacyMode, width, height)
            val startNanos = usable.first().timestampNanos
            val durationSec = (usable.last().timestampNanos - startNanos) / 1_000_000_000f
            return EncodedClip(output.readBytes(), durationSec, usable.first().capturedAtMillis)
        } finally {
            output.delete()
        }
    }

    private fun encodeTo(output: File, frames: List<ClipFrame>, privacyMode: PrivacyMode, width: Int, height: Int) {
        val fps = estimateFps(frames)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val startNanos = frames.first().timestampNanos
            val argb = IntArray(width * height)

            fun drain(endOfStream: Boolean) {
                var waits = 0
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (endOfStream) DRAIN_TIMEOUT_US else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            check(++waits < INPUT_RETRIES) { "인코더가 마지막 프레임을 내보내지 않았습니다." }
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buffer != null && info.size > 0 && !isConfig && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            frames.forEach { frame ->
                val bitmap = renderFrame(frame, privacyMode, width, height)
                bitmap.getPixels(argb, 0, width, 0, 0, width, height)
                bitmap.recycle()
                val index = dequeueInput(codec) { drain(endOfStream = false) }
                val image = requireNotNull(codec.getInputImage(index)) { "인코더 입력 이미지를 받을 수 없습니다." }
                writeYuv(argb, width, height, image)
                val ptsUs = (frame.timestampNanos - startNanos) / 1_000L
                codec.queueInputBuffer(index, 0, width * height * 3 / 2, ptsUs, 0)
                drain(endOfStream = false)
            }
            val eosIndex = dequeueInput(codec) { drain(endOfStream = false) }
            val lastPtsUs = (frames.last().timestampNanos - startNanos) / 1_000L + 1_000_000L / fps
            codec.queueInputBuffer(eosIndex, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true)
            check(muxerStarted) { "인코더가 영상을 만들지 못했습니다." }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun renderFrame(frame: ClipFrame, privacyMode: PrivacyMode, width: Int, height: Int): Bitmap {
        val upright = decodeJpegFrame(frame.jpeg, frame.rotationDegrees)
        val drawn = if (privacyMode == PrivacyMode.VIDEO_SKELETON) {
            upright.withSkeletonOverlay(requireNotNull(frame.pose)).also { upright.recycle() }
        } else {
            upright
        }
        if (drawn.width == width && drawn.height == height) return drawn
        return Bitmap.createScaledBitmap(drawn, width, height, true).also { drawn.recycle() }
    }

    private inline fun dequeueInput(codec: MediaCodec, onWait: () -> Unit): Int {
        repeat(INPUT_RETRIES) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) return index
            onWait()
        }
        throw IllegalStateException("인코더 입력 버퍼를 얻지 못했습니다.")
    }

    private fun estimateFps(frames: List<ClipFrame>): Int {
        val spanSec = (frames.last().timestampNanos - frames.first().timestampNanos) / 1_000_000_000.0
        if (spanSec <= 0.0) return 8
        return ((frames.size - 1) / spanSec).roundToInt().coerceIn(1, 30)
    }

    private fun evenSize(value: Float): Int = (value.roundToInt() / 2 * 2).coerceAtLeast(2)

    private companion object {
        const val TEMP_PREFIX = "incident-"
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_RETRIES = 500
        const val DRAIN_TIMEOUT_US = 10_000L
    }
}

/** ARGB 픽셀을 BT.601 YUV420으로 바꿔 인코더 입력 이미지의 평면 간격대로 쓴다. */
private fun writeYuv(argb: IntArray, width: Int, height: Int, image: android.media.Image) {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    for (row in 0 until height) {
        for (col in 0 until width) {
            val pixel = argb[row * width + col]
            val r = pixel shr 16 and 0xff
            val g = pixel shr 8 and 0xff
            val b = pixel and 0xff
            val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
            yBuffer.put(row * yPlane.rowStride + col * yPlane.pixelStride, y.coerceIn(0, 255).toByte())
            if (row % 2 == 0 && col % 2 == 0) {
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                val chromaRow = row / 2
                val chromaCol = col / 2
                uBuffer.put(chromaRow * uPlane.rowStride + chromaCol * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                vBuffer.put(chromaRow * vPlane.rowStride + chromaCol * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
            }
        }
    }
}
