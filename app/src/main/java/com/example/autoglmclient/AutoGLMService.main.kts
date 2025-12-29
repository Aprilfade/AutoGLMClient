package com.example.autoglmclient

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AutoGLMService : AccessibilityService() {

    // 当服务连接成功时调用
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutoGLM", "无障碍服务已连接！")
        // 这里可以初始化一些网络连接
    }

    // 必须实现的方法，这里可以留空，除非你想监听某些事件
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // 必须实现的方法
    override fun onInterrupt() {}

    // === 核心功能：模拟点击 ===
    fun performTap(x: Float, y: Float) {
        Log.d("AutoGLM", "正在点击坐标: $x, $y")

        val path = Path()
        path.moveTo(x, y)

        val builder = GestureDescription.Builder()
        val gesture = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d("AutoGLM", "点击执行完毕")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e("AutoGLM", "点击被取消")
            }
        }, null)
    }

    // 全局静态实例，方便在其他地方调用
    companion object {
        var instance: AutoGLMService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}