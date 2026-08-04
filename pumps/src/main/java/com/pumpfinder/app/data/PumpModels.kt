package com.pumpfinder.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types — mirror the Python `Candidate` / `ScanResponse` shapes the
 * Pump-Finder backend serves at /api/scan and the FCM data payload it sends
 * for `type=pump_alert`.
 */

@Serializable
data class PumpCandidate(
    val ticker: String,
    val name: String,
    val price: Double,
    val changePct: Double,
    val volume: Long,
    val avgVolume: Long,
    val relVol: Double,
    val floatShares: Long? = null,
    val marketCap: Long? = null,
    val score: Int,
    val reasons: List<String> = emptyList(),
    val yahooUrl: String,
    val fbSearchUrl: String,
    val stocktwitsUrl: String,
)

@Serializable
data class ScanResponse(
    val asOf: Double,
    val source: String,           // "live" | "mock" | "offline"
    val count: Int,
    val candidates: List<PumpCandidate>,
)

@Serializable
data class FcmTokenRequest(val token: String)

@Serializable
data class TokenRegisterResponse(val ok: Boolean, val devices: Int)

@Serializable
data class AlertConfig(
    val minScore: Int,
    val intervalSec: Int,
    val firebaseEnabled: Boolean,
    val devicesRegistered: Int,
)
