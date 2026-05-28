package com.example.guiderunningfortheblind.util

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 将 CameraX ImageProxy 安全转换为 Bitmap。
 * 优先支持 RGBA_8888（ImageAnalysis 标准输出），回退到 YUV_420_888。
 * 自动处理 rowStride padding 与图像旋转。
 */
fun ImageProxy.toBitmap(): Bitmap {
    return when (format) {
        ImageFormat.YUV_420_888 -> yuvToBitmap()
        else -> rgbaToBitmap()
    }
}

/**
 * RGBA_8888 格式直接转换。
 * CameraX ImageAnalysis.setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888) 时，
 * planes[0] 包含完整像素，但 rowStride 可能因内存对齐大于 width * 4。
 */
private fun ImageProxy.rgbaToBitmap(): Bitmap {
    val plane = planes[0]
    val buffer: ByteBuffer = plane.buffer
    val rowStride = plane.rowStride
    val w = width
    val h = height

    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    if (rowStride == w * 4) {
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
    } else {
        // 逐行处理 padding，构建连续 buffer
        val continuousBuffer = ByteBuffer.allocateDirect(w * h * 4)
        val rowBytes = ByteArray(w * 4)
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            buffer.get(rowBytes, 0, w * 4)
            continuousBuffer.put(rowBytes)
        }
        continuousBuffer.rewind()
        bitmap.copyPixelsFromBuffer(continuousBuffer)
    }

    return rotateIfNeeded(bitmap, imageInfo.rotationDegrees)
}

/**
 * YUV_420_888 -> NV21 -> JPEG -> Bitmap。
 * 作为低性能回退，仅当设备不支持 RGBA 输出时使用。
 */
private fun ImageProxy.yuvToBitmap(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()

    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
}

private fun rotateIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { rotated ->
            if (rotated != bitmap) bitmap.recycle()
        }
}