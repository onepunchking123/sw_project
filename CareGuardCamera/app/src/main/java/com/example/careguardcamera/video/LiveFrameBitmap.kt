package com.example.careguardcamera.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

fun LiveFrame.toBitmap(applyRotation: Boolean = true): Bitmap =
    decodeJpegFrame(toJpeg(85), if (applyRotation) rotationDegrees else 0)

/** 회전하지 않은 원본 방향의 JPEG. 클립 버퍼가 프레임을 작게 보관할 때 쓴다. */
fun LiveFrame.toJpeg(quality: Int): ByteArray {
    require(format == ImageFormat.YUV_420_888) { "Only YUV_420_888 frames are supported." }
    require(planes.size >= 3) { "YUV_420_888 requires three planes." }
    val jpeg = ByteArrayOutputStream()
    YuvImage(toNv21(), ImageFormat.NV21, width, height, null)
        .compressToJpeg(Rect(0, 0, width, height), quality, jpeg)
    return jpeg.toByteArray()
}

fun decodeJpegFrame(jpeg: ByteArray, rotationDegrees: Int): Bitmap {
    val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        ?: throw IllegalStateException("카메라 프레임을 비트맵으로 변환할 수 없습니다.")
    if (rotationDegrees == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    decoded.recycle()
    return rotated
}

/** 모델 관절을 그린 사본. 원본 비트맵은 그대로 둔다. 좌표는 회전된(세운) 프레임 기준이다. */
fun Bitmap.withSkeletonOverlay(pose: OverlayPose): Bitmap {
    val output = copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    val bones = listOf(
        5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10,
        5 to 11, 6 to 12, 11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16
    )
    bones.forEach { (a, b) ->
        val left = pose.keypoints.getOrNull(a)
        val right = pose.keypoints.getOrNull(b)
        if (left != null && right != null && left.conf > 0f && right.conf > 0f) {
            canvas.drawLine(left.x, left.y, right.x, right.y, line)
        }
    }
    pose.keypoints.filter { it.conf > 0f }.forEach { kp ->
        canvas.drawCircle(kp.x, kp.y, 6f, dot)
    }
    return output
}

private fun LiveFrame.toNv21(): ByteArray {
    val ySize = width * height
    val chromaSize = ySize / 2
    val output = ByteArray(ySize + chromaSize)
    copyPlane(planes[0], width, height, output, 0, 1)

    val uPlane = planes[1]
    val vPlane = planes[2]
    var outputOffset = ySize
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            output[outputOffset++] = vPlane.bytes[(row * vPlane.rowStride) + (col * vPlane.pixelStride)]
            output[outputOffset++] = uPlane.bytes[(row * uPlane.rowStride) + (col * uPlane.pixelStride)]
        }
    }
    return output
}

private fun copyPlane(
    plane: LiveFrame.Plane,
    width: Int,
    height: Int,
    output: ByteArray,
    outputStart: Int,
    outputPixelStride: Int
) {
    var outputOffset = outputStart
    for (row in 0 until height) {
        val rowOffset = row * plane.rowStride
        for (col in 0 until width) {
            output[outputOffset] = plane.bytes[rowOffset + (col * plane.pixelStride)]
            outputOffset += outputPixelStride
        }
    }
}
