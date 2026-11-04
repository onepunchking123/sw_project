package com.example.careguardcamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var tvState: TextView
    private lateinit var tvProb: TextView
    private lateinit var tvAlert: TextView

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var alarmHelper: AlarmHelper
    private lateinit var videoRecorder: VideoRecorder

    private val httpClient = OkHttpClient()

    private var emergencyDialog: AlertDialog? = null
    private var countDownTimer: CountDownTimer? = null
    private var isAlertActive = false // 중복 팝업/녹화 방지 플래그

    companion object {
        private const val TAG = "CareGuardCamera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // 💡 백엔드 서버 URL (에뮬레이터: 10.0.2.2, 실기기: PC의 IPv4 주소)
        private const val SERVER_URL = "http://10.0.2.2:8000/api/fcm/send-alert"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. UI 뷰 참조 연결
        viewFinder = findViewById(R.id.viewFinder)
        tvState = findViewById(R.id.tvState)
        tvProb = findViewById(R.id.tvProb)
        tvAlert = findViewById(R.id.tvAlert)

        // 2. 헬퍼 클래스 및 쓰레드 초기화
        alarmHelper = AlarmHelper(this)
        videoRecorder = VideoRecorder(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // 3. 권한 확인 후 카메라 시작
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, PoseAnalyzer(this) { state, probs, events ->
                        runOnUiThread {
                            updateUI(state, probs, events)

                            // 💡 비상 알림 이벤트(R1/R2) 수신 시 자동 녹화 및 카운트다운 트리거
                            if (events.isNotEmpty() && !isAlertActive) {
                                onFallDetected(events.joinToString(", "))
                            }
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUI(state: String, probs: FloatArray, events: List<String>) {
        val stateText = state.uppercase()
        tvState.text = "상태: $stateText"

        when (state) {
            "normal" -> tvState.setTextColor(Color.GREEN)
            "fall", "fallen" -> tvState.setTextColor(Color.RED)
            else -> tvState.setTextColor(Color.GRAY)
        }

        if (probs.size >= 3) {
            tvProb.text = String.format(
                "정상: %.2f | 낙상: %.2f | 방치: %.2f",
                probs[0], probs[1], probs[2]
            )
        }

        if (events.isNotEmpty()) {
            tvAlert.text = "🚨 비상 알림 감지: ${events.joinToString(", ")}"
            tvAlert.setBackgroundColor(Color.RED)
            tvAlert.setTextColor(Color.WHITE)
        } else {
            tvAlert.text = "상태 정상"
            tvAlert.setBackgroundColor(Color.TRANSPARENT)
            tvAlert.setTextColor(Color.DKGRAY)
        }
    }

    // 💡 낙상 감지 시 실행되는 핵심 핸들러 (자동 녹화 + 팝업 + 타이머)
    private fun onFallDetected(reason: String) {
        isAlertActive = true

        // 1. 즉시 현장 기기 자동 녹화 시작
        val savedPath = videoRecorder.startRecording()
        Log.d(TAG, "비상 녹화 시작됨: $savedPath")

        // 2. 사이렌 및 진동 발생
        alarmHelper.startAlarm()

        // 3. 10초 카운트다운 팝업 생성
        val builder = AlertDialog.Builder(this)
            .setTitle("🚨 낙상 비상 감지 ($reason)")
            .setMessage("10초 후 보호자에게 비상 알림이 발송됩니다.\n괜찮으시다면 취소 버튼을 눌러주세요.")
            .setCancelable(false)
            .setPositiveButton("괜찮습니다 (취소)") { dialog, _ ->
                dismissEmergencyAlert()
                dialog.dismiss()
            }

        emergencyDialog = builder.create()
        emergencyDialog?.show()

        // 4. 10초 타이머 시작 (10초 무응답 시 FCM 발송)
        countDownTimer = object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                emergencyDialog?.setMessage(
                    "낙상 위험 상황이 감지되었습니다!\n\n" +
                            "⏱️ $secondsLeft 초 후 긴급 비상 알림이 자동 발송됩니다.\n" +
                            "오탐이거나 괜찮으시다면 아래 버튼을 눌러주세요."
                )
            }

            override fun onFinish() {
                // 10초간 무응답 시: 알람 끄기 + 녹화 종료 + 보호자 FCM 푸시 요청 전송
                alarmHelper.stopAlarm()
                videoRecorder.stopRecording()
                emergencyDialog?.dismiss()

                Toast.makeText(applicationContext, "🚨 보호자에게 비상 푸시 알림을 발송합니다!", Toast.LENGTH_LONG).show()

                // 백엔드로 FCM 푸시 요청
                sendFcmAlertToBackend(reason)

                isAlertActive = false
            }
        }.start()
    }

    // 💡 파이썬 FastAPI 백엔드로 FCM 푸시 발송 요청 보내기
    private fun sendFcmAlertToBackend(reason: String) {
        try {
            val json = JSONObject().apply {
                put("title", "🚨 [CareGuard] 낙상 사고 비상 알림!")
                put("body", "환자에게 낙상 위험 상황이 감지되었습니다 ($reason). 즉시 확인해 주세요.")
                put("patient_name", "사용자")
                put("timestamp", System.currentTimeMillis())
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(body)
                .build()

            httpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "FCM 백엔드 전송 실패: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.d(TAG, "보호자 푸시 알림 요청 성공: ${response.code}")
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "FCM 전송 중 에러 발생", e)
        }
    }

    // 사용자가 '괜찮습니다' 취소 버튼을 눌렀을 때
    private fun dismissEmergencyAlert() {
        countDownTimer?.cancel()
        countDownTimer = null
        alarmHelper.stopAlarm()
        videoRecorder.stopRecording()
        isAlertActive = false
        Toast.makeText(this, "비상 알림 및 녹화가 취소되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "카메라 및 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissEmergencyAlert()
        cameraExecutor.shutdown()
    }
}