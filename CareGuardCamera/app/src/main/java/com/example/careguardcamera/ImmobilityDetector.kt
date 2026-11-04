package com.example.careguardcamera

class ImmobilityDetector(
    private val fps: Float = 30f,
    private val tFallen: Float = 3.0f, // R1 지속시간 (3초)
    private val tStill: Float = 5.0f,  // R2 미동시간 (5초)
    private val eps: Float = 0.02f,
    private val horizThr: Float = 1.0f, // 수평도 임계값
    private val fallCooldown: Float = 2.0f
) {
    private var fallenSince: Float? = null
    private var stillSince: Float? = null
    private var r1Fired = false
    private var r2Fired = false
    private var previousState = "no_person"
    private var lastFallAt = Float.NEGATIVE_INFINITY

    fun update(t: Float, state: String, currentFrame: Array<Keypoint>?): List<String> {
        val stillWindow = currentFrame?.let { listOf(it) }
        return updateWindow(t, state, stillWindow)
    }

    fun updateWindow(t: Float, state: String, stillWindow: List<Array<Keypoint>>?): List<String> {
        val events = mutableListOf<String>()

        // 낙상 순간 - Python realtime_demo.py 와 같은 fall 상승 에지 + cooldown
        if (state == "fall" && previousState != "fall" && t - lastFallAt >= fallCooldown) {
            events.add("FALL (model)")
            lastFallAt = t
        }

        // R1 - 낙상 후 방치
        if (state == "fall" || state == "fallen") {
            if (fallenSince == null) fallenSince = t
            if (!r1Fired && (t - fallenSince!!) >= tFallen) {
                events.add("FALLEN_IMMOBILE (R1)")
                r1Fired = true
            }
        } else {
            fallenSince = null
            r1Fired = false
        }

        // R2 - 누운 채 미동
        if (!stillWindow.isNullOrEmpty() && state != "no_person") {
            val horiz = PosePipeline.torsoHorizontality(stillWindow.last())
            val moving = PosePipeline.motionEnergy(stillWindow)
            if (moving < eps && horiz > horizThr) {
                if (stillSince == null) stillSince = t
                if (!r2Fired && (t - stillSince!!) >= tStill) {
                    events.add("STILL_HORIZONTAL (R2)")
                    r2Fired = true
                }
            } else {
                stillSince = null
                r2Fired = false
            }
        } else {
            stillSince = null
            r2Fired = false
        }

        previousState = state
        return events
    }
}
