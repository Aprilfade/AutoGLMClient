// 文件位置: app/src/main/java/com/example/autoglmclient/ScreenCaptureService.kt
package com.example.autoglmclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo // [新增]
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build // [新增]
import android.os.IBinder

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    companion object {
        var instance: ScreenCaptureService? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        val notification = createNotification()

        // [修改] 针对 Android 10+ (特别是 Android 14) 必须指定服务类型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1, notification)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>("DATA")
        val width = intent?.getIntExtra("WIDTH", 720) ?: 720
        val height = intent?.getIntExtra("HEIGHT", 1280) ?: 1280
        val density = intent?.getIntExtra("DENSITY", 1) ?: 1

        if (resultCode != -1 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData, intent.getIntExtra("WIDTH", 720), intent.getIntExtra("HEIGHT", 1280), intent.getIntExtra("DENSITY", 1))
        return START_STICKY
    }

    private fun startProjection(code: Int, data: Intent, w: Int, h: Int, dpi: Int) {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(code, data)

            // ⚠️ 修复闪退点 1: 确保宽高不为 0
            val safeWidth = if (w > 0) w else 720
            val safeHeight = if (h > 0) h else 1280
            // ⚠️ 修复闪退点 2: 确保 density 不为 0
            val safeDpi = if (dpi > 0) dpi else 320

            // ⚠️ 修复闪退点 3: 加上 try-catch 防止 ImageReader 创建失败
            try {
                imageReader = ImageReader.newInstance(safeWidth, safeHeight, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "AutoGLMScreen",
                    safeWidth, safeHeight, safeDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null, null
                )
            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("AutoGLM", "ImageReader 创建失败: ${e.message}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 这里捕获所有异常，防止 App 闪退到桌面
            android.util.Log.e("AutoGLM", "❌ 录屏服务启动严重错误: ${e.message}")
        }
    }
    // === 核心方法: 获取最新一帧截图 ===
    fun getLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        // acquireLatestImage 获取最新的一帧，可能会抛出异常或返回 null
        val image = reader.acquireLatestImage() ?: return null

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // 创建 Bitmap
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 裁剪掉因为 rowStride 对齐可能产生的多余右边距
            return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            // !!! 极其重要: 必须关闭 image，否则 ImageReader 会卡死
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        instance = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("screen_capture", "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, "screen_capture")
            .setContentTitle("AutoGLM 正在运行")
            .setContentText("正在录制屏幕以辅助操作...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}