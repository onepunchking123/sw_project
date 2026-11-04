package com.example.careguardcamera.video

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** 클립에 쓸 프레임 1장. 메모리를 아끼려고 회전 전 JPEG로 보관한다. */
class ClipFrame(
    val timestampNanos: Long,
    val capturedAtMillis: Long,
    val jpeg: ByteArray,
    val rotationDegrees: Int,
    val pose: OverlayPose?
)

/**
 * 최근 [keepNanos] 동안의 프레임을 시간순으로 보관한다. [minIntervalNanos]보다 촘촘한 프레임은 받지 않는다.
 * 프레임 종류와 무관한 순수 로직이라 단위 테스트로 검증한다.
 */
class ClipFrameWindow<T>(
    private val keepNanos: Long,
    private val minIntervalNanos: Long
) {
    private val frames = java.util.ArrayDeque<Pair<Long, T>>()

    @Synchronized
    fun shouldAccept(timestampNanos: Long): Boolean {
        val last = frames.peekLast()?.first ?: return true
        return timestampNanos - last >= minIntervalNanos
    }

    @Synchronized
    fun add(timestampNanos: Long, frame: T) {
        if (!shouldAccept(timestampNanos)) return
        frames.addLast(timestampNanos to frame)
        while (frames.isNotEmpty() && timestampNanos - frames.peekFirst()!!.first > keepNanos) {
            frames.removeFirst()
        }
    }

    @Synchronized
    fun clear() = frames.clear()

    @Synchronized
    fun latestTimestamp(): Long? = frames.peekLast()?.first

    @Synchronized
    fun between(fromNanos: Long, toNanos: Long): List<T> =
        frames.filter { it.first in fromNanos..toNanos }.map { it.second }
}

/**
 * 낙상 순간 전후 프레임을 모은다. 카메라는 R1(쓰러진 채 3초) 시점에 요청하므로
 * 실제로 넘어진 장면은 기준 시각보다 약 3초 앞이고 [preNanos] 안에 들어간다.
 */
class IncidentClipRecorder(
    private val preNanos: Long = 5_000_000_000L,
    private val postNanos: Long = 5_000_000_000L,
    framesPerSecond: Int = 8,
    private val jpegQuality: Int = 70,
    private val window: ClipFrameWindow<ClipFrame> = ClipFrameWindow(
        keepNanos = preNanos + postNanos + 2_000_000_000L,
        minIntervalNanos = 1_000_000_000L / framesPerSecond
    )
) {
    /** 프레임 수집 코루틴에서 호출한다. JPEG 압축이 호출 스레드에서 일어나므로 메인 스레드에서 부르지 않는다. */
    fun offer(frame: LiveFrame, pose: OverlayPose?) {
        if (!window.shouldAccept(frame.timestampNanos)) return
        window.add(
            frame.timestampNanos,
            ClipFrame(frame.timestampNanos, frame.capturedAtMillis, frame.toJpeg(jpegQuality), frame.rotationDegrees, pose)
        )
    }

    fun clear() = window.clear()

    /** 기준 시각 뒤 [postNanos]까지 프레임이 쌓이길 기다렸다가 전후 구간을 돌려준다. 카메라가 멈추면 모인 만큼만 준다. */
    suspend fun framesAround(triggerNanos: Long, waitLimitMillis: Long = postNanos / 1_000_000L + 3_000L): List<ClipFrame> {
        withTimeoutOrNull(waitLimitMillis) {
            while ((window.latestTimestamp() ?: Long.MIN_VALUE) < triggerNanos + postNanos) delay(200L)
        }
        return window.between(triggerNanos - preNanos, triggerNanos + postNanos)
    }
}
