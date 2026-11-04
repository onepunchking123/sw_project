package com.example.careguardcamera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.careguardcamera.inference.ModelManifest
import com.example.careguardcamera.inference.OnnxSessionOptions
import com.example.careguardcamera.inference.TimestampedFallModelEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ModelRuntimeInstrumentedTest {
    @Test
    fun androidOrtMatchesPythonGoldenForYoloAndGru() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val golden = JSONObject(
            instrumentation.context.assets.open("model-runtime-golden.json").bufferedReader().use { it.readText() }
        )
        val manifest = ModelManifest.load(target)
        manifest.verifyAssets(target)
        val env = OrtEnvironment.getEnvironment()

        val yoloSession = target.assets.open(manifest.yolo.tensor.asset).use { input ->
            OnnxSessionOptions.create().use { options ->
                env.createSession(input.readBytes(), options)
            }
        }
        val gruSession = target.assets.open(manifest.gru.tensor.asset).use { input ->
            OnnxSessionOptions.create().use { options ->
                env.createSession(input.readBytes(), options)
            }
        }
        try {
            val yoloFixtures = golden.getJSONObject("yoloSamples").getJSONArray("fixtures")
            for (i in 0 until yoloFixtures.length()) {
                val fixture = yoloFixtures.getJSONObject(i)
                val input = syntheticYoloInput(fixture.getInt("index"), manifest.yolo.tensor.inputShape)
                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), manifest.yolo.tensor.inputShape)
                val result = yoloSession.run(mapOf(manifest.yolo.tensor.inputName to tensor))
                try {
                    val output = result[0].value as Array<Array<FloatArray>>
                    val raw = output[0]
                    assertEquals(56, raw.size)
                    assertEquals(8400, raw[0].size)
                    val samples = fixture.getJSONArray("samples")
                    for (j in 0 until samples.length()) {
                        val sample = samples.getJSONObject(j)
                        val actual = raw[sample.getInt("channel")][sample.getInt("anchor")]
                        assertEquals(sample.getDouble("value").toFloat(), actual, 1e-3f)
                    }
                } finally {
                    result.close()
                    tensor.close()
                }
            }

            val gruFixtures = golden.getJSONArray("gruFixtures")
            for (i in 0 until gruFixtures.length()) {
                val fixture = gruFixtures.getJSONObject(i)
                val input = syntheticGruInput(fixture.getInt("index")).reshapeToFlatGru()
                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), manifest.gru.tensor.inputShape)
                val result = gruSession.run(mapOf(manifest.gru.tensor.inputName to tensor))
                try {
                    val logits = (result[0].value as Array<FloatArray>)[0]
                    val expectedLogits = fixture.getJSONArray("logits")
                    for (j in logits.indices) {
                        assertEquals(expectedLogits.getDouble(j).toFloat(), logits[j], 1e-4f)
                    }
                    val probabilities = softmax(logits)
                    val expectedProbabilities = fixture.getJSONArray("probabilities")
                    for (j in probabilities.indices) {
                        assertEquals(expectedProbabilities.getDouble(j).toFloat(), probabilities[j], 1e-3f)
                    }
                } finally {
                    result.close()
                    tensor.close()
                }
            }
        } finally {
            yoloSession.close()
            gruSession.close()
        }
    }

    @Test
    fun engineWarmupAtTenFpsKeepsBlankBitmapNoPerson() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = TimestampedFallModelEngine(target)
        val bitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        try {
            var state = ""
            val start = 1_000_000_000_000L
            for (i in 0..20) {
                val result = engine.analyze(bitmap, start + i * 100L)
                state = result.state
                assertTrue(result.events.isEmpty())
            }
            assertEquals("no_person", state)
            try {
                engine.analyze(bitmap, start + 2_000L)
                throw AssertionError("Expected nonmonotonic timestamp rejection")
            } catch (_: IllegalArgumentException) {
            }
        } finally {
            bitmap.recycle()
            engine.close()
        }
    }

    @Test
    fun timedFeaturePipelineUsesIrregularCaptureTime() {
        val first = Array(17) { Keypoint(0f, 0f, 0f) }
        val second = Array(17) { Keypoint(0f, 0f, 0f) }
        val third = Array(17) { Keypoint(0f, 0f, 0f) }
        first[0] = Keypoint(1f, 2f, 0.1f)
        second[0] = Keypoint(2f, 4f, 0.1f)
        third[0] = Keypoint(4f, 8f, 0.1f)

        val velocities = PosePipeline.addVelocityTimed(listOf(first, second, third), listOf(0L, 100L, 300L))
        val sampled = PosePipeline.resampleTimed(velocities, listOf(0L, 100L, 300L), targetLen = 4)

        assertEquals(1f, velocities[0][3], 1e-6f)
        assertEquals(2f, velocities[0][4], 1e-6f)
        assertEquals(1f, velocities[2][3], 1e-6f)
        assertEquals(2f, velocities[2][4], 1e-6f)
        assertEquals(1f, sampled[0][0], 1e-6f)
        assertEquals(2f, sampled[1][0], 1e-6f)
        assertEquals(3f, sampled[2][0], 1e-6f)
    }

    private fun syntheticYoloInput(index: Int, shape: LongArray): FloatArray {
        val channels = shape[1].toInt()
        val height = shape[2].toInt()
        val width = shape[3].toInt()
        val data = FloatArray(channels * height * width)
        val base = ((index + 1).toFloat() / 31f) * 0.5f
        for (c in 0 until channels) {
            for (y in 0 until height) {
                val yy = if (height == 1) 0f else y.toFloat() / (height - 1).toFloat()
                for (x in 0 until width) {
                    val xx = if (width == 1) 0f else x.toFloat() / (width - 1).toFloat()
                    val value = base + (c + 1) * xx + ((index % 7) + 1) * yy / 10f
                    data[c * height * width + y * width + x] = value - kotlin.math.floor(value)
                }
            }
        }
        return data
    }

    private fun syntheticGruInput(index: Int): Array<Array<Array<FloatArray>>> {
        val fixture = Array(1) { Array(64) { Array(17) { FloatArray(5) } } }
        for (t in 0 until 64) {
            for (k in 0 until 17) {
                for (c in 0 until 5) {
                    val angle = (index + 1) * (t + 1) * 0.017f + (k + 1) * 0.13f + c * 0.37f
                    fixture[0][t][k][c] = kotlin.math.sin(angle) * 0.25f
                }
                fixture[0][t][k][2] = if (index % 5 == 0) {
                    0f
                } else {
                    abs(fixture[0][t][k][2]).coerceIn(0.05f, 1f)
                }
            }
        }
        return fixture
    }

    private fun Array<Array<Array<FloatArray>>>.reshapeToFlatGru(): FloatArray {
        val flat = FloatArray(64 * 17 * 5)
        var p = 0
        for (t in 0 until 64) {
            for (k in 0 until 17) {
                for (c in 0 until 5) {
                    flat[p++] = this[0][t][k][c]
                }
            }
        }
        return flat
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: 0f
        val exps = FloatArray(logits.size) { kotlin.math.exp(logits[it] - max) }
        val sum = exps.sum()
        return FloatArray(logits.size) { exps[it] / sum }
    }

}
