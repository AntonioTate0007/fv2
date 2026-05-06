package com.fortress.app.data.api

import com.fortress.app.data.model.ActivePosition
import com.fortress.app.data.model.CloseRequest
import com.fortress.app.data.model.CloseResponse
import com.fortress.app.data.model.DeployRequest
import com.fortress.app.data.model.DeployResponse
import com.fortress.app.data.model.RiskOfficerRequest
import com.fortress.app.data.model.RiskOfficerResponse
import com.fortress.app.data.model.ScannedTrade
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Talks to the cloud-hosted Python backend (auto_wheel_bot.py wrapped in a web service).
 * Stub interface — implement the routes server-side to match.
 */
interface FortressApiService {

    @GET("v1/radar/scan")
    suspend fun scan(@Query("capital") capitalDeployment: Int): List<ScannedTrade>

    @POST("v1/radar/deploy")
    suspend fun deploy(@Body request: DeployRequest): DeployResponse

    @GET("v1/armory/positions")
    suspend fun positions(): List<ActivePosition>

    @POST("v1/armory/close")
    suspend fun closePosition(@Body request: CloseRequest): CloseResponse

    @POST("v1/officer/ask")
    suspend fun askRiskOfficer(@Body request: RiskOfficerRequest): RiskOfficerResponse

    @POST("v1/notifications/register")
    suspend fun registerFcmToken(@Body token: FcmTokenRequest)
}

@kotlinx.serialization.Serializable
data class FcmTokenRequest(val token: String)
