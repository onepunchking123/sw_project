package com.example.careguardcamera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class Keypoint(val x: Float, val y: Float, val conf: Float)

object PosePipeline {
    private const val L_SH = 5
    private const val R_SH = 6
    private const val L_HIP = 11
    private const val R_HIP = 12
    private const val VEL_SCALE = 10.0f

    // 엉덩이 중점 이동 + 몸통 길이 스케일링 정규화
    fun normalize(rawKps: Array<Keypoint>, width: Int, height: Int): Array<Keypoint> {
        val w = max(width, 1).toFloat()
        val h = max(height, 1).toFloat()

        val norm = Array(17) { i ->
            Keypoint(rawKps[i].x / w, rawKps[i].y / h, rawKps[i].conf)
        }

        val hipX = (norm[L_HIP].x + norm[R_HIP].x) / 2f
        val hipY = (norm[L_HIP].y + norm[R_HIP].y) / 2f
        val shoX = (norm[L_SH].x + norm[R_SH].x) / 2f
        val shoY = (norm[L_SH].y + norm[R_SH].y) / 2f

        var torso = sqrt((shoX - hipX) * (shoX - hipX) + (shoY - hipY) * (shoY - hipY))
        if (torso < 1e-3f) torso = 1.0f

        return Array(17) { i ->
            Keypoint(
                x = (norm[i].x - hipX) / torso,
                y = (norm[i].y - hipY) / torso,
                conf = norm[i].conf
            )
        }
    }

    // 관절 속도 포함 5채널(posvel) 합성
    fun addVelocity(window: List<Array<Keypoint>>, fps: Float): Array<FloatArray> {
        val T = window.size
        val result = Array(T) { FloatArray(17 * 5) }

        for (t in 0 until T) {
            val cur = window[t]
            val prev = if (t > 0) window[t - 1] else window[0]
            val next = if (t == 0 && T >= 2) window[1] else cur

            for (i in 0 until 17) {
                val copyFirst = t == 0 && T >= 2
                val other = if (copyFirst) next else prev
                val valid = cur[i].conf > 0f && other[i].conf > 0f
                val vx = if (t > 0 && valid) {
                    (cur[i].x - other[i].x) * (fps / VEL_SCALE)
                } else if (copyFirst && valid) {
                    (other[i].x - cur[i].x) * (fps / VEL_SCALE)
                } else 0f

                val vy = if (t > 0 && valid) {
                    (cur[i].y - other[i].y) * (fps / VEL_SCALE)
                } else if (copyFirst && valid) {
                    (other[i].y - cur[i].y) * (fps / VEL_SCALE)
                } else 0f

                val offset = i * 5
                result[t][offset + 0] = cur[i].x
                result[t][offset + 1] = cur[i].y
                result[t][offset + 2] = cur[i].conf
                result[t][offset + 3] = vx
                result[t][offset + 4] = vy
            }
        }
        return result
    }

    fun addVelocityTimed(window: List<Array<Keypoint>>, timestampsMs: List<Long>): Array<FloatArray> {
        require(window.size == timestampsMs.size) { "window/timestamp size mismatch" }
        val result = Array(window.size) { FloatArray(17 * 5) }
        for (t in window.indices) {
            val cur = window[t]
            val prevIndex = if (t > 0) t - 1 else 0
            val nextIndex = if (t == 0 && window.size >= 2) 1 else t
            val otherIndex = if (t == 0 && window.size >= 2) nextIndex else prevIndex
            val dtSec = if (otherIndex == t) {
                0f
            } else {
                abs(timestampsMs[t] - timestampsMs[otherIndex]).coerceAtLeast(1L) / 1000f
            }
            for (i in 0 until 17) {
                val other = window[otherIndex][i]
                val valid = cur[i].conf > 0f && other.conf > 0f && dtSec > 0f
                val vx = if (!valid) {
                    0f
                } else if (t == 0) {
                    (other.x - cur[i].x) / (dtSec * VEL_SCALE)
                } else {
                    (cur[i].x - other.x) / (dtSec * VEL_SCALE)
                }
                val vy = if (!valid) {
                    0f
                } else if (t == 0) {
                    (other.y - cur[i].y) / (dtSec * VEL_SCALE)
                } else {
                    (cur[i].y - other.y) / (dtSec * VEL_SCALE)
                }
                val offset = i * 5
                result[t][offset + 0] = cur[i].x
                result[t][offset + 1] = cur[i].y
                result[t][offset + 2] = cur[i].conf
                result[t][offset + 3] = vx
                result[t][offset + 4] = vy
            }
        }
        return result
    }

    // 시퀀스 길이 64로 리샘플링 보간
    fun resample(data: Array<FloatArray>, targetLen: Int = 64): Array<FloatArray> {
        val srcLen = data.size
        val featDim = data[0].size
        val resampled = Array(targetLen) { FloatArray(featDim) }

        for (i in 0 until targetLen) {
            val idx = i.toFloat() * (srcLen - 1) / (targetLen - 1)
            val lo = idx.toInt()
            val hi = minOf(lo + 1, srcLen - 1)
            val w = idx - lo

            for (j in 0 until featDim) {
                resampled[i][j] = data[lo][j] * (1f - w) + data[hi][j] * w
            }
        }
        return resampled
    }

    fun resampleTimed(data: Array<FloatArray>, timestampsMs: List<Long>, targetLen: Int = 64): Array<FloatArray> {
        require(data.isNotEmpty()) { "cannot resample empty data" }
        require(data.size == timestampsMs.size) { "data/timestamp size mismatch" }
        return resampleTimed(
            data = data,
            timestampsMs = timestampsMs,
            targetLen = targetLen,
            startMs = timestampsMs.first(),
            endMs = timestampsMs.last()
        )
    }

    fun resampleTimed(
        data: Array<FloatArray>,
        timestampsMs: List<Long>,
        targetLen: Int = 64,
        startMs: Long,
        endMs: Long
    ): Array<FloatArray> {
        require(data.isNotEmpty()) { "cannot resample empty data" }
        require(data.size == timestampsMs.size) { "data/timestamp size mismatch" }
        require(endMs >= startMs) { "resample end must be at or after start" }
        if (data.size == 1 || startMs == endMs) {
            return Array(targetLen) { data[0].copyOf() }
        }
        val featDim = data[0].size
        val out = Array(targetLen) { FloatArray(featDim) }
        var lo = 0
        for (i in 0 until targetLen) {
            val target = startMs.toFloat() + (endMs - startMs).toFloat() * i.toFloat() / (targetLen - 1).coerceAtLeast(1)
            while (lo < timestampsMs.lastIndex - 1 && timestampsMs[lo + 1] < target) {
                lo += 1
            }
            val hi = minOf(lo + 1, timestampsMs.lastIndex)
            val denom = (timestampsMs[hi] - timestampsMs[lo]).coerceAtLeast(1L).toFloat()
            val w = ((target - timestampsMs[lo].toFloat()) / denom).coerceIn(0f, 1f)
            for (j in 0 until featDim) {
                out[i][j] = data[lo][j] * (1f - w) + data[hi][j] * w
            }
        }
        return out
    }

    fun motionEnergy(window: List<Array<Keypoint>>): Float {
        if (window.size < 2) return 0f
        var total = 0f
        var count = 0
        for (t in 1 until window.size) {
            val cur = window[t]
            val prev = window[t - 1]
            for (i in 0 until 17) {
                if (cur[i].conf > 0.05f && prev[i].conf > 0.05f) {
                    val dx = cur[i].x - prev[i].x
                    val dy = cur[i].y - prev[i].y
                    total += sqrt(dx * dx + dy * dy)
                    count += 1
                }
            }
        }
        return if (count == 0) 0f else total / count
    }

    fun detectionRate(window: List<Array<Keypoint>>): Float {
        if (window.isEmpty()) return 0f
        val detected = window.count { frame ->
            frame.any { abs(it.x) + abs(it.y) > 0f }
        }
        return detected.toFloat() / window.size.toFloat()
    }

    // 수평도 계산 (R2 미동 상태 검출용)
    fun torsoHorizontality(frame: Array<Keypoint>): Float {
        val shoX = (frame[L_SH].x + frame[R_SH].x) / 2f
        val shoY = (frame[L_SH].y + frame[R_SH].y) / 2f
        val hipX = (frame[L_HIP].x + frame[R_HIP].x) / 2f
        val hipY = (frame[L_HIP].y + frame[R_HIP].y) / 2f

        val vx = shoX - hipX
        val vy = shoY - hipY
        return abs(vx) / (abs(vy) + 1e-6f)
    }
}
