package com.example.careguardcamera.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.exp

class GruFallOnnxModel(
    context: Context,
    private val env: OrtEnvironment,
    private val spec: GruSpec
) : AutoCloseable {
    private val session: OrtSession = context.assets.open(spec.tensor.asset).use { input ->
        OnnxSessionOptions.create().use { options ->
            env.createSession(input.readBytes(), options)
        }
    }

    init {
        requireSessionContract()
    }

    fun classify(flatFeatures: FloatArray): FloatArray {
        val expected = spec.tensor.inputShape.drop(1).fold(1L) { acc, n -> acc * n }.toInt()
        if (flatFeatures.size != expected) {
            throw ModelIncompatibleException("GRU feature length mismatch: expected $expected, got ${flatFeatures.size}")
        }
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatFeatures), spec.tensor.inputShape)
        val result = tensor.use {
            session.run(mapOf(spec.tensor.inputName to it))
        }
        result.use { output ->
            val logits = extractLogits(output[0].value)
            return softmax(logits)
        }
    }

    private fun requireSessionContract() {
        val input = session.inputInfo.keys.firstOrNull()
        val output = session.outputInfo.keys.firstOrNull()
        if (input != spec.tensor.inputName || output != spec.tensor.outputName) {
            throw ModelIncompatibleException("GRU tensor names mismatch: input=$input output=$output")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractLogits(value: Any): FloatArray {
        val batched = value as Array<FloatArray>
        val logits = batched[0]
        if (logits.size != spec.tensor.outputShape.last().toInt()) {
            throw ModelIncompatibleException("GRU output size mismatch: got ${logits.size}")
        }
        return logits
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }

    override fun close() {
        session.close()
    }
}
