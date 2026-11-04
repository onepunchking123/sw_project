package com.example.careguardcamera.inference

import android.graphics.Bitmap
import com.example.careguardcamera.Keypoint

interface LiveFallModelEngine : AutoCloseable {
    fun analyze(bitmap: Bitmap, captureTimestampMs: Long): FallInferenceResult
}

data class FallInferenceResult(
    val captureTimestampMs: Long,
    val state: String,
    val probabilities: FloatArray,
    val events: List<FallInferenceEvent>,
    val keypoints: Array<Keypoint>?
)

data class FallInferenceEvent(
    val kind: String,
    val rule: String,
    val timestampMs: Long,
    val state: String,
    val probabilities: FloatArray
)
