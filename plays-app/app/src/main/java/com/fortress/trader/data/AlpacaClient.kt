package com.fortress.trader.data

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import kotlinx.serialization.json.Json

interface AlpacaApi {
    @GET("/v2/account")
    suspend fun getAccount(): AlpacaAccount

    @GET("/v2/positions")
    suspend fun getPositions(): List<AlpacaPosition>
}

/**
 * Builds an [AlpacaApi] authenticated with the user's key/secret. Calls go straight
 * from the device to Alpaca's REST API — no backend in between.
 */
object AlpacaClient {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun create(keyId: String, secret: String, paper: Boolean): AlpacaApi {
        val baseUrl = if (paper) {
            "https://paper-api.alpaca.markets"
        } else {
            "https://api.alpaca.markets"
        }

        val auth = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("APCA-API-KEY-ID", keyId)
                .addHeader("APCA-API-SECRET-KEY", secret)
                .addHeader("Accept", "application/json")
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AlpacaApi::class.java)
    }
}
