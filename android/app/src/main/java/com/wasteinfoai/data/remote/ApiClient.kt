package com.wasteinfoai.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Creates and configures the Retrofit API client.
 */
object ApiClient {

    // Default to Android Emulator loopback host for FastAPI dev server
    const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"

    fun create(
        sessionManager: SessionManager,
        baseUrl: String = DEFAULT_BASE_URL
    ): ApiService {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val token = sessionManager.getToken()
            val requestBuilder = original.newBuilder()

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }
            requestBuilder.header("Accept", "application/json")
            chain.proceed(requestBuilder.build())
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(ApiService::class.java)
    }
}
