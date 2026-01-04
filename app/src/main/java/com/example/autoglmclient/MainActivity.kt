package com.example.autoglmclient

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.autoglmclient.data.AgentCommand
import com.example.autoglmclient.data.Content
import com.example.autoglmclient.data.ImageUrl
import com.example.autoglmclient.data.Message
import com.example.autoglmclient.data.OpenAiRequest
import com.example.autoglmclient.network.RetrofitClient
import com.example.autoglmclient.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isTaskRunning = false

    // 权限申请回调
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCaptureService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "权限被拒绝，无法运行", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            if (!checkAndOpenAccessibility()) return@setOnClickListener
            requestScreenCapture()
        }

        val btnStart = findViewById<Button>(R.id.btn_start_auto)
        val btnStop = findViewById<Button>(R.id.btn_stop_auto)
        val etGoal = findViewById<EditText>(R.id.et_goal)

        btnStart.setOnClickListener {
            val goal = etGoal.text.toString()
            if (goal.isBlank()) {
                Toast.makeText(this, "请输入任务目标", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startAutoLoop(goal)
        }

        btnStop.setOnClickListener { stopAutoLoop() }
    }

    // === System Prompt ===
    private fun getSystemPrompt(goal: String): String {
        return """
            你是一个 Android 手机自动化助手。
            当前用户的任务目标是：【 $goal 】
            
            请根据屏幕截图，输出下一步要执行的操作。
            
            输出格式说明（请严格遵守）：
            1. 打开应用: do(action="Launch", app="应用名称")
            2. 点击操作: do(action="Tap", element=[x,y])
            3. 滑动操作: do(action="Swipe", start=[x1,y1], end=[x2,y2])
            4. 返回操作: do(action="Back")
            5. 回主桌面: do(action="Home")
            6. 任务完成: finish(message="完成")
            
            注意：
            - 如果目标应用未打开，请优先使用 Launch 指令直接打开它。
            - 坐标 (x,y) 请使用 0-1000 的相对坐标系。
        """.trimIndent()
    }

    private fun startAutoLoop(goal: String) {
        if (AutoGLMService.instance == null) {
            appendLog("❌ 无障碍服务未启动！")
            checkAndOpenAccessibility()
            return
        }
        if (ScreenCaptureService.instance == null) {
            appendLog("❌ 录屏权限未开启，请先点击按钮 1")
            return
        }

        isTaskRunning = true
        updateUiState(true)
        appendLog("🚀 任务启动: $goal")

        lifecycleScope.launch(Dispatchers.IO) {
            // [新增] 预先检查一次包列表，确保权限弹窗在App界面内处理完
            packageManager.getInstalledPackages(0)
            // [关键修改 1] 启动后先回桌面，防止模型看着自己的界面发呆
            withContext(Dispatchers.Main) { appendLog("🏠 正在返回桌面，准备开始...") }
            AutoGLMService.instance?.performGlobalActionStr("home")
            delay(2000) // 多给点时间让动画结束

            var stepCount = 0
            val maxSteps = 20

            while (isTaskRunning && stepCount < maxSteps) {
                stepCount++
                try {
                    // 1. 获取截图
                    val captureService = ScreenCaptureService.instance
                    val bitmap = captureService?.getLatestBitmap()
                    if (bitmap == null) {
                        appendLog("⚠️ 截图失败，重试中...")
                        delay(1000)
                        continue
                    }
                    val base64Image = ImageUtils.bitmapToBase64(bitmap)

                    // 2. 构建请求
                    val currentMessages = listOf(
                        Message(
                            role = "user",
                            content = listOf(
                                Content(type = "text", text = getSystemPrompt(goal)),
                                Content(type = "image_url", image_url = ImageUrl("data:image/jpeg;base64,$base64Image"))
                            )
                        )
                    )

                    withContext(Dispatchers.Main) { appendLog("🔄 第 $stepCount 步: 思考中...") }

                    // 3. 发送给大模型
                    val requestData = OpenAiRequest(messages = currentMessages)
                    val response = RetrofitClient.api.chatWithAutoGLM(requestData)

                    if (response.isSuccessful && response.body() != null) {
                        val choices = response.body()!!.choices
                        if (choices.isNotEmpty()) {
                            val contentStr = choices.first().message.content

                            // 4. 解析指令
                            val command = parseCommandFromText(contentStr)

                            withContext(Dispatchers.Main) {
                                // 打印简略日志
                                if (command != null) {
                                    val logMsg = if (command.action == "Launch")
                                        "👉 操作: 打开 [${command.appName}]"
                                    else
                                        "👉 操作: ${command.action} ${command.params}"
                                    appendLog(logMsg)
                                } else {
                                    appendLog("⚠️ 无法解析指令，原文: ${contentStr.take(20)}...")
                                }
                            }

                            // 5. 执行指令
                            if (command != null) {
                                if (command.action.equals("finish", ignoreCase = true)) {
                                    withContext(Dispatchers.Main) { appendLog("✅ 任务完成！") }
                                    break
                                }
                                executeAIAction(command)
                            }

                            // 操作后等待界面响应
                            delay(4000)
                        }
                    } else {
                        withContext(Dispatchers.Main) { appendLog("❌ 网络请求失败: ${response.code()}") }
                        delay(3000)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { appendLog("❌ 错误: ${e.message}") }
                    delay(3000)
                }
            }

            withContext(Dispatchers.Main) {
                stopAutoLoop()
                appendLog("🏁 流程结束")
            }
        }
    }

    // [关键修改 2] 解析 Launch 指令
    private fun parseCommandFromText(text: String): AgentCommand? {
        try {
            // 匹配 action="..."
            val actionPattern = Pattern.compile("action=\"([^\"]+)\"")
            val actionMatcher = actionPattern.matcher(text)

            if (actionMatcher.find()) {
                val action = actionMatcher.group(1) ?: return null
                val params = mutableListOf<Int>()
                var appName: String? = null

                // 匹配坐标 [123, 456]
                val coordPattern = Pattern.compile("\\[(\\d+),\\s*(\\d+)\\]")
                val coordMatcher = coordPattern.matcher(text)
                while (coordMatcher.find()) {
                    params.add(coordMatcher.group(1).toInt())
                    params.add(coordMatcher.group(2).toInt())
                }

                // [新增] 匹配 App 名称 app="QQ音乐"
                val appPattern = Pattern.compile("app=\"([^\"]+)\"")
                val appMatcher = appPattern.matcher(text)
                if (appMatcher.find()) {
                    appName = appMatcher.group(1)
                }

                return AgentCommand(thought = text, action = action, params = params, appName = appName)
            } else if (text.contains("finish")) {
                return AgentCommand(thought = text, action = "finish", params = emptyList())
            }
            return null
        } catch (e: Exception) {
            Log.e("Parser", "解析错误", e)
            return null
        }
    }

    // [关键修改 3] 执行 Launch 操作
    private fun executeAIAction(command: AgentCommand) {
        val service = AutoGLMService.instance ?: return
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        try {
            when (command.action.lowercase()) {
                "launch" -> {
                    // 尝试根据名字打开 App
                    val appName = command.appName
                    if (!appName.isNullOrBlank()) {
                        launchAppByName(appName)
                    }
                }
                "click", "tap" -> {
                    if (command.params.size >= 2) {
                        val x = (command.params[0] / 1000.0 * metrics.widthPixels).toInt()
                        val y = (command.params[1] / 1000.0 * metrics.heightPixels).toInt()
                        service.performClick(x, y)
                    }
                }
                "swipe", "scroll" -> {
                    if (command.params.size >= 4) {
                        val startX = (command.params[0] / 1000.0 * metrics.widthPixels).toInt()
                        val startY = (command.params[1] / 1000.0 * metrics.heightPixels).toInt()
                        val endX = (command.params[2] / 1000.0 * metrics.widthPixels).toInt()
                        val endY = (command.params[3] / 1000.0 * metrics.heightPixels).toInt()
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
    // 辅助方法：通过应用名称查找包名并启动
    private fun launchAppByName(appName: String) {
        // [关键修改 1] 优先使用 AccessibilityService 的 Context，因为它有后台启动的特权
        val context = AutoGLMService.instance ?: this

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager

                withContext(Dispatchers.Main) {
                    appendLog("🔍 正在查找应用: $appName")
                }

                // 获取应用列表
                val packages = pm.getInstalledPackages(0)

                var targetPackage: String? = null

                // 1. 先尝试精确匹配
                for (packageInfo in packages) {
                    val label = packageInfo.applicationInfo.loadLabel(pm).toString()
                    if (label == appName) {
                        targetPackage = packageInfo.packageName
                        break
                    }
                }

                // 2. 如果没找到，尝试包含匹配
                if (targetPackage == null) {
                    for (packageInfo in packages) {
                        val label = packageInfo.applicationInfo.loadLabel(pm).toString()
                        if (label.contains(appName, ignoreCase = true)) {
                            targetPackage = packageInfo.packageName
                            break
                        }
                    }
                }

                if (targetPackage != null) {
                    val intent = pm.getLaunchIntentForPackage(targetPackage)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // [关键修改 2] 使用 Service Context 启动
                        context.startActivity(intent)

                        withContext(Dispatchers.Main) {
                            appendLog("🚀 已发送启动指令: $appName")
                            // 启动后稍微多等一会儿，确保应用加载出来
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            appendLog("❌ 无法获取启动 Intent")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        appendLog("❌ 未找到应用: $appName")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { appendLog("❌ 启动出错: ${e.message}") }
            }
        }
    }
    // ... 保持 stopAutoLoop, updateUiState, checkAndOpenAccessibility 等方法不变 ...

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
            if (tvLog != null) {
                val currentText = tvLog.text.toString()
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                tvLog.text = "[$time] $text\n$currentText"
            }
        }
    }

    private fun checkAndOpenAccessibility(): Boolean {
        if (AutoGLMService.instance == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            return false
        }
        return true
    }

    private fun requestScreenCapture() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

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
        Toast.makeText(this, "服务已就绪", Toast.LENGTH_SHORT).show()
    }
}