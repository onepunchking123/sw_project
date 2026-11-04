package com.example.careguardcamera.video

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LiveFrame(
    val timestampNanos: Long,
    val capturedAtMillis: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val format: Int,
    val planes: List<Plane>
) {
    data class Plane(
        val bytes: ByteArray,
        val rowStride: Int,
        val pixelStride: Int
    )
}

class LatestFrameFanout<T> {
    private val _frames = MutableSharedFlow<T>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: SharedFlow<T> = _frames

    fun offer(frame: T) {
        _frames.tryEmit(frame)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun clear() {
        _frames.resetReplayCache()
    }
}

class LiveCaptureSession(
    private val context: Context,
    private val previewView: PreviewView,
    private val lifecycleOwner: LifecycleOwner,
    private val selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    initialExecutor: ExecutorService = Executors.newSingleThreadExecutor()
) : LiveFrameSource {
    private val fanout = LatestFrameFanout<LiveFrame>()
    override val frames: SharedFlow<LiveFrame> = fanout.frames

    private var provider: ProcessCameraProvider? = null
    private var executor: ExecutorService = initialExecutor
    private var started = false

    override suspend fun start() {
        withContext(Dispatchers.Main.immediate) {
            if (started) return@withContext
            if (executor.isShutdown || executor.isTerminated) {
                executor = Executors.newSingleThreadExecutor()
            }
            val cameraProvider = context.applicationContext.cameraProvider()
            provider = cameraProvider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(executor) { image ->
                        try {
                            fanout.offer(image.copyLiveFrame())
                        } finally {
                            image.close()
                        }
                    }
                }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                started = true
            } catch (e: Exception) {
                started = false
                provider = null
                cameraProvider.unbindAll()
                throw e
            }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main.immediate) {
            started = false
            provider?.unbindAll()
            provider = null
            executor.shutdownNow()
            fanout.clear()
        }
    }

    private fun ImageProxy.copyLiveFrame(): LiveFrame {
        val copiedPlanes = planes.map { plane ->
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            LiveFrame.Plane(bytes, plane.rowStride, plane.pixelStride)
        }
        return LiveFrame(
            timestampNanos = imageInfo.timestamp,
            capturedAtMillis = System.currentTimeMillis(),
            width = width,
            height = height,
            rotationDegrees = imageInfo.rotationDegrees,
            format = format,
            planes = copiedPlanes
        )
    }
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    if (continuation.isActive) continuation.resume(future.get())
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
