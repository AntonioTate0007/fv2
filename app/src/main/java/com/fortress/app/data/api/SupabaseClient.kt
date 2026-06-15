package com.fortress.app.data.api

import com.fortress.app.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/** Retrofit client for Supabase PostgREST, authenticated with the publishable key. */
object SupabaseClient {

    val configured: Boolean = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /** Every request carries the apikey + bearer (anon role) headers PostgREST expects. */
    private val authInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .addHeader("apikey", BuildConfig.SUPABASE_KEY)
            .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_KEY}")
            .addHeader("Content-Type", "application/json")
            .build()
        chain.proceed(req)
    }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                            else HttpLoggingInterceptor.Level.NONE
                }
            )
            .build()
    }

    val service: SupabaseService by lazy {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/"
        Retrofit.Builder()
            .baseUrl(base)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SupabaseService::class.java)
    }
}
