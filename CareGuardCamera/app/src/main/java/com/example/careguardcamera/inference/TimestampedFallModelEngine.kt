package com.example.careguardcamera.inference

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import com.example.careguardcamera.ImmobilityDetector
import com.example.careguardcamera.Keypoint
import com.example.careguardcamera.PosePipeline
import java.util.ArrayDeque

class TimestampedFallModelEngine(
    context: Context,
    private val manifest: ModelManifest = ModelManifest.load(context)
) : LiveFallModelEngine {
    private val env = OrtEnvironment.getEnvironment()
    private lateinit var yolo: YoloPoseOnnxModel
    private lateinit var gru: GruFallOnnxModel
    private val window = ArrayDeque<TimestampedPose>()
    private val detector = ImmobilityDetector(
        tFallen = manifest.rules.tFallenSec,
        tStill = manifest.rules.tStillSec,
        eps = manifest.rules.motionEps,
        horizThr = manifest.rules.horizThreshold,
        fallCooldown = manifest.rules.fallCooldownSec
    )
    private var lastInferenceAtMs = Long.MIN_VALUE
    private var firstCaptureTimestampMs: Long? = null
    private var lastCaptureTimestampMs: Long? = null
    private var inRisk = false
    private var state = "no_person"
    private var probabilities = FloatArray(manifest.classOrder.size)

    init {
        manifest.verifyAssets(context)
        try {
            yolo = YoloPoseOnnxModel(context, env, manifest.yolo)
            gru = GruFallOnnxModel(context, env, manifest.gru)
        } catch (t: Throwable) {
            closeInitialized()
            throw t
        }
    }

    override fun analyze(bitmap: Bitmap, captureTimestampMs: Long): FallInferenceResult {
        val relativeTimestampMs = relativeTimestamp(captureTimestampMs)
        val pose = yolo.detectLargestPerson(bitmap)
        val normalized = if (pose == null) {
            Array(17) { Keypoint(0f, 0f, 0f) }
        } else {
            PosePipeline.normalize(pose, bitmap.width, bitmap.height)
        }
        appendPose(relativeTimestampMs, normalized)
        maybeClassify(relativeTimestampMs)
        val stillWindow = recent(manifest.rules.tStillSec, relativeTimestampMs).map { it.pose }
        val events = detector.updateWindow(relativeTimestampMs / 1000f, state, stillWindow)
            .map { label ->
                val parts = label.removeSuffix(")").split(" (")
                FallInferenceEvent(
                    kind = parts[0],
                    rule = parts.getOrElse(1) { "model" },
                    timestampMs = captureTimestampMs,
                    state = state,
                    probabilities = probabilities.copyOf()
                )
            }
        return FallInferenceResult(
            captureTimestampMs = captureTimestampMs,
            state = state,
            probabilities = probabilities.copyOf(),
            events = events,
            keypoints = pose
        )
    }

    private fun appendPose(timestampMs: Long, pose: Array<Keypoint>) {
        window.addLast(TimestampedPose(timestampMs, pose))
        val cutoff = timestampMs - (manifest.rules.tStillSec * 1000f).toLong()
        while (window.size > 1 && window.first().timestampMs < cutoff) {
            window.removeFirst()
        }
        while (window.size > MAX_WINDOW_FRAMES) {
            window.removeFirst()
        }
    }

    private fun maybeClassify(timestampMs: Long) {
        val strideMs = (manifest.stream.strideSec * 1000f).toLong()
        if (lastInferenceAtMs != Long.MIN_VALUE && timestampMs - lastInferenceAtMs < strideMs) return
        val modelWindow = recent(manifest.stream.windowSec, timestampMs)
        val poses = modelWindow.map { it.pose }
        if (!hasEnoughCoverage(modelWindow)) {
            state = if (PosePipeline.detectionRate(poses) <= 0f) "no_person" else state
            return
        }
        lastInferenceAtMs = timestampMs
        if (PosePipeline.detectionRate(poses) < manifest.stream.minimumDetectionRate) {
            state = "no_person"
            probabilities = FloatArray(manifest.classOrder.size)
            inRisk = false
            return
        }
        val timestamps = modelWindow.map { it.timestampMs }
        val velData = PosePipeline.addVelocityTimed(poses, timestamps)
        val sampled = PosePipeline.resampleTimed(
            data = velData,
            timestampsMs = timestamps,
            targetLen = manifest.stream.sequenceLength,
            startMs = modelWindowStartMs(timestampMs),
            endMs = timestampMs
        )
        val flat = FloatArray(manifest.stream.sequenceLength * 17 * 5)
        for (i in sampled.indices) {
            System.arraycopy(sampled[i], 0, flat, i * 17 * 5, 17 * 5)
        }
        probabilities = gru.classify(flat)
        val risk = probabilities[1] + probabilities[2]
        inRisk = risk > manifest.stream.riskEnterThreshold || (inRisk && risk > manifest.stream.riskExitThreshold)
        state = if (inRisk) {
            if (probabilities[2] >= probabilities[1]) "fallen" else "fall"
        } else {
            "normal"
        }
    }

    private fun recent(seconds: Float, nowMs: Long): List<TimestampedPose> {
        val cutoff = nowMs - (seconds * 1000f).toLong()
        val timestamps = window.map { it.timestampMs }
        val start = LiveModelTiming.boundaryWindowStartIndex(timestamps, cutoff)
        return window.drop(start)
    }

    private fun hasEnoughCoverage(modelWindow: List<TimestampedPose>): Boolean {
        return LiveModelTiming.hasEnoughCoverage(
            timestampsMs = modelWindow.map { it.timestampMs },
            windowSec = manifest.stream.windowSec
        ) && LiveModelTiming.hasIntervalCoverage(
            timestampsMs = modelWindow.map { it.timestampMs },
            startMs = modelWindowStartMs(modelWindow.last().timestampMs),
            endMs = modelWindow.last().timestampMs
        )
    }

    private fun modelWindowStartMs(nowMs: Long): Long = nowMs - (manifest.stream.windowSec * 1000f).toLong()

    private fun relativeTimestamp(captureTimestampMs: Long): Long {
        val previous = lastCaptureTimestampMs
        LiveModelTiming.requireStrictlyIncreasing(previous, captureTimestampMs)
        lastCaptureTimestampMs = captureTimestampMs
        val first = firstCaptureTimestampMs ?: captureTimestampMs.also { firstCaptureTimestampMs = it }
        return LiveModelTiming.relativeTimestamp(first, captureTimestampMs)
    }

    override fun close() {
        closeInitialized()
    }

    private data class TimestampedPose(val timestampMs: Long, val pose: Array<Keypoint>)

    private fun closeInitialized() {
        if (::yolo.isInitialized) yolo.close()
        if (::gru.isInitialized) gru.close()
    }

    companion object {
        private const val MAX_WINDOW_FRAMES = 300
    }
}
