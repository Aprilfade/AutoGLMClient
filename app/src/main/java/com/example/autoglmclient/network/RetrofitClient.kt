package com.example.autoglmclient.network

import android.util.Log
import com.example.autoglmclient.data.OpenAiRequest
import com.example.autoglmclient.data.OpenAiResponse
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface AutoGLMApi {
    // 智谱/OpenAI 的标准聊天接口路径
    @POST("paas/v4/chat/completions")
    suspend fun chatWithAutoGLM(@Body request: OpenAiRequest): Response<OpenAiResponse>
}

object RetrofitClient {
    // 智谱 AI 的 API 地址
    private const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

    // !!! 请在这里填入你的 API Key !!!
    // TODO: 记得去 https://open.bigmodel.cn/usercenter/apikeys 获取并替换下面这个字符串
    private const val API_KEY = "f7273b78b9b14f91bc4ea757afa5bda6.BDJv2RiDKT768747"

    private val client: OkHttpClient by lazy {
        // 创建日志拦截器
        val logging = HttpLoggingInterceptor { message ->
            Log.d("API_LOG", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY // 打印具体的 Body 内容
        }

        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging) // 添加日志拦截器
            .addInterceptor { chain ->
                // 运行时检查 API KEY
                if (API_KEY.contains("粘贴在这里")) {
                    throw IllegalArgumentException("请先在 RetrofitClient.kt 中配置正确的 API Key！")
                }

                val request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $API_KEY")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    val api: AutoGLMApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AutoGLMApi::class.java)
    }
}