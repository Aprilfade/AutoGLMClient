package com.example.autoglmclient

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 创建一个简单的按钮（你需要先在 activity_main.xml 里加一个 ID 为 btn_open_settings 的按钮）
        // 为了演示方便，我们假设布局里有个按钮
        /*
        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            // 跳转到无障碍设置页面
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // 模拟测试：延迟5秒后点击屏幕 (需要先手动开启无障碍服务)
        findViewById<Button>(R.id.btn_test_tap).setOnClickListener {
             Thread {
                 Thread.sleep(5000)
                 // 必须在主线程外或通过 Handler 调用，这里仅作演示
                 AutoGLMService.instance?.performTap(500f, 1000f)
             }.start()
        }
        */
    }
}