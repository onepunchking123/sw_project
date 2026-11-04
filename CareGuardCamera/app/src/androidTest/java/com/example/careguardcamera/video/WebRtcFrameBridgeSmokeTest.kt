package com.example.careguardcamera.video

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebRtcFrameBridgeSmokeTest {
    @Test
    fun createsVideoSourceAndAcceptsExternalBitmapAndI420Frames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = WebRtcFrameBridge.createFactory(context)
        val bridge = WebRtcFrameBridge.create(factory)

        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff336699.toInt())
            bridge.captureBitmap(
                bitmap = bitmap,
                rotationDegrees = 0,
                timestampNs = 1_000L
            )
            bitmap.recycle()

            bridge.captureI420(
                width = 2,
                height = 2,
                dataY = ByteBuffer.wrap(byteArrayOf(16, 32, 48, 64)),
                strideY = 2,
                dataU = ByteBuffer.wrap(byteArrayOf(128.toByte())),
                strideU = 1,
                dataV = ByteBuffer.wrap(byteArrayOf(128.toByte())),
                strideV = 1,
                rotationDegrees = 90,
                timestampNs = 2_000L
            )
        } finally {
            bridge.close()
            factory.dispose()
        }
    }
}
