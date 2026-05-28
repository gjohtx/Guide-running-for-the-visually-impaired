package com.example.guiderunningfortheblind.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log

/**
 * 障碍物检测器 (TFLite 已移除)
 * 此类目前仅作为占位符，detect() 始终返回空列表。
 * 未来的障碍物检测将通过远程 AI (如 Gemini Vision) 或新的端侧模型实现。
 */
class ObstacleDetector(private val context: Context) {
    
    init {
        Log.d("ObstacleDetector", "初始化 (Local TFLite 模式已禁用)")
    }

    /**
     * 模拟检测，始终返回空列表
     */
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        return emptyList()
    }

    /**
     * 模拟检测，不再执行推理
     */
    fun detectMarkers(
        imageProxy: androidx.camera.core.ImageProxy,
        onObstacleDetected: (String, Float) -> Unit
    ) {
        // 关闭资源以防止内存泄漏
        imageProxy.close()
    }

    /**
     * 估算距离 (保留逻辑供未来参考或其它模块使用)
     */
    fun estimateDistance(boundingBox: RectF, objectClass: String): Float {
        val boxHeightRatio = boundingBox.height()
        val classConstant = when (objectClass.lowercase()) {
            "person" -> 2.5f
            "car", "truck", "bus" -> 3.0f
            "bicycle", "motorcycle" -> 2.0f
            else -> 1.5f
        }
        if (boxHeightRatio <= 0.01f) return 10.0f
        return (classConstant / boxHeightRatio).coerceIn(0.5f, 15.0f)
    }

    fun close() {
        Log.d("ObstacleDetector", "正在关闭资源")
    }

    data class DetectionResult(
        val boundingBox: RectF,
        val categories: List<Category>
    )

    data class Category(
        val label: String,
        val score: Float
    )
}
