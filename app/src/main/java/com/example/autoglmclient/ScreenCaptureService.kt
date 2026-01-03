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
import android.os.IBinder
import android.util.Log

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // [新增] 缓存上一帧成功的截图，防止屏幕静止时 acquireLatestImage 返回 null 导致任务失败
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

        // 针对 Android 10+ (特别是 Android 14) 必须指定服务类型
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
            Log.e("AutoGLM", "❌ 启动参数错误: resultCode=$resultCode, data=$resultData")
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

            // [修复] 确保宽高是偶数 (对齐)，防止某些设备 ImageReader 崩溃或黑屏
            var safeWidth = if (w > 0) w else 720
            var safeHeight = if (h > 0) h else 1280
            if (safeWidth % 2 != 0) safeWidth--
            if (safeHeight % 2 != 0) safeHeight--

            val safeDpi = if (dpi > 0) dpi else 320

            Log.d("AutoGLM", "正在启动录屏: ${safeWidth}x${safeHeight} dpi=$safeDpi")

            try {
                // maxImages 设为 2，留有缓冲
                imageReader = ImageReader.newInstance(safeWidth, safeHeight, PixelFormat.RGBA_8888, 2)

                // [修复] 增加 VIRTUAL_DISPLAY_FLAG_PUBLIC 提高兼容性
                val virtualDisplayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "AutoGLMScreen",
                    safeWidth, safeHeight, safeDpi,
                    virtualDisplayFlags,
                    imageReader?.surface,
                    null, null
                )
                Log.d("AutoGLM", "✅ 录屏服务启动成功")
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("AutoGLM", "❌ ImageReader/VirtualDisplay 创建失败: ${e.message}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AutoGLM", "❌ 录屏服务启动严重错误: ${e.message}")
        }
    }

    // === 核心方法: 获取最新一帧截图 ===
    fun getLatestBitmap(): Bitmap? {
        val reader = imageReader
        if (reader == null) {
            Log.e("AutoGLM", "⚠️ 尝试获取截图但 ImageReader 为 null")
            return null
        }

        // acquireLatestImage 获取最新的一帧
        // 如果屏幕静止，可能没有新帧产生，此时返回 null 是正常的
        val image = reader.acquireLatestImage()

        if (image == null) {
            // [新增] 如果拿不到新帧，返回上一帧缓存 (解决屏幕静止时截图失败的问题)
            if (lastBitmap != null) {
                return lastBitmap
            }
            return null
        }

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
            val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)

            // [新增] 更新缓存
            lastBitmap = finalBitmap
            return finalBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AutoGLM", "❌ 图片转换 Bitmap 异常: ${e.message}")
            return lastBitmap // 异常时也尝试返回缓存
        } finally {
            // !!! 极其重要: 必须关闭 image，否则 ImageReader 会卡死
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