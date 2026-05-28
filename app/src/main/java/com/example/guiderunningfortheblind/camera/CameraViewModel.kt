package com.example.guiderunningfortheblind.camera

import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

enum class DangerLevel { SAFE, CAUTION, DANGER }

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val obstacleDetector: ObstacleDetector,
    private val vibrator: Vibrator
) : ViewModel() {

    private val tag = "CameraViewModel"

    private val _obstacleDetectionCount = MutableSharedFlow<Unit>()
    val obstacleDetectionCount: SharedFlow<Unit> = _obstacleDetectionCount.asSharedFlow()

    private val _sceneFrameFlow = MutableSharedFlow<Bitmap>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val sceneFrameFlow: SharedFlow<Bitmap> = _sceneFrameFlow.asSharedFlow()

    private val sceneFrameInterval = 3_000L
    private var lastSceneFrameTime = 0L

    private val processingLock = AtomicBoolean(false)

    val analyzer = ImageAnalysis.Analyzer { imageProxy ->
        if (processingLock.getAndSet(true)) {
            imageProxy.close()
            return@Analyzer
        }
        try {
            processImage(imageProxy)
        } catch (e: Exception) {
            Log.e(tag, "Frame processing crashed", e)
        } finally {
            processingLock.set(false)
            imageProxy.close()
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.e(tag, "toBitmap failed: ${e.message}", e)
            return
        }

        val now = System.currentTimeMillis()

        try {
            // Local TFLite 已移除，此处不再执行本地推理和震动反馈
            // 保留此逻辑的结构以供将来通过远程 AI 分析结果触发事件
            val results = obstacleDetector.detect(bitmap)
            if (results.isNotEmpty()) {
                // 这里的逻辑在本地 TFLite 模式下目前不会执行，因为 detect() 返回空列表
                Log.d(tag, "检测到 ${results.size} 个潜在障碍物 (Stub)")
            }

            // 仍然需要将帧发送到场景分析器 (AiChatViewModel / Gemini Vision)
            if (now - lastSceneFrameTime >= sceneFrameInterval) {
                lastSceneFrameTime = now
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                    _sceneFrameFlow.emit(copy)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.e(tag, "振动失败: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        obstacleDetector.close()
    }
}
