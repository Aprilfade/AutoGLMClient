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
    // ⚠️ 修改点 1：私有部署通常兼容 OpenAI 格式，路径改为 "v1/chat/completions"
    // 原来的 "paas/v4/..." 是智谱云端专用的
    @POST("v1/chat/completions")
    suspend fun chatWithAutoGLM(@Body request: OpenAiRequest): Response<OpenAiResponse>
}

object RetrofitClient {
    // ⚠️ 修改点 2：填入你的 AutoDL 公网地址
    // 注意：
    // 1. 必须以 "/" 结尾
    // 2. 你的地址带端口 8443，不要漏掉
    private const val BASE_URL = "https://u854750-89b3-f556f2ee.westc.gpuhub.com:8443/"

    // ⚠️ 修改点 3：私有服务器通常不需要特定 Key，填 "EMPTY" 即可
    // (有些服务器如果设置了 API_KEY 环境变量，则需要对应填入，默认通常为空)
    private const val API_KEY = "EMPTY"

    private val client: OkHttpClient by lazy {
        // 创建日志拦截器
        val logging = HttpLoggingInterceptor { message ->
            Log.d("API_LOG", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            // ⚠️ 修改点 4：私有服务器推理可能较慢（特别是9B模型），建议适当延长超时时间
            .connectTimeout(240, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
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
            .baseUrl(BASE_URL) //
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AutoGLMApi::class.java)
    }
}