package com.example.careguardcamera

import com.example.careguardcamera.inference.LiveModelTiming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosePipelineModelParityTest {
    @Test
    fun addVelocityUsesPositiveConfidenceAndCopiesFirstVelocity() {
        val first = Array(17) { Keypoint(0f, 0f, 0f) }
        val second = Array(17) { Keypoint(0f, 0f, 0f) }
        first[0] = Keypoint(1f, 2f, 0.1f)
        second[0] = Keypoint(2f, 4f, 0.1f)

        val out = PosePipeline.addVelocity(listOf(first, second), fps = 30f)

        assertEquals(3f, out[0][3], 1e-6f)
        assertEquals(6f, out[0][4], 1e-6f)
        assertEquals(3f, out[1][3], 1e-6f)
        assertEquals(6f, out[1][4], 1e-6f)
    }

    @Test
    fun addVelocityTimedUsesActualDeltaTimeAndCopiesFirstVelocity() {
        val first = Array(17) { Keypoint(0f, 0f, 0f) }
        val second = Array(17) { Keypoint(0f, 0f, 0f) }
        val third = Array(17) { Keypoint(0f, 0f, 0f) }
        first[0] = Keypoint(1f, 2f, 0.1f)
        second[0] = Keypoint(2f, 4f, 0.1f)
        third[0] = Keypoint(4f, 8f, 0.1f)

        val out = PosePipeline.addVelocityTimed(
            listOf(first, second, third),
            listOf(0L, 100L, 300L)
        )

        assertEquals(1f, out[0][3], 1e-6f)
        assertEquals(2f, out[0][4], 1e-6f)
        assertEquals(1f, out[1][3], 1e-6f)
        assertEquals(2f, out[1][4], 1e-6f)
        assertEquals(1f, out[2][3], 1e-6f)
        assertEquals(2f, out[2][4], 1e-6f)
    }

    @Test
    fun addVelocityZerosAcrossMissingKeypoint() {
        val first = Array(17) { Keypoint(0f, 0f, 0f) }
        val second = Array(17) { Keypoint(0f, 0f, 0f) }
        first[0] = Keypoint(1f, 2f, 0f)
        second[0] = Keypoint(2f, 4f, 0.1f)

        val out = PosePipeline.addVelocity(listOf(first, second), fps = 30f)

        assertEquals(0f, out[0][3], 1e-6f)
        assertEquals(0f, out[1][3], 1e-6f)
    }

    @Test
    fun resampleRepeatsSingleFrame() {
        val frame = FloatArray(85) { it.toFloat() }
        val out = PosePipeline.resample(arrayOf(frame), targetLen = 3)

        assertArrayEquals(frame, out[0], 0f)
        assertArrayEquals(frame, out[1], 0f)
        assertArrayEquals(frame, out[2], 0f)
    }

    @Test
    fun resampleTimedUsesSourceTimeInsteadOfFrameIndex() {
        val a = floatArrayOf(0f)
        val b = floatArrayOf(10f)
        val c = floatArrayOf(20f)

        val out = PosePipeline.resampleTimed(arrayOf(a, b, c), listOf(0L, 100L, 300L), targetLen = 4)

        assertEquals(0f, out[0][0], 1e-6f)
        assertEquals(10f, out[1][0], 1e-6f)
        assertEquals(15f, out[2][0], 1e-6f)
        assertEquals(20f, out[3][0], 1e-6f)
    }

    @Test
    fun timingGateAllowsTenFpsTwoSecondWindowAndRejectsNonmonotonicInput() {
        val tenFpsTwoSeconds = (0..20).map { it * 100L }

        assertTrue(LiveModelTiming.hasEnoughCoverage(tenFpsTwoSeconds, windowSec = 2.0f))
        assertFalse(LiveModelTiming.hasEnoughCoverage((0..19).map { it * 100L }, windowSec = 2.0f))
        val oneHundredThreeMsCadence = (0..30).map { it * 103L }
        val now = oneHundredThreeMsCadence[25]
        val cutoff = now - 2000L
        val start = LiveModelTiming.boundaryWindowStartIndex(oneHundredThreeMsCadence, cutoff)
        val realisticWindow = oneHundredThreeMsCadence.drop(start).filter { it <= now }
        assertTrue(LiveModelTiming.hasIntervalCoverage(realisticWindow, cutoff, now))
        assertEquals(123L, LiveModelTiming.relativeTimestamp(1_000_000_000_000L, 1_000_000_000_123L))
        try {
            LiveModelTiming.requireStrictlyIncreasing(10L, 10L)
            throw AssertionError("Expected nonmonotonic timestamp rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun immobilityDetectorMatchesPythonEventOrder() {
        val det = ImmobilityDetector(tFallen = 3f, tStill = 5f, eps = 0.02f, horizThr = 1f)
        val horizontal = horizontalFrame()
        val stillWindow = List(2) { horizontal }

        assertEquals(listOf("FALL (model)"), det.updateWindow(0f, "fall", stillWindow))
        assertTrue(det.updateWindow(1f, "fall", stillWindow).isEmpty())
        assertTrue(det.updateWindow(2f, "fallen", stillWindow).isEmpty())
        assertEquals(listOf("FALLEN_IMMOBILE (R1)"), det.updateWindow(3f, "fallen", stillWindow))
        assertEquals(listOf("STILL_HORIZONTAL (R2)"), det.updateWindow(5f, "normal", stillWindow))
    }

    @Test
    fun noPersonWindowResetsRiskAndStillnessRules() {
        val det = ImmobilityDetector(tFallen = 3f, tStill = 5f, eps = 0.02f, horizThr = 1f)
        val horizontal = horizontalFrame()
        val noPerson = Array(17) { Keypoint(0f, 0f, 0f) }

        assertEquals(listOf("FALL (model)"), det.updateWindow(0f, "fall", listOf(horizontal, horizontal)))
        assertTrue(det.updateWindow(1f, "no_person", listOf(noPerson, noPerson)).isEmpty())
        assertEquals(0f, PosePipeline.detectionRate(listOf(noPerson, noPerson)), 0f)
        assertTrue(det.updateWindow(4f, "fallen", listOf(horizontal, horizontal)).isEmpty())
        assertEquals(listOf("FALLEN_IMMOBILE (R1)"), det.updateWindow(7f, "fallen", listOf(horizontal, horizontal)))
    }

    private fun horizontalFrame(): Array<Keypoint> {
        val frame = Array(17) { Keypoint(0f, 0f, 1f) }
        frame[5] = Keypoint(1f, 0f, 1f)
        frame[6] = Keypoint(1f, 0f, 1f)
        frame[11] = Keypoint(0f, 0f, 1f)
        frame[12] = Keypoint(0f, 0f, 1f)
        return frame
    }
}
