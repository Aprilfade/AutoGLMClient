package com.example.autoglmclient.utils

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {
    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()

        // 缩放图片: 如果原图宽度大于 720，则等比缩放到宽度 720
        var finalBitmap = bitmap
        if (bitmap.width > 720) {
            val scale = 720f / bitmap.width
            val newHeight = (bitmap.height * scale).toInt()
            finalBitmap = Bitmap.createScaledBitmap(bitmap, 720, newHeight, true)
        }

        // 压缩格式为 JPEG, 质量 80% (对于截图识别，60-80% 足够)
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)

        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}