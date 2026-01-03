package com.example.autoglmclient

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Button
import android.widget.EditText // 修复 Unresolved reference 'EditText'
import android.widget.TextView // 修复 Unresolved reference 'TextView'
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.autoglmclient.data.AgentCommand
import com.example.autoglmclient.data.Content // 修复 Unresolved reference 'Content'
import com.example.autoglmclient.data.ImageUrl // 修复 Unresolved reference 'ImageUrl'
import com.example.autoglmclient.data.Message // 修复 Unresolved reference 'Message'
import com.example.autoglmclient.data.OpenAiRequest
import com.example.autoglmclient.network.RetrofitClient
import com.example.autoglmclient.utils.ImageUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay // 修复 Unresolved reference 'delay'
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // 状态控制
    private var isTaskRunning = false
    private val chatHistory = mutableListOf<Message>()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "权限被拒绝，无法使用眼睛", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 按钮 1: 开启权限（逻辑升级）
        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            // 先检查无障碍服务
            if (!checkAndOpenAccessibility()) {
                return@setOnClickListener // 如果没开，先去开无障碍，不执行后面
            }

            // 如果无障碍已经开了，再请求录屏权限
            requestScreenCapture()
        }
        // 获取 UI 控件
        val btnStart = findViewById<Button>(R.id.btn_start_auto)
        val btnStop = findViewById<Button>(R.id.btn_stop_auto)
        val etGoal = findViewById<EditText>(R.id.et_goal)

        // 按钮 2: 开始自动任务
        btnStart.setOnClickListener {
            val goal = etGoal.text.toString()
            if (goal.isBlank()) {
                Toast.makeText(this, "请输入任务目标", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startAutoLoop(goal)
        }

        // 按钮 3: 停止
        btnStop.setOnClickListener {
            stopAutoLoop()
        }
    }

    // 动态生成 System Prompt
    private fun getSystemPrompt(goal: String): String {
        return """
            你是一个 Android 手机自动化助手。
            当前用户的任务目标是：【 $goal 】
            
            请根据屏幕截图，一步步操作来实现该目标。
            如果任务已完成，请在 thought 中明确说明，并输出 action: "finish"。
            如果界面没有变化或操作失败，请尝试其他方式。
            
            输出格式 (JSON Only):
            {
              "thought": "简述当前状态和下一步计划",
              "action": "click" | "swipe" | "back" | "home" | "finish", 
              "params": [x, y] 或 [x1, y1, x2, y2]
            }
        """.trimIndent()
    }

    // === 核心循环逻辑 ===
    private fun startAutoLoop(goal: String) {
        // 检查无障碍服务
        if (AutoGLMService.instance == null) {
            appendLog("❌ 无障碍服务未启动！正在跳转设置...")
            checkAndOpenAccessibility() // 自动跳转
            return
        }
        // 检查录屏服务
        if (ScreenCaptureService.instance == null) {
            appendLog("❌ 录屏权限未开启（或服务已崩溃），请点击按钮 1 重试")
            return
        }

        isTaskRunning = true
        updateUiState(true)
        chatHistory.clear()
        appendLog("🚀 任务开始: $goal")

        lifecycleScope.launch(Dispatchers.IO) {
            var stepCount = 0
            val maxSteps = 20 // 防止死循环，最大执行20步

            while (isTaskRunning && stepCount < maxSteps) {
                stepCount++
                try {
                    // A. 获取截图
                    val captureService = ScreenCaptureService.instance
                    val bitmap = captureService?.getLatestBitmap()
                    if (bitmap == null) {
                        appendLog("⚠️ 截图获取失败，重试中...")
                        delay(1000)
                        continue
                    }
                    val base64Image = ImageUtils.bitmapToBase64(bitmap)

                    // B. 构建消息
                    val currentMessages = listOf(
                        Message(
                            role = "user",
                            content = listOf(
                                Content(type = "text", text = getSystemPrompt(goal)),
                                Content(type = "image_url", image_url = ImageUrl("data:image/jpeg;base64,$base64Image"))
                            )
                        )
                    )

                    withContext(Dispatchers.Main) { appendLog("🔄 第 $stepCount 步: 正在思考...") }

                    // C. 请求 API
                    val requestData = OpenAiRequest(
                        messages = currentMessages,
                        temperature = 0.1
                    )

                    val response = RetrofitClient.api.chatWithAutoGLM(requestData)

                    if (response.isSuccessful && response.body() != null) {
                        val choices = response.body()!!.choices
                        if (choices.isNotEmpty()) {
                            val contentStr = choices.first().message.content
                            val cleanJsonStr = cleanJson(contentStr)

                            try {
                                val command = Gson().fromJson(cleanJsonStr, AgentCommand::class.java)

                                withContext(Dispatchers.Main) {
                                    appendLog("💡 想法: ${command.thought}")
                                    appendLog("👉 操作: ${command.action} ${command.params}")
                                }

                                if (command.action == "finish") {
                                    withContext(Dispatchers.Main) { appendLog("✅ 任务完成！") }
                                    break
                                }

                                executeAIAction(command.action, command.params)

                                // D. 等待操作生效 (给界面一点反应时间)
                                delay(3000)
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { appendLog("⚠️ JSON解析错误: ${e.message}") }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) { appendLog("❌ API 请求失败: ${response.code()}") }
                        delay(2000)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { appendLog("❌ 出错: ${e.message}") }
                    delay(2000)
                }
            }

            withContext(Dispatchers.Main) {
                stopAutoLoop()
                appendLog("🏁 自动循环结束")
            }
        }
    }

    private fun stopAutoLoop() {
        isTaskRunning = false
        updateUiState(false)
    }

    private fun updateUiState(running: Boolean) {
        runOnUiThread {
            findViewById<Button>(R.id.btn_start_auto).isEnabled = !running
            findViewById<Button>(R.id.btn_stop_auto).isEnabled = running
            findViewById<EditText>(R.id.et_goal).isEnabled = !running
        }
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            val tvLog = findViewById<TextView>(R.id.tv_log)
            // 检查 null 以防 XML 尚未更新导致找不到 ID
            if (tvLog != null) {
                val currentText = tvLog.text.toString()
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                tvLog.text = "[$time] $text\n$currentText"
            }
        }
    }

    private fun requestScreenCapture() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    @Suppress("DEPRECATION") // ✅ 添加这一行
    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
            putExtra("WIDTH", metrics.widthPixels)
            putExtra("HEIGHT", metrics.heightPixels)
            putExtra("DENSITY", metrics.densityDpi)
        }
        startForegroundService(serviceIntent)
        Toast.makeText(this, "AutoGLM 眼睛已准备就绪", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION") // ✅ 添加这一行
    private fun executeAIAction(action: String?, params: List<Int>?) {
        val service = AutoGLMService.instance ?: return
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        try {
            when (action?.lowercase()) {
                "click", "tap" -> {
                    if (params != null && params.size >= 2) {
                        val x = (params[0] / 1000.0 * screenWidth).toInt()
                        val y = (params[1] / 1000.0 * screenHeight).toInt()
                        service.performClick(x, y)
                    }
                }
                "swipe", "scroll" -> {
                    if (params != null && params.size >= 4) {
                        val startX = (params[0] / 1000.0 * screenWidth).toInt()
                        val startY = (params[1] / 1000.0 * screenHeight).toInt()
                        val endX = (params[2] / 1000.0 * screenWidth).toInt()
                        val endY = (params[3] / 1000.0 * screenHeight).toInt()
                        service.performSwipe(startX, startY, endX, endY)
                    }
                }
                "back" -> service.performGlobalActionStr("back")
                "home" -> service.performGlobalActionStr("home")
            }
        } catch (e: Exception) {
            Log.e("AutoGLM", "动作执行出错: ${e.message}")
        }
    }

    private fun cleanJson(input: String): String {
        var result = input.trim()
        if (result.startsWith("```json")) {
            result = result.substring(7)
        }
        if (result.startsWith("```")) {
            result = result.substring(3)
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.length - 3)
        }
        return result.trim()
    }

    // 检查无障碍服务是否开启，未开启则跳转设置
    private fun checkAndOpenAccessibility(): Boolean {
        if (AutoGLMService.instance == null) {
            Toast.makeText(this, "请在设置中开启 [AutoGLMClient] 无障碍服务", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            return false
        }
        return true
    }
}