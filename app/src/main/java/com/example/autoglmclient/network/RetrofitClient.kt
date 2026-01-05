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
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

interface AutoGLMApi {
    // [修改点 1] 智谱 API 的路径通常是 chat/completions (BaseUrl 包含了 /paas/v4/)
    @POST("chat/completions")
    suspend fun chatWithAutoGLM(@Body request: OpenAiRequest): Response<OpenAiResponse>
}
object RetrofitClient {
    // [修改点 2] 修改为自建 vLLM 服务器的 Base URL，务必以 "/" 结尾
    private const val BASE_URL = "https://u854750-8485-a66a2bde.westb.seetacloud.com:8443/v1/"

    // [修改点 3] vLLM 服务器不需要 API Key，设为空字符串
    private const val API_KEY = ""

    // 创建不验证证书的 TrustManager（用于自签名证书）
    private fun getUnsafeTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    // 创建不验证证书的 SSLContext
    private fun getUnsafeSSLContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(getUnsafeTrustManager())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }
    private val client: OkHttpClient by lazy {
        // 创建日志拦截器
        val logging = HttpLoggingInterceptor { message ->
            Log.d("API_LOG", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        // 获取SSL配置
        val sslContext = getUnsafeSSLContext()
        val trustManager = getUnsafeTrustManager()

        OkHttpClient.Builder()
            // ⚠️ 修改点 4：私有服务器推理可能较慢（特别是9B模型），建议适当延长超时时间
            .connectTimeout(240, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            // 配置SSL以支持自签名证书
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/json")

                // 只有当 API_KEY 不为空时才添加 Authorization header
                if (API_KEY.isNotEmpty()) {
                    requestBuilder.addHeader("Authorization", "Bearer $API_KEY")
                }

                chain.proceed(requestBuilder.build())
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