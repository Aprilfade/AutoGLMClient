// 文件位置: app/src/main/java/com/example/autoglmclient/utils/ImageUtils.kt
package com.example.autoglmclient.utils

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {
    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()

        // 缩放图片 (可选): 如果原图太大，AI 处理慢，可以在这里先 createScaledBitmap
        // val scaled = Bitmap.createScaledBitmap(bitmap, 720, 1280, true)

        // 压缩格式为 JPEG, 质量 80%
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)

        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}