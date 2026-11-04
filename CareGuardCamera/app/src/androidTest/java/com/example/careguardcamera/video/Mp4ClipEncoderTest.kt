package com.example.careguardcamera.video

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.careguardcamera.Keypoint
import com.example.careguardcamera.data.PrivacyMode
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 기기 인코더로 낙상 클립 MP4를 만들고 다시 읽어 길이·크기·프레임 수를 확인한다. */
@RunWith(AndroidJUnit4::class)
class Mp4ClipEncoderTest {
    private val workDir: File = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun encodesFramesWithCaptureTimingAndRotation() {
        // 회전 90도 640x480 원본 → 세운 480x640, 초당 8장 2초
        val frames = (0 until 17).map { index -> frame(index * 125_000_000L, 640, 480, 90, pose = null, shade = index * 12) }

        val clip = Mp4ClipEncoder(workDir).encode(frames, PrivacyMode.NO_OVERLAY)

        assertNotNull(clip)
        assertEquals(2.0f, clip!!.durationSec, 0.01f)
        val track = readVideoTrack(clip.mp4)
        assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, track.mime)
        assertEquals(480, track.width)
        assertEquals(640, track.height)
        assertEquals(17, track.samples)
        assertTrue("duration ${track.lastPtsUs}", track.lastPtsUs in 1_900_000L..2_100_000L)
    }

    @Test
    fun skeletonModeKeepsOnlyFramesWithPoseAndSkeletonOnlyMakesNoClip() {
        val pose = OverlayPose(Array(17) { Keypoint(100f + it, 120f + it, 0.9f) }, 0L, 480, 640)
        val frames = (0 until 10).map { index ->
            frame(index * 125_000_000L, 640, 480, 90, pose = if (index % 2 == 0) pose else null, shade = 80)
        }

        val skeleton = Mp4ClipEncoder(workDir).encode(frames, PrivacyMode.VIDEO_SKELETON)

        assertNotNull(skeleton)
        assertEquals(5, readVideoTrack(skeleton!!.mp4).samples)
        assertNull(Mp4ClipEncoder(workDir).encode(frames, PrivacyMode.SKELETON_ONLY))
    }

    private fun frame(timestampNanos: Long, width: Int, height: Int, rotation: Int, pose: OverlayPose?, shade: Int): ClipFrame {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(shade % 256, 120, 200))
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        return ClipFrame(timestampNanos, 1_700_000_000_000L + timestampNanos / 1_000_000L, jpeg, rotation, pose)
    }

    private data class VideoTrack(val mime: String, val width: Int, val height: Int, val samples: Int, val lastPtsUs: Long)

    private fun readVideoTrack(mp4: ByteArray): VideoTrack {
        val file = File.createTempFile("verify-", ".mp4", workDir)
        try {
            file.writeBytes(mp4)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertEquals(1, extractor.trackCount)
                val format = extractor.getTrackFormat(0)
                extractor.selectTrack(0)
                var samples = 0
                var lastPts = 0L
                while (extractor.sampleTime >= 0) {
                    samples++
                    lastPts = maxOf(lastPts, extractor.sampleTime)
                    extractor.advance()
                }
                return VideoTrack(
                    format.getString(MediaFormat.KEY_MIME).orEmpty(),
                    format.getInteger(MediaFormat.KEY_WIDTH),
                    format.getInteger(MediaFormat.KEY_HEIGHT),
                    samples,
                    lastPts
                )
            } finally {
                extractor.release()
            }
        } finally {
            file.delete()
        }
    }
}
