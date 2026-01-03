// 文件位置: app/src/main/java/com/example/autoglmclient/ScreenCaptureService.kt
package com.example.autoglmclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler // [新增]
import android.os.IBinder
import android.os.Looper  // [新增]
import android.util.Log

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 缓存上一帧成功的截图
    private var lastBitmap: Bitmap? = null

    companion object {
        var instance: ScreenCaptureService? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        val notification = createNotification()

        // Android 14 (SDK 34) 必须指定 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
        val density = intent?.getIntExtra("DENSITY", 320) ?: 320

        if (resultCode != -1 || resultData == null) {
            Log.e("AutoGLM", "❌ 启动参数错误: resultCode=$resultCode")
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData, width, height, density)
        return START_STICKY
    }

    private fun startProjection(code: Int, data: Intent, w: Int, h: Int, dpi: Int) {
        try {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(code, data)

            if (mediaProjection == null) {
                Log.e("AutoGLM", "❌ MediaProjection 创建失败 (null)")
                return
            }

            // 确保宽高是偶数，防止对齐问题
            var safeWidth = if (w > 0) w else 720
            var safeHeight = if (h > 0) h else 1280
            if (safeWidth % 2 != 0) safeWidth--
            if (safeHeight % 2 != 0) safeHeight--

            val safeDpi = if (dpi > 0) dpi else 320

            Log.d("AutoGLM", "正在启动录屏: ${safeWidth}x${safeHeight} dpi=$safeDpi")

            try {
                // [修复点 1] 创建一个主线程 Handler
                val handler = Handler(Looper.getMainLooper())

                imageReader = ImageReader.newInstance(safeWidth, safeHeight, PixelFormat.RGBA_8888, 2)

                // [修复点 2] 极其重要：设置一个空的 Listener，这会强制 ImageReader 开始接收数据流
                imageReader?.setOnImageAvailableListener({ reader ->
                    // 这里可以留空，因为我们是主动 poll (acquireLatestImage)
                    // 但必须设置 Listener 才能在某些设备上激活 VirtualDisplay 的输出
                }, handler)

                val virtualDisplayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

                // [修复点 3] 将 handler 传递给 createVirtualDisplay
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "AutoGLMScreen",
                    safeWidth, safeHeight, safeDpi,
                    virtualDisplayFlags,
                    imageReader?.surface,
                    null,
                    handler // 传入 handler
                )
                Log.d("AutoGLM", "✅ 录屏服务启动成功，等待画面...")
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AutoGLM", "❌ ImageReader/VirtualDisplay 创建失败: ${e.message}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AutoGLM", "❌ 录屏服务启动严重错误: ${e.message}")
        }
    }

    fun getLatestBitmap(): Bitmap? {
        val reader = imageReader
        if (reader == null) {
            Log.e("AutoGLM", "⚠️ getLatestBitmap: ImageReader 为 null (可能服务未正确启动)")
            return null
        }

        // 获取最新的一帧
        val image = reader.acquireLatestImage()

        if (image == null) {
            if (lastBitmap != null) {
                return lastBitmap
            }
            // 只有在完全没有拿到过图片时才会打印这个 Log，避免刷屏
            Log.w("AutoGLM", "⚠️ getLatestBitmap: acquireLatestImage 返回 null 且无缓存")
            return null
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)

            lastBitmap = finalBitmap
            return finalBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AutoGLM", "❌ 图片转换 Bitmap 异常: ${e.message}")
            return lastBitmap
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null
        instance = null
        Log.d("AutoGLM", "🚫 录屏服务已销毁")
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