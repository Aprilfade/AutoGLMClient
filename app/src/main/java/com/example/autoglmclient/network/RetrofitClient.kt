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
    // [修改点 1] 智谱 API 的路径通常是 chat/completions (BaseUrl 包含了 /paas/v4/)
    @POST("chat/completions")
    suspend fun chatWithAutoGLM(@Body request: OpenAiRequest): Response<OpenAiResponse>
}
object RetrofitClient {
    // [修改点 2] 修改为智谱大模型的 Base URL，务必以 "/" 结尾
    private const val BASE_URL = "https://open.bigmodel.cn/api/paas/v4/"

    // [修改点 3] 填入你的智谱 API Key
    // 请将 "your-bigmodel-api-key" 替换为你真实的 Key
    private const val API_KEY = "f7273b78b9b14f91bc4ea757afa5bda6.BDJv2RiDKT768747"
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