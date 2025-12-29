package com.example.autoglmclient

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.autoglmclient.network.RetrofitClient
import com.example.autoglmclient.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    // 提取 Prompt 为常量
    private val SYSTEM_PROMPT = """
        你是一个 Android 手机自动化助手。
        请根据屏幕截图，输出 JSON 格式的操作指令。
        
        输出格式 (必须是纯 JSON，不要用 markdown):
        {
          "thought": "简述要在屏幕什么位置做什么",
          "action": "click", 
          "params": [x, y]
        }
        
        支持的 action:
        1. "click": 点击。params: [x, y] (绝对坐标)
        2. "swipe": 滑动。params: [start_x, start_y, end_x, end_y]
        3. "back": 返回键。params: []
        4. "home": 回桌面。params: []
    """.trimIndent()

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

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            requestScreenCapture()
        }

        findViewById<Button>(R.id.btn_test_action)?.setOnClickListener {
            performSingleStep()
        }
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
        Toast.makeText(this, "AutoGLM 眼睛已准备就绪", Toast.LENGTH_SHORT).show()
    }

    private fun performSingleStep() {
        val captureService = ScreenCaptureService.instance
        if (captureService == null) {
            Toast.makeText(this, "请先点击开启录屏服务", Toast.LENGTH_SHORT).show()
            return
        }
        if (AutoGLMService.instance == null) {
            Toast.makeText(this, "无障碍服务未启动！请去设置开启", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "正在思考...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = captureService.getLatestBitmap()
                if (bitmap == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "截图获取失败", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                // 此时 ImageUtils 已经包含了缩放逻辑
                val base64Image = ImageUtils.bitmapToBase64(bitmap)

                val requestData = com.example.autoglmclient.data.OpenAiRequest(
                    messages = listOf(
                        com.example.autoglmclient.data.Message(
                            role = "user",
                            content = listOf(
                                com.example.autoglmclient.data.Content(type = "text", text = SYSTEM_PROMPT),
                                com.example.autoglmclient.data.Content(
                                    type = "image_url",
                                    image_url = com.example.autoglmclient.data.ImageUrl(url = "data:image/jpeg;base64,$base64Image")
                                )
                            )
                        )
                    )
                )

                val response = RetrofitClient.api.chatWithAutoGLM(requestData)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        if (!body.choices.isNullOrEmpty()) {
                            val contentStr = body.choices[0].message.content
                            Log.d("AutoGLM", "AI原始回复: $contentStr")

                            try {
                                // 使用提取出来的 cleanJson 方法
                                val cleanJsonStr = cleanJson(contentStr)

                                val command = com.google.gson.Gson().fromJson(cleanJsonStr, com.example.autoglmclient.data.AgentCommand::class.java)

                                Toast.makeText(this@MainActivity, "AI: ${command.thought}", Toast.LENGTH_SHORT).show()
                                executeAIAction(command.action, command.params)

                            } catch (e: Exception) {
                                Log.e("AutoGLM", "解析JSON失败", e)
                                Toast.makeText(this@MainActivity, "AI返回格式错误: $contentStr", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        val errorMsg = response.errorBody()?.string() ?: "未知错误"
                        Log.e("AutoGLM", "请求失败: $errorMsg")
                        Toast.makeText(this@MainActivity, "API请求失败: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IllegalArgumentException) {
                // 捕获 API Key 未配置的异常
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "错误: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

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

    // 之前未使用的方法，现在已启用
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
}