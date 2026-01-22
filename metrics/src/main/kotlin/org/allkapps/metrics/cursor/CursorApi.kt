package org.allkapps.metrics.cursor

import com.russhwolf.settings.Settings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.allkapps.metrics.commands.KEY_CURSOR_API

@Serializable
data class DailyUsageRequest(
    val startDate: Long,
    val endDate: Long
)

@Serializable
data class DailyUsageData(
    val date: Long, // Unix timestamp in milliseconds
    val isActive: Boolean,
    val totalLinesAdded: Int,
    val totalLinesDeleted: Int,
    val acceptedLinesAdded: Int,
    val acceptedLinesDeleted: Int,
    val totalApplies: Int,
    val totalAccepts: Int,
    val totalRejects: Int,
    val totalTabsShown: Int,
    val totalTabsAccepted: Int,
    val composerRequests: Int,
    val chatRequests: Int,
    val agentRequests: Int,
    val cmdkUsages: Int,
    val subscriptionIncludedReqs: Int,
    val apiKeyReqs: Int,
    val usageBasedReqs: Int,
    val bugbotUsages: Int,
    val mostUsedModel: String,
    val applyMostUsedExtension: String,
    val tabMostUsedExtension: String,
    val clientVersion: String? = null,
    val email: String
)

@Serializable
data class UsagePeriod(
    val startDate: Long,
    val endDate: Long
)

@Serializable
data class DailyUsageResponse(
    val data: List<DailyUsageData>,
    val period: UsagePeriod
)

fun createCursorClient(): HttpClient {
    val token = System.getenv("CURSOR_API_KEY")
        ?: Settings().getString(KEY_CURSOR_API, "")

    // Basic auth format: base64(apiKey:)
    val credentials = "$token:"
    val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())

    return HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.NONE
        }

        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.cursor.com"
            }
            headers {
                append(HttpHeaders.Authorization, "Basic $encodedCredentials")
                append(HttpHeaders.ContentType, "application/json")
            }
        }
    }
}

class CursorApi {
    private val client = createCursorClient()

    fun close() {
        client.close()
    }

    /**
     * Fetch daily usage data for the team
     * @param startDate Start date in YYYY-MM-DD format
     * @param endDate End date in YYYY-MM-DD format
     */
    suspend fun getDailyUsage(startDate: LocalDate, endDate: LocalDate): DailyUsageResponse {
        // Parse date strings and convert to millisecond timestamps
        val startTimestamp = startDate.atStartOfDayIn(TimeZone.UTC)
            .toEpochMilliseconds()
        val endTimestamp = endDate.atStartOfDayIn(TimeZone.UTC)
            .toEpochMilliseconds()

        return client.post("/teams/daily-usage-data") {
            setBody(DailyUsageRequest(
                startDate = startTimestamp,
                endDate = endTimestamp
            ))
        }.body<DailyUsageResponse>()
    }
}

/**
 * Aggregated usage data for a single user across multiple days
 */
data class UserUsageStats(
    val email: String,
    val totalDaysActive: Int,
    val totalLinesAdded: Int,
    val totalLinesDeleted: Int,
    val acceptedLinesAdded: Int,
    val acceptedLinesDeleted: Int,
    val totalApplies: Int,
    val totalAccepts: Int,
    val totalRejects: Int,
    val acceptanceRate: Double,
    val totalTabsShown: Int,
    val totalTabsAccepted: Int,
    val tabAcceptanceRate: Double,
    val composerRequests: Int,
    val chatRequests: Int,
    val agentRequests: Int,
    val totalAiRequests: Int,
    val cmdkUsages: Int,
    val subscriptionIncludedReqs: Int,
    val apiKeyReqs: Int,
    val usageBasedReqs: Int,
    val bugbotUsages: Int,
    val mostCommonModel: String,
    val mostCommonApplyExtension: String,
    val mostCommonTabExtension: String,
    val latestClientVersion: String
)

