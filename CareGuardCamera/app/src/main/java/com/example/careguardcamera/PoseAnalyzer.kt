package com.example.careguardcamera

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.ArrayDeque

class PoseAnalyzer(
    context: Context,
    private val onResult: (state: String, probs: FloatArray, events: List<String>) -> Unit
) : ImageAnalysis.Analyzer {

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val gruSession: OrtSession

    // 링 버퍼 (2초 = 약 60프레임)
    private val ringBuffer = ArrayDeque<Array<Keypoint>>(60)
    private val detector = ImmobilityDetector(fps = 30f)
    private var frameCount = 0L

    init {
        val gruBytes = context.assets.open("gru.onnx").readBytes()
        gruSession = ortEnv.createSession(gruBytes)
    }

    override fun analyze(imageProxy: ImageProxy) {
        val width = imageProxy.width
        val height = imageProxy.height

        val rawKps: Array<Keypoint>? = extractYoloPose(imageProxy)

        if (rawKps != null) {
            val normKps = PosePipeline.normalize(rawKps, width, height)

            synchronized(ringBuffer) {
                if (ringBuffer.size >= 60) ringBuffer.removeFirst()
                ringBuffer.addLast(normKps)
            }

            // 15프레임(약 0.5초) 마다 GRU 시계열 추론
            if (frameCount % 15L == 0L && ringBuffer.size >= 30) {
                val windowList = synchronized(ringBuffer) { ringBuffer.toList() }

                val velData = PosePipeline.addVelocity(windowList, 30f)
                val sampled = PosePipeline.resample(velData, 64)

                val flatArray = FloatArray(1 * 64 * 85)
                for (i in 0 until 64) {
                    System.arraycopy(sampled[i], 0, flatArray, i * 85, 85)
                }

                val inputTensor = OnnxTensor.createTensor(
                    ortEnv,
                    FloatBuffer.wrap(flatArray),
                    longArrayOf(1, 64, 85)
                )

                val output = gruSession.run(mapOf("input" to inputTensor))
                val logits = (output[0].value as Array<FloatArray>)[0]
                val probs = softmax(logits)

                val risk = probs[1] + probs[2]
                val state = if (risk > 0.5f) {
                    if (probs[2] >= probs[1]) "fallen" else "fall"
                } else "normal"

                val currentTimeSec = frameCount / 30f
                val events = detector.update(currentTimeSec, state, normKps)

                onResult(state, probs, events)
            }
        } else {
            val events = detector.update(frameCount / 30f, "no_person", null)
            onResult("no_person", floatArrayOf(0f, 0f, 0f), events)
        }

        frameCount++
        imageProxy.close()
    }

    private fun extractYoloPose(imageProxy: ImageProxy): Array<Keypoint>? {
        return null
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { kotlin.math.exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }
}