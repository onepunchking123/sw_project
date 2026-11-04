package com.example.careguardcamera.video

import com.example.careguardcamera.data.ApiHttpException
import com.example.careguardcamera.data.DetectionEventUpload
import com.example.careguardcamera.data.FallIncidentApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 카메라 폰 화면에 보이는 낙상 대응 단계. */
sealed interface FallAlertState {
    data object Idle : FallAlertState
    data class Countdown(val clientEventId: String, val secondsLeft: Int) : FallAlertState
    /** 환자가 '괜찮아요'를 눌렀다. 영상은 보내지 않는다. 서버에 취소가 전달됐는지는 [delivery]로 구분한다. */
    data class PatientOk(val delivery: CancelDelivery) : FallAlertState
    /** 응답이 없어 보호자에게 알렸다. */
    data class Escalated(val clip: ClipDelivery) : FallAlertState
}

/** NOT_AVAILABLE은 개인정보 설정이나 사람 미인식으로 클립을 만들지 않은 경우, FAILED는 인코딩·업로드 오류다. */
enum class ClipDelivery { PREPARING, UPLOADED, NOT_AVAILABLE, FAILED }

enum class CancelDelivery { SENDING, SENT, FAILED }

interface FallAlarm {
    fun start()
    fun stop()
}

/**
 * 쓰러진 채 3초가 지난(R1) 낙상을 카메라 폰에서 처리한다.
 * 사이렌과 함께 [countdownSeconds]초를 세고, 그 안에 환자가 취소하면 서버에 '괜찮음'을 알리고 끝낸다.
 * 취소가 없으면 낙상 전후 클립을 올려 보호자 긴급 화면에서 볼 수 있게 한다. 긴급 상황 자체는 서버가 R1 이벤트로 이미 열어 둔다.
 */
class FallIncidentCoordinator(
    private val scope: CoroutineScope,
    private val api: FallIncidentApi,
    private val cameraId: () -> String?,
    private val alarm: FallAlarm,
    private val captureClip: suspend (DetectionEventUpload) -> EncodedClip?,
    private val countdownSeconds: Int = 10,
    private val resultVisibleMillis: Long = 8_000L,
    private val retryDelaysMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L),
    private val onClipError: (Throwable) -> Unit = {}
) {
    private val _state = MutableStateFlow<FallAlertState>(FallAlertState.Idle)
    val state: StateFlow<FallAlertState> = _state

    private var countdownJob: Job? = null
    private var clipJob: Deferred<Result<EncodedClip?>>? = null

    /**
     * 앞선 낙상을 처리하는 동안(카운트다운·업로드·결과 표시)에는 새 낙상을 받지 않는다.
     * 서버도 열린 긴급 상황이 있는 동안 짧은 간격의 R1으로 새 긴급 상황을 만들지 않는다.
     */
    fun onFallIncident(event: DetectionEventUpload) {
        if (_state.value != FallAlertState.Idle) return
        // 직후 5초를 모아야 하므로 카운트다운과 동시에 클립 준비를 시작한다.
        // 실패를 Result로 감싸 둔다. async 안에서 예외가 그대로 나가면 화면 전체 스코프가 취소된다.
        val clip = scope.async { runCatching { captureClip(event) } }
        clipJob = clip
        countdownJob = scope.launch {
            alarm.start()
            try {
                for (left in countdownSeconds downTo 1) {
                    _state.value = FallAlertState.Countdown(event.clientEventId, left)
                    delay(1_000L)
                }
            } finally {
                alarm.stop()
            }
            _state.value = FallAlertState.Escalated(ClipDelivery.PREPARING)
            val delivery = try {
                val encoded = clip.await().getOrThrow()
                if (encoded == null) ClipDelivery.NOT_AVAILABLE else upload(event, encoded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onClipError(e)
                ClipDelivery.FAILED
            }
            _state.value = FallAlertState.Escalated(delivery)
            delay(resultVisibleMillis)
            _state.value = FallAlertState.Idle
        }
    }

    fun patientOk() {
        val current = _state.value as? FallAlertState.Countdown ?: return
        countdownJob?.cancel()
        clipJob?.cancel()
        alarm.stop()
        _state.value = FallAlertState.PatientOk(CancelDelivery.SENDING)
        scope.launch {
            val sent = withRetry { api.reportPatientOk(requireCameraId(), current.clientEventId) }
            // 취소가 서버에 닿지 않으면 보호자에게 경보가 간다. 환자에게도 그 사실을 보여 준다.
            _state.value = FallAlertState.PatientOk(if (sent) CancelDelivery.SENT else CancelDelivery.FAILED)
            delay(resultVisibleMillis)
            _state.value = FallAlertState.Idle
        }
    }

    private suspend fun upload(event: DetectionEventUpload, clip: EncodedClip): ClipDelivery {
        val startedAt = isoUtc(clip.startedAtMillis)
        return if (withRetry { api.uploadClip(requireCameraId(), event.clientEventId, clip.mp4, startedAt, clip.durationSec) }) {
            ClipDelivery.UPLOADED
        } else {
            ClipDelivery.FAILED
        }
    }

    /**
     * 이벤트 업로드가 늦게 끝나면 서버가 아직 이벤트를 모를 수 있어 404도 다시 시도한다.
     * 인증 오류나 충돌(환자가 이미 괜찮다고 함)은 다시 시도해도 달라지지 않는다.
     */
    private suspend fun withRetry(block: suspend () -> Unit): Boolean {
        for (attempt in 0..retryDelaysMillis.size) {
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiHttpException) {
                if (e.code in setOf(400, 401, 403, 409, 413, 415)) return false
            } catch (_: Exception) {
                // 네트워크 오류는 다시 시도한다.
            }
            val wait = retryDelaysMillis.getOrNull(attempt) ?: return false
            delay(wait)
        }
        return false
    }

    private fun requireCameraId(): String = cameraId() ?: throw IllegalStateException("카메라 등록 정보가 필요합니다.")

    private fun isoUtc(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(millis))
}
