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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // 标记服务是否准备好
    @Volatile
    private var isReady = false

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

        // Android 10+ 前台服务类型
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } catch (e: Exception) {
                Log.e("AutoGLM", "❌ startForeground 失败: ${e.message}")
                stopSelf()
            }
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
            shutdownService()
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
                Log.e("AutoGLM", "❌ MediaProjection 创建失败")
                shutdownService()
                return
            }

            val handler = Handler(Looper.getMainLooper())

            // =======================================================================
            // 修复核心：必须在 createVirtualDisplay 之前注册 Callback
            // =======================================================================
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w("AutoGLM", "⚠️ 系统停止了录屏 (onStop)")
                    shutdownService()
                }
            }, handler)

            // 计算安全的宽高（偶数）
            var safeWidth = if (w > 0) w else 720
            var safeHeight = if (h > 0) h else 1280
            if (safeWidth % 2 != 0) safeWidth--
            if (safeHeight % 2 != 0) safeHeight--

            val safeDpi = if (dpi > 0) dpi else 320

            Log.d("AutoGLM", "正在启动录屏: ${safeWidth}x${safeHeight} dpi=$safeDpi")

            imageReader = ImageReader.newInstance(safeWidth, safeHeight, PixelFormat.RGBA_8888, 2)
            // 必须设置 Listener 即使为空，以确保 ImageReader 在某些设备上工作正常
            imageReader?.setOnImageAvailableListener({ _ -> }, handler)

            val virtualDisplayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AutoGLMScreen",
                safeWidth, safeHeight, safeDpi,
                virtualDisplayFlags,
                imageReader?.surface,
                null,
                handler
            )

            isReady = true
            Log.d("AutoGLM", "✅ 录屏服务启动成功")

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("AutoGLM", "❌ 录屏服务启动异常: ${e.message}")
            shutdownService()
        }
    }

    fun getLatestBitmap(): Bitmap? {
        if (!isReady || imageReader == null) {
            return null
        }
        val reader = imageReader ?: return null

        try {
            val image = reader.acquireLatestImage() ?: return lastBitmap
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
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e("AutoGLM", "❌ 获取截图异常: ${e.message}")
            return lastBitmap
        }
    }

    private fun shutdownService() {
        isReady = false
        stopSelf() // 这会触发 onDestroy
    }

    override fun onDestroy() {
        super.onDestroy()
        isReady = false
        try {
            virtualDisplay?.release()
            mediaProjection?.stop() // 停止录屏
            mediaProjection = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e("AutoGLM", "资源释放异常: ${e.message}")
        }
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
            .setContentTitle("AutoGLM 运行中")
            .setContentText("正在进行屏幕识别...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}