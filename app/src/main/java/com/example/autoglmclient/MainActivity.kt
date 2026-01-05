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

        // [修改] 设置默认任务
        etGoal.setText("打开设置，搜索视频彩铃")

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
// === System Prompt ===
    private fun getSystemPrompt(goal: String): String {
        return """
            任务：打开设置，搜索并关闭视频彩铃

            【固定步骤】：
            1. 在桌面：do(action="Launch", app="设置")
            2. 在设置页面：找到"搜索设置项"输入框，do(action="Tap", element=[输入框坐标])
            3. 键盘弹出后：立即 do(action="Input", text="视频")，不要点击键盘字母！
            4. 出现搜索结果：do(action="Tap", element=[视频相关选项坐标])
            5. 找到彩铃开关：do(action="Tap", element=[开关坐标])
            6. 完成：finish(message="完成")

            【关键规则】：
            - 看到键盘立即用Input，禁止点击键盘字母！
            - 看到搜索框立即点击，不要犹豫！
            - 输入的是"视频"两个字，不是"彩铃"！

            指令格式：
            do(action="Launch", app="设置")
            do(action="Tap", element=[x,y])
            do(action="Input", text="视频")
            finish(message="完成")

            只输出一条指令！
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
            delay(3500) // [修改] 延长等待时间，确保回桌面动画完成并且截图更新

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

                    // 2. [关键修改] 每次都是全新独立请求，不使用对话历史
                    // 构建单次请求消息
                    val messages = listOf(
                        Message(
                            role = "user",
                            content = listOf(
                                Content(type = "text", text = getSystemPrompt(goal)),
                                Content(type = "text", text = "当前截图第${stepCount}步，只输出一条指令："),
                                Content(type = "image_url", image_url = ImageUrl("data:image/jpeg;base64,$base64Image"))
                            )
                        )
                    )

                    withContext(Dispatchers.Main) { appendLog("🔄 第 $stepCount 步: 思考中...") }

                    // 3. 发送单次请求给大模型
                    val requestData = OpenAiRequest(messages = messages)
                    val response = RetrofitClient.api.chatWithAutoGLM(requestData)

                    if (response.isSuccessful && response.body() != null) {
                        val choices = response.body()!!.choices
                        if (choices.isNotEmpty()) {
                            val contentStr = choices.first().message.content

                            // 4. 解析指令
                            val command = parseCommandFromText(contentStr)

                            withContext(Dispatchers.Main) {
                                // [新增] 打印模型原始回复（前50字符）
                                appendLog("📝 模型回复: ${contentStr.take(50)}...")

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

    // [关键修改 2] 解析 Launch 和 Input 指令（增强版，支持从长文本中提取）
    private fun parseCommandFromText(text: String): AgentCommand? {
        try {
            // [新增] 首先尝试查找最后一个 do(...) 或 finish(...) 指令
            val doPattern = Pattern.compile("do\\s*\\(.*?\\)", Pattern.DOTALL)
            val doMatcher = doPattern.matcher(text)

            var lastDoCommand: String? = null
            while (doMatcher.find()) {
                lastDoCommand = doMatcher.group()
            }

            // 如果找到了 do(...) 指令，解析它
            val textToParse = lastDoCommand ?: text

            // 匹配 action="..."
            val actionPattern = Pattern.compile("action\\s*=\\s*\"([^\"]+)\"")
            val actionMatcher = actionPattern.matcher(textToParse)

            if (actionMatcher.find()) {
                val action = actionMatcher.group(1) ?: return null
                val params = mutableListOf<Int>()
                var appName: String? = null
                var inputText: String? = null

                // 匹配坐标 [123, 456]
                val coordPattern = Pattern.compile("\\[(\\d+)\\s*,\\s*(\\d+)\\]")
                val coordMatcher = coordPattern.matcher(textToParse)
                while (coordMatcher.find()) {
                    params.add(coordMatcher.group(1).toInt())
                    params.add(coordMatcher.group(2).toInt())
                }

                // 匹配 App 名称
                val appPattern = Pattern.compile("app\\s*=\\s*\"([^\"]+)\"")
                val appMatcher = appPattern.matcher(textToParse)
                if (appMatcher.find()) {
                    appName = appMatcher.group(1)
                }

                // [新增] 匹配输入文本 text="..."
                val textPattern = Pattern.compile("text\\s*=\\s*\"([^\"]+)\"")
                val textMatcher = textPattern.matcher(textToParse)
                if (textMatcher.find()) {
                    inputText = textMatcher.group(1)
                }

                return AgentCommand(thought = text, action = action, params = params, appName = appName, text = inputText)
            } else if (text.contains("finish", ignoreCase = true)) {
                return AgentCommand(thought = text, action = "finish", params = emptyList())
            }
            return null
        } catch (e: Exception) {
            Log.e("Parser", "解析错误", e)
            return null
        }
    }

    // [关键修改 3] 执行 Launch 和 Input 操作
    private fun executeAIAction(command: AgentCommand) {
        val service = AutoGLMService.instance ?: return
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        try {
            when (command.action.lowercase()) {
                "launch" -> {
                    val appName = command.appName
                    if (!appName.isNullOrBlank()) {
                        launchAppByName(appName)
                    }
                }
                // [新增] 处理 Input 指令
                "input" -> {
                    val text = command.text
                    if (!text.isNullOrBlank()) {
                        service.performInput(text)
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
                withContext(Dispatchers.Main) {
                    appendLog("🔍 正在查找应用: $appName")
                }

                // =============================================================
                // [新增修复逻辑] 特殊处理“设置”和“系统设置”
                // 日志显示模型喜欢说 "系统设置"，所以这里必须包含它
                // =============================================================
                val isSettings = appName == "设置" ||
                        appName == "系统设置" ||
                        appName.equals("Settings", ignoreCase = true)

                if (isSettings) {
                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    withContext(Dispatchers.Main) {
                        appendLog("🚀 已直接启动系统设置")
                    }
                    return@launch
                }
                // =============================================================

                val pm = context.packageManager
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
                        // 修改：忽略大小写，且防止 label 为空
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
                        context.startActivity(intent)

                        withContext(Dispatchers.Main) {
                            appendLog("🚀 已发送启动指令: $appName")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            appendLog("❌ 无法获取启动 Intent: $targetPackage")
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