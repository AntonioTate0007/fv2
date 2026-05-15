package com.pumpfinder.app.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pumpfinder.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface PumpApi {
    @GET("api/scan")
    suspend fun scan(): ScanResponse

    @POST("api/notifications/register")
    suspend fun registerToken(@Body body: FcmTokenRequest): TokenRegisterResponse

    @GET("api/notifications/config")
    suspend fun alertConfig(): AlertConfig
}

object PumpApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                            else HttpLoggingInterceptor.Level.NONE
                }
            )
            .build()
    }

    val service: PumpApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.BACKEND_URL)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PumpApi::class.java)
    }
}
