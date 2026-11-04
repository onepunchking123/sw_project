package com.example.careguardcamera

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFilePath: String? = null

    companion object {
        private const val TAG = "VideoRecorder"
    }

    // 녹화 시작
    fun startRecording(): String? {
        if (isRecording) return currentFilePath

        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = context.getExternalFilesDir("fall_videos")
            if (storageDir?.exists() == false) storageDir.mkdirs()

            val file = File(storageDir, "FALL_RECORD_$timeStamp.mp4")
            currentFilePath = file.absolutePath

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(10000000)
                setVideoFrameRate(30)
                setVideoSize(1280, 720)
                setOutputFile(currentFilePath)
                prepare()
                start()
            }

            isRecording = true
            Log.d(TAG, "자동 비상 녹화 시작: $currentFilePath")
            return currentFilePath

        } catch (e: Exception) {
            Log.e(TAG, "녹화 시작 실패", e)
            stopRecording()
            return null
        }
    }

    // 녹화 중지
    fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            Log.d(TAG, "녹화 저장 완료: $currentFilePath")
        } catch (e: Exception) {
            Log.e(TAG, "녹화 중지 중 오류 발생", e)
        }
    }
}