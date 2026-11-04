package com.example.careguardcamera

import com.example.careguardcamera.video.EventRetryPolicy
import com.example.careguardcamera.video.LatestFrameFanout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionPolicyTest {
    @Test
    fun latestFrameFanoutKeepsOnlyNewestFrameAndClearsOnStop() {
        val fanout = LatestFrameFanout<Int>()

        fanout.offer(1)
        fanout.offer(2)

        assertEquals(listOf(2), fanout.frames.replayCache)

        fanout.clear()

        assertTrue(fanout.frames.replayCache.isEmpty())
    }

    @Test
    fun eventRetryPolicyBoundsAttemptsAndAge() {
        val policy = EventRetryPolicy()

        assertTrue(policy.shouldRetry(attempt = 1, ageMillis = 0L))
        assertTrue(policy.shouldRetry(attempt = 4, ageMillis = 119_999L))
        assertTrue(policy.shouldRetry(attempt = 5, ageMillis = 0L))
        assertEquals(false, policy.shouldRetry(attempt = 6, ageMillis = 0L))
        assertEquals(false, policy.shouldRetry(attempt = 2, ageMillis = 120_001L))
        assertTrue(policy.delayForAttempt(1) in 1_000L..1_249L)
        assertTrue(policy.delayForAttempt(10) in 8_000L..8_249L)
    }
}
