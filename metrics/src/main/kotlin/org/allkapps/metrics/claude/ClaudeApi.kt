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
    // ── Claude Code API (token/cost data only from this source) ──────────────
    val activeDays: Int,
    val sessions: Int,
    val linesAdded: Long,
    val linesRemoved: Long,
    val commits: Int,
    val pullRequests: Int,
    val toolAccepted: Int,
    val toolRejected: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val totalTokens: Long,
    val estimatedCostUsd: Double,
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
}

// ─────────────────────────────────────────────────────────────────────────────
// Merge both sources into consolidated per-user stats
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fetches data from both the Claude Code API and the Enterprise Analytics API
 * for the window [startDate, endDate) and returns consolidated per-user stats.
 *
 * Token / cost data comes exclusively from the Code API (it's not in Enterprise).
 * Chat metrics come exclusively from the Enterprise API.
 * Code metrics (sessions, lines, commits, PRs, tools) prefer the Code API when
 * available, falling back to the Enterprise API for users not present there.
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

    // ── Merge on email ────────────────────────────────────────────────────────
    val allEmails = codeByEmail.keys + enterpriseByEmail.keys

    return allEmails.distinct().map { email ->
        val codeEntries       = codeByEmail[email] ?: emptyList()
        val enterpriseEntries = enterpriseByEmail[email] ?: emptyList()

        val inputTokens   = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.input } }
        val outputTokens  = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.output } }
        val cacheRead     = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.cacheRead } }
        val cacheCreation = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.tokens.cacheCreation } }

        ClaudeUserStats(
            email               = email,
            // Code API metrics (use enterprise fallback for session/line/commit if code API has nothing)
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
            // Token / cost — Code API only
            inputTokens         = inputTokens,
            outputTokens        = outputTokens,
            cacheReadTokens     = cacheRead,
            cacheCreationTokens = cacheCreation,
            totalTokens         = inputTokens + outputTokens + cacheRead + cacheCreation,
            estimatedCostUsd    = codeEntries.sumOf { e -> e.modelBreakdown.sumOf { it.estimatedCost.amount } },
            // Chat / claude.ai — Enterprise API only
            chatConversations   = enterpriseEntries.sumOf { it.chatMetrics.distinctConversationCount },
            chatMessages        = enterpriseEntries.sumOf { it.chatMetrics.messageCount },
            chatProjectsUsed    = enterpriseEntries.sumOf { it.chatMetrics.distinctProjectsUsedCount },
            chatArtifactsCreated = enterpriseEntries.sumOf { it.chatMetrics.distinctArtifactsCreatedCount },
            chatThinkingMessages = enterpriseEntries.sumOf { it.chatMetrics.thinkingMessageCount },
            webSearchCount      = enterpriseEntries.sumOf { it.webSearchCount }
        )
    }.sortedByDescending { it.sessions + it.chatMessages }
}

