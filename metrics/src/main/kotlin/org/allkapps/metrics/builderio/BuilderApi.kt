package org.allkapps.metrics.builderio

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
import org.allkapps.metrics.commands.KEY_BUILDER_API

@Serializable
data class TokenMetrics(
    val total: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cacheWrite: Long = 0,
    val cacheInput: Long = 0
)

@Serializable
data class UserMetrics(
    val linesAdded: Int = 0,
    val linesRemoved: Int = 0,
    val linesAccepted: Int = 0,
    val totalLines: Int = 0,
    val events: Int = 0,
    val userPrompts: Int = 0,
    val creditsUsed: String = "0",
    val prsMerged: Int = 0,
    val tokens: TokenMetrics = TokenMetrics()
)

@Serializable
data class UserMetric(
    val userId: String,
    val userEmail: String,
    val lastActive: String? = null,
    val designExports: Int = 0,
    val metrics: UserMetrics = UserMetrics()
)

@Serializable
data class OrganizationMetricsResponse(
    val data: List<UserMetric>
)

fun createBuilderClient(): HttpClient {
    val token = System.getenv("BUILDER_PRIVATE_KEY")
        ?: Settings().getString(KEY_BUILDER_API, "")

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
                host = "api.builder.io"
            }
            headers {
                append(HttpHeaders.Authorization, "Bearer $token")
                append(HttpHeaders.ContentType, "application/json")
            }
        }
    }
}

class BuilderApi {
    private val client = createBuilderClient()

    fun close() {
        client.close()
    }

    /**
     * Fetch organization metrics for a date range
     * @param organizationId The Builder.io organization ID
     * @param startDate Start date in YYYY-MM-DD format
     * @param endDate End date in YYYY-MM-DD format
     */
    suspend fun getOrganizationUserMetrics(
        startDate: String,
        endDate: String
    ): OrganizationMetricsResponse {
        return client.get("/api/v1/orgs/fusion/users") {
            parameter("startDate", startDate)
            parameter("endDate", endDate)
            parameter("granularity", "day")
        }.body()
    }

    /**
     * Fetch organization metrics using LocalDate objects
     */
    suspend fun getOrganizationUserMetrics(
        startDate: LocalDate,
        endDate: LocalDate
    ): OrganizationMetricsResponse {
        return getOrganizationUserMetrics(
            startDate = startDate.toString(),
            endDate = endDate.toString()
        )
    }
}

/**
 * Aggregated user statistics for Builder.io Fusion usage
 */
data class UserBuilderStats(
    val userId: String,
    val userEmail: String,
    val lastActive: String?,
    val designExports: Int,
    val linesAdded: Int,
    val linesRemoved: Int,
    val linesAccepted: Int,
    val totalLines: Int,
    val events: Int,
    val userPrompts: Int,
    val creditsUsed: Double,
    val prsMerged: Int,
    val tokensTotal: Long,
    val tokensInput: Long,
    val tokensOutput: Long,
    val tokensCacheWrite: Long,
    val tokensCacheInput: Long
)
