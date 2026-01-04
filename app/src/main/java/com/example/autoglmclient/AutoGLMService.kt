// 文件位置: app/src/main/java/com/example/autoglmclient/AutoGLMService.kt
package com.example.autoglmclient

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoGLMService : AccessibilityService() {

    companion object {
        var instance: AutoGLMService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AutoGLM", "无障碍服务已连接！")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // === 动作 1: 点击 ===
    fun performClick(x: Int, y: Int) {
        val path = Path()
        path.moveTo(x.toFloat(), y.toFloat())

        val builder = GestureDescription.Builder()
        // 点击持续 100ms
        val gesture = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, null, null)
    }

    // === 动作 2: 滑动 ===
    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int) {
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        path.lineTo(endX.toFloat(), endY.toFloat())

        val builder = GestureDescription.Builder()
        // 滑动持续 500ms
        val gesture = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()

        dispatchGesture(gesture, null, null)
    }

    // === 动作 3: 全局按键 (返回/Home) ===
    fun performGlobalActionStr(action: String) {
        when (action.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }
    // [新增] 动作 4: 输入文本
    fun performInput(text: String) {
        val root = rootInActiveWindow ?: return

        // 1. 尝试找当前获得焦点的输入框
        var targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        // 2. 如果没找到焦点，尝试搜索所有 EditText 节点
        if (targetNode == null) {
            val editableNodes = root.findAccessibilityNodeInfosByViewId("android:id/search_src_text") // 常见搜索框ID
            if (editableNodes.isNullOrEmpty()) {
                // 广撒网找可编辑节点
                val allNodes = ArrayList<AccessibilityNodeInfo>()
                fun traverse(node: AccessibilityNodeInfo) {
                    if (node.isEditable) allNodes.add(node)
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { traverse(it) }
                    }
                }
                traverse(root)
                if (allNodes.isNotEmpty()) targetNode = allNodes[0]
            } else {
                targetNode = editableNodes[0]
            }
        }

        if (targetNode != null) {
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            targetNode.recycle()
            Log.d("AutoGLM", "已输入文本: $text")
        } else {
            Log.e("AutoGLM", "❌ 未找到可输入的输入框")
        }
    }
}