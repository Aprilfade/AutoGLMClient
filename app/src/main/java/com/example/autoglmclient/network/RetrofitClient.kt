package com.example.autoglmclient.network

import com.example.autoglmclient.data.OpenAiRequest
import com.example.autoglmclient.data.OpenAiResponse
import okhttp3.OkHttpClient
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
    private const val BASE_URL = "https://open.bigmodel.cn/api/"

    // !!! 请在这里填入你的 API Key !!!
    // 格式通常是 "你的KEY.后缀"
    private const val API_KEY = "你的_ZHIPU_API_KEY_粘贴在这里"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS) // 图片上传可能较慢，延长时间
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $API_KEY")
                .addHeader("Content-Type", "application/json")
                .build()
            chain.proceed(request)
        }
        .build()

    val api: AutoGLMApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AutoGLMApi::class.java)
    }
}