package com.fortress.app.data.api

import com.fortress.app.data.model.AccountSnapshot
import com.fortress.app.data.model.ActivityItem
import com.fortress.app.data.model.AutopilotSettings
import com.fortress.app.data.model.FollowRequest
import com.fortress.app.data.model.FollowState
import com.fortress.app.data.model.Portfolio
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Talks to the Autopilot backend (FastAPI). The backend serves the portfolio
 * catalog + follow/settings state; the autopilot engine writes the live account
 * snapshot and the trade activity log that Home and Activity read back.
 */
interface FortressApiService {

    @GET("v1/portfolios")
    suspend fun portfolios(): List<Portfolio>

    @GET("v1/portfolios/{id}")
    suspend fun portfolio(@Path("id") id: String): Portfolio

    @POST("v1/portfolios/{id}/refresh")
    suspend fun refreshPortfolio(@Path("id") id: String): Portfolio

    @GET("v1/account")
    suspend fun account(): AccountSnapshot

    @GET("v1/activity")
    suspend fun activity(@Query("limit") limit: Int = 50): List<ActivityItem>

    @GET("v1/settings")
    suspend fun settings(): AutopilotSettings

    @POST("v1/settings")
    suspend fun updateSettings(@Body settings: AutopilotSettings): AutopilotSettings

    @GET("v1/follow")
    suspend fun follows(): FollowState

    @POST("v1/follow")
    suspend fun follow(@Body request: FollowRequest): FollowState

    @POST("v1/notifications/register")
    suspend fun registerFcmToken(@Body token: FcmTokenRequest)
}

@kotlinx.serialization.Serializable
data class FcmTokenRequest(val token: String)
