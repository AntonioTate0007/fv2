package com.fortress.app.data.api

import com.fortress.app.data.model.AccountSnapshot
import com.fortress.app.data.model.ActivityItem
import com.fortress.app.data.model.AutopilotSettings
import com.fortress.app.data.model.Portfolio
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Talks directly to Supabase's PostgREST API (`/rest/v1`). This is the app's live
 * backend — no server to deploy. The autopilot engine (the agent) writes the
 * account snapshot + activity rows; the app reads them and manages follows/settings.
 *
 * Column names in the tables are camelCase (quoted) so PostgREST JSON maps straight
 * onto the @Serializable models. List endpoints return JSON arrays.
 */
interface SupabaseService {

    @GET("portfolios")
    suspend fun portfolios(
        @Query("select") select: String = "*",
        @Query("order") order: String = "ytdReturnPct.desc"
    ): List<Portfolio>

    /** [idEq] must be a PostgREST filter, e.g. "eq.ai-flagship". */
    @GET("portfolios")
    suspend fun portfolioById(
        @Query("id") idEq: String,
        @Query("select") select: String = "*"
    ): List<Portfolio>

    @GET("account_snapshot")
    suspend fun account(@Query("select") select: String = "*"): List<AccountSnapshot>

    @GET("activity")
    suspend fun activity(
        @Query("select") select: String = "*",
        @Query("order") order: String = "at.desc",
        @Query("limit") limit: Int = 50
    ): List<ActivityItem>

    @GET("settings")
    suspend fun settings(
        @Query("id") idEq: String = "eq.singleton",
        @Query("select") select: String = "*"
    ): List<AutopilotSettings>

    @Headers("Prefer: return=representation")
    @PATCH("settings")
    suspend fun updateSettings(
        @Query("id") idEq: String = "eq.singleton",
        @Body settings: AutopilotSettings
    ): List<AutopilotSettings>

    @GET("follows")
    suspend fun follows(@Query("select") select: String = "*"): List<FollowRow>

    @Headers("Prefer: resolution=merge-duplicates,return=representation")
    @POST("follows")
    suspend fun upsertFollow(@Body row: FollowRow): List<FollowRow>

    /** [idEq] must be a PostgREST filter, e.g. "eq.ai-flagship". */
    @DELETE("follows")
    suspend fun deleteFollow(@Query("portfolioId") idEq: String): Response<Unit>
}

@Serializable
data class FollowRow(
    val portfolioId: String,
    val allocationPct: Double = 1.0,
    val followedAt: Double = 0.0
)
