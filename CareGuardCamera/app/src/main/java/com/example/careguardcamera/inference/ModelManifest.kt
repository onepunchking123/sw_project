package com.example.careguardcamera.inference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class ModelIncompatibleException(message: String) : IllegalStateException(message)

data class TensorSpec(
    val asset: String,
    val sha256: String,
    val inputName: String,
    val inputShape: LongArray,
    val inputDtype: String,
    val outputName: String,
    val outputShape: LongArray,
    val outputDtype: String
) {
    fun requireInputShape(actual: LongArray) {
        if (!inputShape.contentEquals(actual)) {
            throw ModelIncompatibleException("Input shape mismatch for $asset: expected ${inputShape.contentToString()}, got ${actual.contentToString()}")
        }
    }
}

data class YoloSpec(
    val tensor: TensorSpec,
    val inputColorOrder: String,
    val orientationContract: String,
    val rotationHandling: String,
    val mirrorHandling: String,
    val inputScale: Float,
    val letterboxPadValue: Int,
    val confidenceThreshold: Float,
    val nmsIouThreshold: Float,
    val keypointCount: Int
)

data class GruSpec(
    val tensor: TensorSpec,
    val feature: String
)

data class StreamSpec(
    val windowSec: Float,
    val strideSec: Float,
    val sequenceLength: Int,
    val timestampMode: String,
    val resampleMode: String,
    val minimumDetectionRate: Float,
    val riskEnterThreshold: Float,
    val riskExitThreshold: Float
)

data class RuleSpec(
    val tFallenSec: Float,
    val tStillSec: Float,
    val motionEps: Float,
    val horizThreshold: Float,
    val fallCooldownSec: Float
)

data class ModelManifest(
    val classOrder: List<String>,
    val yolo: YoloSpec,
    val gru: GruSpec,
    val stream: StreamSpec,
    val rules: RuleSpec
) {
    fun verifyAssets(context: Context) {
        verifyAsset(context, yolo.tensor)
        verifyAsset(context, gru.tensor)
        if (classOrder != listOf("normal", "fall", "fallen")) {
            throw ModelIncompatibleException("Class order mismatch: $classOrder")
        }
        requireTensorSpec(yolo.tensor, "YOLO")
        requireTensorSpec(gru.tensor, "GRU")
        if (yolo.inputColorOrder != "RGB") {
            throw ModelIncompatibleException("Unsupported YOLO color order: ${yolo.inputColorOrder}")
        }
        if (yolo.orientationContract != "UPRIGHT_UNMIRRORED_BITMAP") {
            throw ModelIncompatibleException("Unsupported orientation contract: ${yolo.orientationContract}")
        }
        if (yolo.rotationHandling != "CALLER_PROVIDES_UPRIGHT") {
            throw ModelIncompatibleException("Unsupported rotation handling: ${yolo.rotationHandling}")
        }
        if (yolo.mirrorHandling != "CALLER_PROVIDES_UNMIRRORED") {
            throw ModelIncompatibleException("Unsupported mirror handling: ${yolo.mirrorHandling}")
        }
        if (gru.feature != "posvel") {
            throw ModelIncompatibleException("Unsupported GRU feature mode: ${gru.feature}")
        }
        if (stream.timestampMode != "CAPTURE_TIMESTAMP_ACTUAL_DT" || stream.resampleMode != "SOURCE_TIME_LINEAR") {
            throw ModelIncompatibleException("Unsupported stream timing contract: ${stream.timestampMode}/${stream.resampleMode}")
        }
        yolo.tensor.requireInputShape(longArrayOf(1, 3, 640, 640))
        gru.tensor.requireInputShape(longArrayOf(1, stream.sequenceLength.toLong(), 17L * 5L))
    }

    private fun requireTensorSpec(spec: TensorSpec, label: String) {
        if (spec.inputDtype != "float32" || spec.outputDtype != "float32") {
            throw ModelIncompatibleException("$label dtype mismatch: ${spec.inputDtype}/${spec.outputDtype}")
        }
    }

    private fun verifyAsset(context: Context, spec: TensorSpec) {
        val actual = context.assets.open(spec.asset).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (actual != spec.sha256) {
            throw ModelIncompatibleException("Hash mismatch for ${spec.asset}: expected ${spec.sha256}, got $actual")
        }
    }

    companion object {
        fun load(context: Context, assetName: String = "model-manifest.json"): ModelManifest {
            val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
            return fromJson(json)
        }

        fun fromJson(json: String): ModelManifest {
            val root = JSONObject(json)
            val yolo = root.getJSONObject("yolo")
            val gru = root.getJSONObject("gru")
            val stream = root.getJSONObject("stream")
            val rules = root.getJSONObject("rules")
            return ModelManifest(
                classOrder = root.getJSONArray("classOrder").toStringList(),
                yolo = YoloSpec(
                    tensor = yolo.tensorSpec(),
                    inputColorOrder = yolo.getString("inputColorOrder"),
                    orientationContract = yolo.getString("orientationContract"),
                    rotationHandling = yolo.getString("rotationHandling"),
                    mirrorHandling = yolo.getString("mirrorHandling"),
                    inputScale = yolo.getDouble("inputScale").toFloat(),
                    letterboxPadValue = yolo.getInt("letterboxPadValue"),
                    confidenceThreshold = yolo.getDouble("confidenceThreshold").toFloat(),
                    nmsIouThreshold = yolo.getDouble("nmsIouThreshold").toFloat(),
                    keypointCount = yolo.getInt("keypointCount")
                ),
                gru = GruSpec(
                    tensor = gru.tensorSpec(),
                    feature = gru.getString("feature")
                ),
                stream = StreamSpec(
                    windowSec = stream.getDouble("windowSec").toFloat(),
                    strideSec = stream.getDouble("strideSec").toFloat(),
                    sequenceLength = stream.getInt("sequenceLength"),
                    timestampMode = stream.getString("timestampMode"),
                    resampleMode = stream.getString("resampleMode"),
                    minimumDetectionRate = stream.getDouble("minimumDetectionRate").toFloat(),
                    riskEnterThreshold = stream.getDouble("riskEnterThreshold").toFloat(),
                    riskExitThreshold = stream.getDouble("riskExitThreshold").toFloat()
                ),
                rules = RuleSpec(
                    tFallenSec = rules.getDouble("tFallenSec").toFloat(),
                    tStillSec = rules.getDouble("tStillSec").toFloat(),
                    motionEps = rules.getDouble("motionEps").toFloat(),
                    horizThreshold = rules.getDouble("horizThreshold").toFloat(),
                    fallCooldownSec = rules.getDouble("fallCooldownSec").toFloat()
                )
            )
        }

        private fun JSONObject.tensorSpec(): TensorSpec = TensorSpec(
            asset = getString("asset"),
            sha256 = getString("sha256"),
            inputName = getString("inputName"),
            inputShape = getJSONArray("inputShape").toLongArray(),
            inputDtype = getString("inputDtype"),
            outputName = getString("outputName"),
            outputShape = getJSONArray("outputShape").toLongArray(),
            outputDtype = getString("outputDtype")
        )

        private fun JSONArray.toLongArray(): LongArray = LongArray(length()) { getLong(it) }

        private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
    }
}
