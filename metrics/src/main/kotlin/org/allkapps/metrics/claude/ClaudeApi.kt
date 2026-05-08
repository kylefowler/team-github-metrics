package org.allkapps.metrics.claude

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.allkapps.metrics.commands.KEY_CLAUDE_API
import org.allkapps.metrics.commands.KEY_CLAUDE_ENTERPRISE_API

// ─────────────────────────────────────────────────────────────────────────────
// Claude Code Analytics API  (platform.claude.com — Admin API key)
// Endpoint: GET /v1/organizations/usage_report/claude_code
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ClaudeActor(
    val type: String = "",
    @SerialName("email_address") val emailAddress: String = ""
)

@Serializable
data class ClaudeLinesOfCode(
    val added: Long = 0,
    val removed: Long = 0
)

@Serializable
data class ClaudeCoreMetrics(
    @SerialName("num_sessions") val numSessions: Int = 0,
    @SerialName("lines_of_code") val linesOfCode: ClaudeLinesOfCode = ClaudeLinesOfCode(),
    @SerialName("commits_by_claude_code") val commitsByClaudeCode: Int = 0,
    @SerialName("pull_requests_by_claude_code") val pullRequestsByClaudeCode: Int = 0
)

@Serializable
data class ClaudeToolStat(
    val accepted: Int = 0,
    val rejected: Int = 0
)

@Serializable
data class ClaudeToolActions(
    @SerialName("edit_tool") val editTool: ClaudeToolStat = ClaudeToolStat(),
    @SerialName("multi_edit_tool") val multiEditTool: ClaudeToolStat = ClaudeToolStat(),
    @SerialName("write_tool") val writeTool: ClaudeToolStat = ClaudeToolStat(),
    @SerialName("notebook_edit_tool") val notebookEditTool: ClaudeToolStat = ClaudeToolStat()
)

@Serializable
data class ClaudeTokens(
    val input: Long = 0,
    val output: Long = 0,
    @SerialName("cache_read") val cacheRead: Long = 0,
    @SerialName("cache_creation") val cacheCreation: Long = 0
)

@Serializable
data class ClaudeEstimatedCost(
    val currency: String = "USD",
    val amount: Double = 0.0
)

@Serializable
data class ClaudeModelBreakdown(
    val model: String = "",
    val tokens: ClaudeTokens = ClaudeTokens(),
    @SerialName("estimated_cost") val estimatedCost: ClaudeEstimatedCost = ClaudeEstimatedCost()
)

@Serializable
data class ClaudeUsageEntry(
    val date: String = "",
    val actor: ClaudeActor = ClaudeActor(),
    @SerialName("organization_id") val organizationId: String = "",
    @SerialName("customer_type") val customerType: String = "",
    @SerialName("terminal_type") val terminalType: String = "",
    @SerialName("core_metrics") val coreMetrics: ClaudeCoreMetrics = ClaudeCoreMetrics(),
    @SerialName("tool_actions") val toolActions: ClaudeToolActions = ClaudeToolActions(),
    @SerialName("model_breakdown") val modelBreakdown: List<ClaudeModelBreakdown> = emptyList()
)

@Serializable
data class ClaudeUsageResponse(
    val data: List<ClaudeUsageEntry>,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page") val nextPage: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Claude Enterprise Analytics API  (claude.ai — Analytics API key)
// Endpoint: GET /v1/organizations/analytics/users?date=YYYY-MM-DD
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class EnterpriseUser(
    val id: String = "",
    @SerialName("email_address") val emailAddress: String = ""
)

// ─────────────────────────────────────────────────────────────────────────────
// Enterprise Cost & Usage Report endpoints (beta)
// Endpoints: GET /v1/organizations/analytics/user_usage_report
//            GET /v1/organizations/analytics/user_cost_report
// ─────────────────────────────────────────────────────────────────────────────

/** Actor object returned by the cost/usage report endpoints. */
@Serializable
data class EnterpriseAnalyticsActor(
    val type: String = "",
    @SerialName("user_id") val userId: String = "",
    val name: String? = null,
    val email: String? = null,
    val deleted: Boolean = false
)

/** Prompt-cache creation breakdown (usage report). */
@Serializable
data class EnterpriseCacheCreationTokens(
    @SerialName("ephemeral_5m_input_tokens") val ephemeral5mInputTokens: Long = 0,
    @SerialName("ephemeral_1h_input_tokens") val ephemeral1hInputTokens: Long = 0
)

/** Server-tool usage (usage report). */
@Serializable
data class EnterpriseServerToolUse(
    @SerialName("web_search_requests") val webSearchRequests: Int = 0
)

/** One row from user_usage_report. */
@Serializable
data class UserUsageReportEntry(
    val actor: EnterpriseAnalyticsActor = EnterpriseAnalyticsActor(),
    val product: String? = null,
    val model: String? = null,
    @SerialName("context_window") val contextWindow: String? = null,
    @SerialName("inference_geo") val inferenceGeo: String? = null,
    val speed: String? = null,
    @SerialName("uncached_input_tokens") val uncachedInputTokens: Long = 0,
    @SerialName("cache_creation") val cacheCreation: EnterpriseCacheCreationTokens = EnterpriseCacheCreationTokens(),
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    @SerialName("server_tool_use") val serverToolUse: EnterpriseServerToolUse = EnterpriseServerToolUse(),
    val requests: Int = 0
)

@Serializable
data class UserUsageReportResponse(
    @SerialName("organization_id") val organizationId: String = "",
    val data: List<UserUsageReportEntry> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page") val nextPage: String? = null,
    @SerialName("data_refreshed_at") val dataRefreshedAt: String? = null
)

/** One row from user_cost_report. */
@Serializable
data class UserCostReportEntry(
    val actor: EnterpriseAnalyticsActor = EnterpriseAnalyticsActor(),
    val product: String? = null,
    val model: String? = null,
    @SerialName("context_window") val contextWindow: String? = null,
    @SerialName("inference_geo") val inferenceGeo: String? = null,
    val speed: String? = null,
    val currency: String = "USD",
    /** Fractional cents string, e.g. "41280.000000" = $412.80 */
    val amount: String = "0",
    /** List-price (pre-discount) fractional cents string. */
    @SerialName("list_amount") val listAmount: String = "0",
    @SerialName("cost_type") val costType: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val requests: Int = 0
) {
    /** Parsed [amount] converted to USD dollars. */
    val amountUsd: Double get() = amount.toDoubleOrNull()?.div(100.0) ?: 0.0
    /** Parsed [listAmount] converted to USD dollars. */
    val listAmountUsd: Double get() = listAmount.toDoubleOrNull()?.div(100.0) ?: 0.0
}

@Serializable
data class UserCostReportResponse(
    @SerialName("organization_id") val organizationId: String = "",
    val data: List<UserCostReportEntry> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page") val nextPage: String? = null,
    @SerialName("data_refreshed_at") val dataRefreshedAt: String? = null
)

@Serializable
data class EnterpriseChatMetrics(
    @SerialName("distinct_conversation_count") val distinctConversationCount: Int = 0,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("distinct_projects_created_count") val distinctProjectsCreatedCount: Int = 0,
    @SerialName("distinct_projects_used_count") val distinctProjectsUsedCount: Int = 0,
    @SerialName("distinct_files_uploaded_count") val distinctFilesUploadedCount: Int = 0,
    @SerialName("distinct_artifacts_created_count") val distinctArtifactsCreatedCount: Int = 0,
    @SerialName("thinking_message_count") val thinkingMessageCount: Int = 0,
    @SerialName("distinct_skills_used_count") val distinctSkillsUsedCount: Int = 0,
    @SerialName("connectors_used_count") val connectorsUsedCount: Int = 0
)

@Serializable
data class EnterpriseCodeLinesOfCode(
    @SerialName("added_count") val addedCount: Long = 0,
    @SerialName("removed_count") val removedCount: Long = 0
)

@Serializable
data class EnterpriseCodeCoreMetrics(
    @SerialName("commit_count") val commitCount: Int = 0,
    @SerialName("pull_request_count") val pullRequestCount: Int = 0,
    @SerialName("lines_of_code") val linesOfCode: EnterpriseCodeLinesOfCode = EnterpriseCodeLinesOfCode(),
    @SerialName("distinct_session_count") val distinctSessionCount: Int = 0
)

@Serializable
data class EnterpriseCodeToolActions(
    @SerialName("edit_tool") val editTool: ClaudeToolStat = ClaudeToolStat(),
    @SerialName("multi_edit_tool") val multiEditTool: ClaudeToolStat = ClaudeToolStat(),
    @SerialName("write_tool") val writeTool: ClaudeToolStat = ClaudeToolStat(),
    @SerialName("notebook_edit_tool") val notebookEditTool: ClaudeToolStat = ClaudeToolStat()
)

@Serializable
data class EnterpriseClaudeCodeMetrics(
    @SerialName("core_metrics") val coreMetrics: EnterpriseCodeCoreMetrics = EnterpriseCodeCoreMetrics(),
    @SerialName("tool_actions") val toolActions: EnterpriseCodeToolActions = EnterpriseCodeToolActions()
)

@Serializable
data class EnterpriseUserEntry(
    val user: EnterpriseUser = EnterpriseUser(),
    @SerialName("chat_metrics") val chatMetrics: EnterpriseChatMetrics = EnterpriseChatMetrics(),
    @SerialName("claude_code_metrics") val claudeCodeMetrics: EnterpriseClaudeCodeMetrics = EnterpriseClaudeCodeMetrics(),
    @SerialName("web_search_count") val webSearchCount: Int = 0
)

@Serializable
data class EnterpriseUsersResponse(
    val data: List<EnterpriseUserEntry> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page") val nextPage: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Consolidated per-user stats (both sources merged)
// ─────────────────────────────────────────────────────────────────────────────

data class ClaudeUserStats(
    val email: String,
    // ── Claude Code API — behavioral metrics ────────────────────────────────
    val activeDays: Int,
    val sessions: Int,
    val linesAdded: Long,
    val linesRemoved: Long,
    val commits: Int,
    val pullRequests: Int,
    val toolAccepted: Int,
    val toolRejected: Int,
    // ── Enterprise user_usage_report — tokens across all products ────────────
    /** Uncached input tokens across all Claude products. */
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val totalTokens: Long,
    // ── Enterprise user_cost_report — cost across all products ───────────────
    /** Discounted USD cost across all Claude products (from user_cost_report). */
    val estimatedCostUsd: Double,
    /** List-price USD cost across all Claude products (pre-discount). */
    val listCostUsd: Double,
    // ── Enterprise Analytics API (chat / claude.ai metrics) ──────────────────
    val chatConversations: Int,
    val chatMessages: Int,
    val chatProjectsUsed: Int,
    val chatArtifactsCreated: Int,
    val chatThinkingMessages: Int,
    val webSearchCount: Int
)

// ─────────────────────────────────────────────────────────────────────────────
// Shared HTTP client builder
// ─────────────────────────────────────────────────────────────────────────────

private fun buildHttpClient(apiKey: String): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; isLenient = true; ignoreUnknownKeys = true })
    }
    install(Logging) { logger = Logger.DEFAULT; level = LogLevel.NONE }
    defaultRequest {
        url { protocol = URLProtocol.HTTPS; host = "api.anthropic.com" }
        headers {
            append("x-api-key", apiKey)
            append("anthropic-version", "2023-06-01")
            append(HttpHeaders.ContentType, "application/json")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Claude Code Analytics API client
// ─────────────────────────────────────────────────────────────────────────────

fun createClaudeClient(): HttpClient = buildHttpClient(
    System.getenv("CLAUDE_API_KEY") ?: Settings().getString(KEY_CLAUDE_API, "")
)

class ClaudeApi {
    private val client = createClaudeClient()

    fun close() = client.close()

    /** Pages through all results for the given [startDate] window. */
    suspend fun getAllEntries(startDate: LocalDate): List<ClaudeUsageEntry> {
        val allEntries = mutableListOf<ClaudeUsageEntry>()
        var nextPage: String? = null
        var hasMore = true

        while (hasMore) {
            val response = client.get("/v1/organizations/usage_report/claude_code") {
                parameter("starting_at", startDate.toString())
                parameter("limit", 1000)
                if (nextPage != null) parameter("next_page", nextPage)
            }.body<ClaudeUsageResponse>()

            allEntries.addAll(response.data)
            hasMore = response.hasMore
            nextPage = response.nextPage
        }
        return allEntries
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Enterprise Analytics API client
// ─────────────────────────────────────────────────────────────────────────────

fun createClaudeEnterpriseClient(): HttpClient = buildHttpClient(
    System.getenv("CLAUDE_ENTERPRISE_API_KEY") ?: Settings().getString(KEY_CLAUDE_ENTERPRISE_API, "")
)

class ClaudeEnterpriseApi {
    private val client = createClaudeEnterpriseClient()

    fun close() = client.close()

    /** Fetches all per-user entries for [date], handling cursor pagination.
     *  Returns an empty list and logs a warning if the API returns an error
     *  (e.g. date is too recent — Enterprise data has a 3-day delay). */
    suspend fun getUsersForDate(date: LocalDate): List<EnterpriseUserEntry> {
        val allEntries = mutableListOf<EnterpriseUserEntry>()
        var nextPage: String? = null
        var hasMore = true

        while (hasMore) {
            val httpResponse = client.get("/v1/organizations/analytics/users") {
                parameter("date", date.toString())
                parameter("limit", 1000)
                if (nextPage != null) parameter("page", nextPage)
            }
            if (!httpResponse.status.isSuccess()) {
                // Silently skip — most likely a 400 for a date that is too recent
                break
            }
            val response = httpResponse.body<EnterpriseUsersResponse>()
            allEntries.addAll(response.data)
            hasMore = response.hasMore
            nextPage = response.nextPage
        }
        return allEntries
    }

    /** Fetches all per-user entries for every day in [startDate]..[endDate).
     *  Automatically caps the range to respect the 3-day data-availability delay. */
    suspend fun getUsersForRange(startDate: LocalDate, endDate: LocalDate): List<EnterpriseUserEntry> {
        // Enterprise data is only available for dates at least 3 days in the past
        val latestAvailable = Clock.System.now()
            .toLocalDateTime(TimeZone.UTC).date
            .minus(3, DateTimeUnit.DAY)
        val effectiveEnd = minOf(endDate, latestAvailable.plus(1, DateTimeUnit.DAY))

        if (startDate >= effectiveEnd) return emptyList()

        val all = mutableListOf<EnterpriseUserEntry>()
        var current = startDate
        while (current < effectiveEnd) {
            all.addAll(getUsersForDate(current))
            current = current.plus(1, DateTimeUnit.DAY)
        }
        return all
    }

    /**
     * Fetches all rows from the beta [user_usage_report] endpoint for the given date range.
     * The range is split into at-most-31-day windows automatically.
     * Returns token usage data across all products per user.
     */
    suspend fun getUserUsageReport(startDate: LocalDate, endDate: LocalDate): List<UserUsageReportEntry> {
        val all = mutableListOf<UserUsageReportEntry>()
        // API limit: a single query may span at most 31 days
        var windowStart = startDate
        while (windowStart < endDate) {
            val windowEnd = minOf(endDate, windowStart.plus(31, DateTimeUnit.DAY))
            var nextPage: String? = null
            var hasMore = true
            while (hasMore) {
                val httpResponse = client.get("/v1/organizations/analytics/user_usage_report") {
                    parameter("starting_at", "${windowStart}T00:00:00Z")
                    parameter("ending_at", "${windowEnd}T00:00:00Z")
                    parameter("limit", 1000)
                    if (nextPage != null) parameter("page", nextPage)
                }
                if (!httpResponse.status.isSuccess()) break
                val response = httpResponse.body<UserUsageReportResponse>()
                all.addAll(response.data)
                hasMore = response.hasMore
                nextPage = response.nextPage
            }
            windowStart = windowEnd
        }
        return all
    }

    /**
     * Fetches all rows from the beta [user_cost_report] endpoint for the given date range.
     * The range is split into at-most-31-day windows automatically.
     * Returns USD cost data across all products per user.
     */
    suspend fun getUserCostReport(startDate: LocalDate, endDate: LocalDate): List<UserCostReportEntry> {
        val all = mutableListOf<UserCostReportEntry>()
        var windowStart = startDate
        while (windowStart < endDate) {
            val windowEnd = minOf(endDate, windowStart.plus(31, DateTimeUnit.DAY))
            var nextPage: String? = null
            var hasMore = true
            while (hasMore) {
                val httpResponse = client.get("/v1/organizations/analytics/user_cost_report") {
                    parameter("starting_at", "${windowStart}T00:00:00Z")
                    parameter("ending_at", "${windowEnd}T00:00:00Z")
                    parameter("limit", 1000)
                    if (nextPage != null) parameter("page", nextPage)
                }
                if (!httpResponse.status.isSuccess()) break
                val response = httpResponse.body<UserCostReportResponse>()
                all.addAll(response.data)
                hasMore = response.hasMore
                nextPage = response.nextPage
            }
            windowStart = windowEnd
        }
        return all
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Merge both sources into consolidated per-user stats
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fetches data from the Claude Code API, the Enterprise Analytics API,
 * and the new Enterprise cost/usage report endpoints for the window
 * [startDate, endDate) and returns consolidated per-user stats.
 *
 * - Behavioral metrics (sessions, LOC, commits, PRs, tools): Claude Code API
 *   with Enterprise analytics as fallback.
 * - Token usage: Enterprise user_usage_report (all products).
 *   Falls back to Code API model_breakdown when the new endpoint returns nothing.
 * - Cost: Enterprise user_cost_report (all products).
 *   Falls back to Code API estimated_cost when the new endpoint returns nothing.
 * - Chat metrics: Enterprise Analytics users endpoint.
 * - Web searches: Enterprise user_usage_report server_tool_use (primary) + legacy
 *   enterprise users endpoint.
 */
suspend fun getConsolidatedUserStats(
    startDate: LocalDate,
    endDate: LocalDate,
    codeApi: ClaudeApi,
    enterpriseApi: ClaudeEnterpriseApi
): List<ClaudeUserStats> {

    // ── Code API: group raw entries by email ──────────────────────────────────
    val codeByEmail = mutableMapOf<String, MutableList<ClaudeUsageEntry>>()
    codeApi.getAllEntries(startDate)
        .filter { entry ->
            val d = LocalDate.parse(entry.date.take(10))
            d >= startDate && d < endDate
        }
        .forEach { entry ->
            val email = entry.actor.emailAddress
            if (email.isNotBlank()) codeByEmail.getOrPut(email) { mutableListOf() }.add(entry)
        }

    // ── Enterprise API: group raw entries by email ────────────────────────────
    val enterpriseByEmail = mutableMapOf<String, MutableList<EnterpriseUserEntry>>()
    enterpriseApi.getUsersForRange(startDate, endDate).forEach { entry ->
        val email = entry.user.emailAddress
        if (email.isNotBlank()) enterpriseByEmail.getOrPut(email) { mutableListOf() }.add(entry)
    }

    // ── Enterprise user_usage_report: group by email ──────────────────────────
    val usageReportByEmail = mutableMapOf<String, MutableList<UserUsageReportEntry>>()
    enterpriseApi.getUserUsageReport(startDate, endDate).forEach { entry ->
        val email = entry.actor.email
        if (!email.isNullOrBlank()) usageReportByEmail.getOrPut(email) { mutableListOf() }.add(entry)
    }

    // ── Enterprise user_cost_report: group by email ───────────────────────────
    val costReportByEmail = mutableMapOf<String, MutableList<UserCostReportEntry>>()
    enterpriseApi.getUserCostReport(startDate, endDate).forEach { entry ->
        val email = entry.actor.email
        if (!email.isNullOrBlank()) costReportByEmail.getOrPut(email) { mutableListOf() }.add(entry)
    }

    // ── Merge on email ────────────────────────────────────────────────────────
    val allEmails = (codeByEmail.keys + enterpriseByEmail.keys +
                     usageReportByEmail.keys + costReportByEmail.keys).distinct()

    return allEmails.map { email ->
        val codeEntries       = codeByEmail[email] ?: emptyList()
        val enterpriseEntries = enterpriseByEmail[email] ?: emptyList()
        val usageEntries      = usageReportByEmail[email] ?: emptyList()
        val costEntries       = costReportByEmail[email] ?: emptyList()

        // Token data — prefer enterprise user_usage_report (all products),
        // fall back to Code API model_breakdown for backward compatibility.
        val inputTokens: Long
        val outputTokens: Long
        val cacheReadTokens: Long
        val cacheCreationTokens: Long
        val totalTokens: Long
        if (usageEntries.isNotEmpty()) {
            inputTokens         = usageEntries.sumOf { it.uncachedInputTokens }
            outputTokens        = usageEntries.sumOf { it.outputTokens }
            cacheReadTokens     = usageEntries.sumOf { it.cacheReadInputTokens }
            cacheCreationTokens = usageEntries.sumOf {
                it.cacheCreation.ephemeral5mInputTokens + it.cacheCreation.ephemeral1hInputTokens
            }
            totalTokens         = usageEntries.sumOf { it.totalTokens }
        } else {
            inputTokens         = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.input } }
            outputTokens        = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.output } }
            cacheReadTokens     = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.cacheRead } }
            cacheCreationTokens = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.cacheCreation } }
            totalTokens         = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens
        }

        // Cost — prefer enterprise user_cost_report (all products),
        // fall back to Code API estimated_cost.
        val estimatedCostUsd: Double
        val listCostUsd: Double
        if (costEntries.isNotEmpty()) {
            estimatedCostUsd = costEntries.sumOf { it.amountUsd }
            listCostUsd      = costEntries.sumOf { it.listAmountUsd }
        } else {
            estimatedCostUsd = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.estimatedCost.amount } }
            listCostUsd      = estimatedCostUsd   // no list-price data from Code API
        }

        // Web searches — prefer user_usage_report server_tool_use (covers all products),
        // fall back to legacy enterprise per-day count.
        val webSearchCount = if (usageEntries.isNotEmpty())
            usageEntries.sumOf { it.serverToolUse.webSearchRequests }
        else
            enterpriseEntries.sumOf { it.webSearchCount }

        ClaudeUserStats(
            email               = email,
            // Behavioral — Code API (enterprise analytics fallback)
            activeDays          = codeEntries.size.takeIf { it > 0 } ?: enterpriseEntries.size,
            sessions            = codeEntries.sumOf { it.coreMetrics.numSessions }
                                    .takeIf { it > 0 }
                                    ?: enterpriseEntries.sumOf { it.claudeCodeMetrics.coreMetrics.distinctSessionCount },
            linesAdded          = codeEntries.sumOf { it.coreMetrics.linesOfCode.added }
                                    .takeIf { it > 0L }
                                    ?: enterpriseEntries.sumOf { it.claudeCodeMetrics.coreMetrics.linesOfCode.addedCount },
            linesRemoved        = codeEntries.sumOf { it.coreMetrics.linesOfCode.removed }
                                    .takeIf { it > 0L }
                                    ?: enterpriseEntries.sumOf { it.claudeCodeMetrics.coreMetrics.linesOfCode.removedCount },
            commits             = codeEntries.sumOf { it.coreMetrics.commitsByClaudeCode }
                                    .takeIf { it > 0 }
                                    ?: enterpriseEntries.sumOf { it.claudeCodeMetrics.coreMetrics.commitCount },
            pullRequests        = codeEntries.sumOf { it.coreMetrics.pullRequestsByClaudeCode }
                                    .takeIf { it > 0 }
                                    ?: enterpriseEntries.sumOf { it.claudeCodeMetrics.coreMetrics.pullRequestCount },
            toolAccepted        = codeEntries.sumOf { e ->
                                    e.toolActions.editTool.accepted + e.toolActions.multiEditTool.accepted +
                                    e.toolActions.writeTool.accepted + e.toolActions.notebookEditTool.accepted
                                  }.takeIf { it > 0 }
                                    ?: enterpriseEntries.sumOf { e ->
                                    e.claudeCodeMetrics.toolActions.editTool.accepted +
                                    e.claudeCodeMetrics.toolActions.multiEditTool.accepted +
                                    e.claudeCodeMetrics.toolActions.writeTool.accepted +
                                    e.claudeCodeMetrics.toolActions.notebookEditTool.accepted },
            toolRejected        = codeEntries.sumOf { e ->
                                    e.toolActions.editTool.rejected + e.toolActions.multiEditTool.rejected +
                                    e.toolActions.writeTool.rejected + e.toolActions.notebookEditTool.rejected
                                  }.takeIf { it > 0 }
                                    ?: enterpriseEntries.sumOf { e ->
                                    e.claudeCodeMetrics.toolActions.editTool.rejected +
                                    e.claudeCodeMetrics.toolActions.multiEditTool.rejected +
                                    e.claudeCodeMetrics.toolActions.writeTool.rejected +
                                    e.claudeCodeMetrics.toolActions.notebookEditTool.rejected },
            // Token / cost — enterprise reports (Code API fallback)
            inputTokens         = inputTokens,
            outputTokens        = outputTokens,
            cacheReadTokens     = cacheReadTokens,
            cacheCreationTokens = cacheCreationTokens,
            totalTokens         = totalTokens,
            estimatedCostUsd    = estimatedCostUsd,
            listCostUsd         = listCostUsd,
            // Chat / claude.ai — Enterprise users API
            chatConversations   = enterpriseEntries.sumOf { it.chatMetrics.distinctConversationCount },
            chatMessages        = enterpriseEntries.sumOf { it.chatMetrics.messageCount },
            chatProjectsUsed    = enterpriseEntries.sumOf { it.chatMetrics.distinctProjectsUsedCount },
            chatArtifactsCreated = enterpriseEntries.sumOf { it.chatMetrics.distinctArtifactsCreatedCount },
            chatThinkingMessages = enterpriseEntries.sumOf { it.chatMetrics.thinkingMessageCount },
            webSearchCount      = webSearchCount
        )
    }.sortedByDescending { it.sessions + it.chatMessages }
}

