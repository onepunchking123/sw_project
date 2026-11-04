package com.example.careguardcamera.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.example.careguardcamera.Keypoint
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloPoseOnnxModel(
    context: Context,
    private val env: OrtEnvironment,
    private val spec: YoloSpec
) : AutoCloseable {
    private val session: OrtSession = context.assets.open(spec.tensor.asset).use { input ->
        OnnxSessionOptions.create().use { options ->
            env.createSession(input.readBytes(), options)
        }
    }

    init {
        requireSessionContract()
    }

    fun detectLargestPerson(bitmap: Bitmap): Array<Keypoint>? {
        val prep = preprocess(bitmap)
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(prep.data), spec.tensor.inputShape)
        val result = tensor.use {
            session.run(mapOf(spec.tensor.inputName to it))
        }
        result.use { output ->
            val raw = extractYoloOutput(output[0].value)
            return postprocess(raw, prep)
        }
    }

    private fun requireSessionContract() {
        val input = session.inputInfo.keys.firstOrNull()
        val output = session.outputInfo.keys.firstOrNull()
        if (input != spec.tensor.inputName || output != spec.tensor.outputName) {
            throw ModelIncompatibleException("YOLO tensor names mismatch: input=$input output=$output")
        }
    }

    private fun preprocess(bitmap: Bitmap): PreprocessedImage {
        val inputSize = spec.tensor.inputShape.last().toInt()
        val scale = min(inputSize.toFloat() / bitmap.width.toFloat(), inputSize.toFloat() / bitmap.height.toFloat())
        val resizedW = max(1, (bitmap.width * scale).toInt())
        val resizedH = max(1, (bitmap.height * scale).toInt())
        val padX = (inputSize - resizedW) / 2
        val padY = (inputSize - resizedH) / 2
        val resized = Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
        val pixels = IntArray(resizedW * resizedH)
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, resizedH)
        val data = FloatArray(3 * inputSize * inputSize) { spec.letterboxPadValue * spec.inputScale }
        for (y in 0 until resizedH) {
            for (x in 0 until resizedW) {
                val color = pixels[y * resizedW + x]
                val dst = (y + padY) * inputSize + (x + padX)
                data[dst] = ((color shr 16) and 0xff) * spec.inputScale
                data[inputSize * inputSize + dst] = ((color shr 8) and 0xff) * spec.inputScale
                data[2 * inputSize * inputSize + dst] = (color and 0xff) * spec.inputScale
            }
        }
        if (resized !== bitmap) resized.recycle()
        return PreprocessedImage(data, bitmap.width, bitmap.height, inputSize, scale, padX.toFloat(), padY.toFloat())
    }

    private fun postprocess(raw: Array<FloatArray>, prep: PreprocessedImage): Array<Keypoint>? {
        val candidates = mutableListOf<PoseCandidate>()
        val anchors = raw[0].size
        for (i in 0 until anchors) {
            val score = raw[4][i]
            if (score < spec.confidenceThreshold) continue
            val cx = raw[0][i]
            val cy = raw[1][i]
            val w = raw[2][i]
            val h = raw[3][i]
            val left = cx - w / 2f
            val top = cy - h / 2f
            val keypoints = Array(spec.keypointCount) { k ->
                val base = 5 + k * 3
                Keypoint(
                    x = unletterboxX(raw[base][i], prep),
                    y = unletterboxY(raw[base + 1][i], prep),
                    conf = raw[base + 2][i]
                )
            }
            candidates += PoseCandidate(
                left = unletterboxX(left, prep),
                top = unletterboxY(top, prep),
                right = unletterboxX(left + w, prep),
                bottom = unletterboxY(top + h, prep),
                score = score,
                keypoints = keypoints
            )
        }
        if (candidates.isEmpty()) return null
        val kept = nms(candidates.sortedByDescending { it.score })
        return kept.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }?.keypoints
    }

    private fun nms(sorted: List<PoseCandidate>): List<PoseCandidate> {
        val kept = mutableListOf<PoseCandidate>()
        for (candidate in sorted) {
            if (kept.none { iou(it, candidate) > spec.nmsIouThreshold }) {
                kept += candidate
            }
        }
        return kept
    }

    private fun iou(a: PoseCandidate, b: PoseCandidate): Float {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun unletterboxX(x: Float, prep: PreprocessedImage): Float =
        ((x - prep.padX) / prep.scale).coerceIn(0f, prep.sourceWidth.toFloat())

    private fun unletterboxY(y: Float, prep: PreprocessedImage): Float =
        ((y - prep.padY) / prep.scale).coerceIn(0f, prep.sourceHeight.toFloat())

    @Suppress("UNCHECKED_CAST")
    private fun extractYoloOutput(value: Any): Array<FloatArray> {
        val batched = value as Array<Array<FloatArray>>
        val raw = batched[0]
        if (raw.size != spec.tensor.outputShape[1].toInt() || raw[0].size != spec.tensor.outputShape[2].toInt()) {
            throw ModelIncompatibleException("YOLO output shape mismatch: got [1, ${raw.size}, ${raw[0].size}]")
        }
        return raw
    }

    override fun close() {
        session.close()
    }

    private data class PreprocessedImage(
        val data: FloatArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val inputSize: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private data class PoseCandidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val keypoints: Array<Keypoint>
    )
}
