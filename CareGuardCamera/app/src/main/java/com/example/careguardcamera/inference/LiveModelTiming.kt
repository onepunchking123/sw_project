package com.example.careguardcamera.inference

object LiveModelTiming {
    private const val TIMESTAMP_TOLERANCE_MS = 1L

    fun requireStrictlyIncreasing(previousTimestampMs: Long?, nextTimestampMs: Long) {
        if (previousTimestampMs != null && nextTimestampMs <= previousTimestampMs) {
            throw IllegalArgumentException("capture timestamps must be strictly increasing")
        }
    }

    fun relativeTimestamp(firstTimestampMs: Long, timestampMs: Long): Long {
        require(timestampMs >= firstTimestampMs) { "timestamp must be at or after baseline" }
        return timestampMs - firstTimestampMs
    }

    fun hasEnoughCoverage(timestampsMs: List<Long>, windowSec: Float): Boolean {
        if (timestampsMs.size < 2) return false
        val spanMs = timestampsMs.last() - timestampsMs.first()
        val requiredMs = (windowSec * 1000f).toLong()
        return spanMs >= requiredMs - TIMESTAMP_TOLERANCE_MS
    }

    fun hasIntervalCoverage(timestampsMs: List<Long>, startMs: Long, endMs: Long): Boolean {
        if (timestampsMs.size < 2) return false
        return timestampsMs.first() <= startMs + TIMESTAMP_TOLERANCE_MS &&
            timestampsMs.last() >= endMs - TIMESTAMP_TOLERANCE_MS
    }

    fun boundaryWindowStartIndex(timestampsMs: List<Long>, cutoffMs: Long): Int {
        val firstAfterCutoff = timestampsMs.indexOfFirst { it >= cutoffMs }
        return when {
            firstAfterCutoff < 0 -> timestampsMs.lastIndex
            firstAfterCutoff == 0 -> 0
            else -> firstAfterCutoff - 1
        }
    }
}
