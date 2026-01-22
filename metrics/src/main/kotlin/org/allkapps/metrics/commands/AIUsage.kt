package org.allkapps.metrics.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import org.allkapps.metrics.github.GitHubApi
import org.allkapps.metrics.cursor.CursorApi
import org.allkapps.metrics.cursor.UserUsageStats
import java.util.concurrent.TimeUnit

class CollectMetrics : CliktCommand(help = "Collect engineering metrics and push to Grafana Alloy") {

    // Allow overriding the Alloy endpoint if needed
    private val alloyEndpoint by option("--alloy-url", help = "URL of Grafana Alloy OTLP receiver").required()

    private val org by option("--org", help = "GitHub organization to collect metrics for")
        .default("builderio")

    private val teams by option("--team", help = "GitHub teams to collect metrics for (can specify multiple)")
        .multiple()

    private val days by option("--days", help = "Number of days to look back for metrics")
        .int()
        .default(30)

    override fun run() {
        echo("Initializing OpenTelemetry...")

        // 1. Setup the OTLP Exporter (HTTP or gRPC)
        val exporter = OtlpHttpMetricExporter.builder()
            .setEndpoint(alloyEndpoint)
            .build()

        // 2. Setup the Provider
        val resource = Resource.getDefault().toBuilder()
            .put(ServiceAttributes.SERVICE_NAME, "engineering-metrics")
            .put(ServiceAttributes.SERVICE_VERSION, "1.0.0")
            .build()

        val metricReader = PeriodicMetricReader.builder(exporter)
            .setInterval(5, TimeUnit.SECONDS)
            .build()

        val sdkMeterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metricReader)
            .setResource(resource)
            .build()

        val meter: Meter = sdkMeterProvider.get("engineering.metrics")

        try {
            // 3. Collect metrics from different sources
            echo("Collecting GitHub metrics...")
            collectGithubMetrics(meter)

            echo("Collecting Cursor metrics...")
            collectCursorMetrics(meter)

            // TODO: Add Builder.io Fusion metrics collection
            // echo("Collecting Builder.io Fusion metrics...")
            // collectBuilderMetrics(meter)

            echo("All metrics collected successfully.")

        } catch (e: Exception) {
            echo("Error collecting metrics: ${e.message}")
            e.printStackTrace()
        } finally {
            // 4. Force Flush / Shutdown
            echo("Flushing metrics to Alloy...")
            sdkMeterProvider.shutdown().join(10, TimeUnit.SECONDS)
            echo("Done.")
        }
    }

    private fun collectGithubMetrics(meter: Meter) {
        val githubApi = GitHubApi()

        try {
            runBlocking {
                // Load PRs once for the entire organization or all specified teams combined
                // This prevents double-counting users who are in multiple teams
                val allUserPrs = loadPrsForAllTeams(githubApi)

                echo("Loaded PRs for ${allUserPrs.size} unique users")

                // Publish PR activity metrics
                publishPRActivityMetrics(meter, allUserPrs)

                // Publish review participation metrics
                publishReviewMetrics(meter, allUserPrs)
            }
        } finally {
            githubApi.close()
        }
    }

    private suspend fun loadPrsForAllTeams(githubApi: GitHubApi): List<UserPrs> {
        // If no teams specified, load for entire org
        if (teams.isEmpty()) {
            echo("Processing entire organization")
            return Github.GithubPRCommand.loadPrsInternal(
                githubApi = githubApi,
                org = org,
                team = null,
                author = null,
                days = days,
                filterForOrg = true,
                fullDetails = true,
                fetchReviewsInDetail = true,
                fetchCommentsInDetail = true
            )
        }

        // Load PRs for all teams and merge them, deduplicating by user
        echo("Processing teams: ${teams.joinToString(", ")}")
        val allPrsByUser = mutableMapOf<String, MutableList<org.allkapps.metrics.github.GitHubIssue>>()

        teams.forEach { team ->
            echo("Loading PRs for team: $team")
            val teamUserPrs = Github.GithubPRCommand.loadPrsInternal(
                githubApi = githubApi,
                org = org,
                team = team,
                author = null,
                days = days,
                filterForOrg = true,
                fullDetails = true,
                fetchReviewsInDetail = true,
                fetchCommentsInDetail = true
            )

            // Merge PRs by user, deduplicating PRs
            teamUserPrs.forEach { userPr ->
                val userLogin = userPr.user.login
                val existingPrs = allPrsByUser.getOrPut(userLogin) { mutableListOf() }

                // Add PRs that aren't already in the list (deduplicate by PR number and repo)
                userPr.prs.forEach { pr ->
                    if (!existingPrs.any { it.number == pr.number && it.repositoryUrl == pr.repositoryUrl }) {
                        existingPrs.add(pr)
                    }
                }
            }
        }

        // Convert back to UserPrs list
        return allPrsByUser.map { (_, prs) ->
            // All PRs in the list have the same user, so grab it from the first one
            UserPrs(prs.first().user, prs)
        }
    }

    private fun publishPRActivityMetrics(meter: Meter, userPrs: List<UserPrs>) {
        // Define metric instruments
        meter.gaugeBuilder("engmetrics_github_prs_total")
            .setDescription("Total number of PRs by user")
            .setUnit("{pr}")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.total.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_open")
            .setDescription("Number of open PRs by user")
            .setUnit("{pr}")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.open.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_merged")
            .setDescription("Number of merged PRs by user")
            .setUnit("{pr}")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.merged.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_avg_days_open")
            .setDescription("Average days PRs are open by user")
            .setUnit("d")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.avgDaysOpen.toDoubleOrNull() ?: 0.0,
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_median_days_open")
            .setDescription("Median days PRs are open by user")
            .setUnit("d")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.medianDaysOpen.toDoubleOrNull() ?: 0.0,
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_comments")
            .setDescription("Total comments on PRs by user")
            .setUnit("{comment}")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.comments.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_lines_added")
            .setDescription("Total lines added in PRs by user")
            .setUnit("{line}")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.additions.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_lines_deleted")
            .setDescription("Total lines deleted in PRs by user")
            .setUnit("{line}")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.subtractions.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_prs_files_changed")
            .setDescription("Total files changed in PRs by user")
            .setUnit("{file}")
            .buildWithCallback { result ->
                val stats = Stats.computeActivityStats(userPrs)
                stats.forEach { stat ->
                    result.record(
                        stat.filesChanged.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        echo("Published PR activity metrics for ${userPrs.size} users")
    }

    private fun publishReviewMetrics(meter: Meter, userPrs: List<UserPrs>) {
        val reviewStats = Stats.computeReviewStatsForTeam(userPrs)

        meter.gaugeBuilder("engmetrics_github_reviews_prs_reviewed")
            .setDescription("Number of PRs reviewed by user")
            .setUnit("{pr}")
            .buildWithCallback { result ->
                reviewStats.forEach { stat ->
                    result.record(
                        stat.prsReviewed.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_reviews_comments_left")
            .setDescription("Number of review comments left by user")
            .setUnit("{comment}")
            .buildWithCallback { result ->
                reviewStats.forEach { stat ->
                    result.record(
                        stat.commentsLeft.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_reviews_prs_approved")
            .setDescription("Number of PRs approved by user")
            .setUnit("{pr}")
            .buildWithCallback { result ->
                reviewStats.forEach { stat ->
                    result.record(
                        stat.prsApproved.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_github_reviews_changes_requested")
            .setDescription("Number of PRs where changes were requested by user")
            .setUnit("{pr}")
            .buildWithCallback { result ->
                reviewStats.forEach { stat ->
                    result.record(
                        stat.prsRequestedChanges.toDouble(),
                        Attributes.builder()
                            .put("user", stat.user.login)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        echo("Published review metrics for ${reviewStats.size} reviewers")
    }

    // Implement Cursor metrics collection
    private fun collectCursorMetrics(meter: Meter) {
        val cursorApi = CursorApi()

        try {
            runBlocking {
                // Calculate date range: get today and go back X days (like GitHub PR loading)
                val startDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                val endDate = startDate.date
                val since = endDate.minus(days, DateTimeUnit.DAY)

                echo("Fetching Cursor usage data from $since to $endDate...")

                // Fetch daily usage data from Cursor API
                val response = cursorApi.getDailyUsage(
                    startDate = since,
                    endDate = endDate
                )

                echo("Received ${response.data.size} daily usage records")

                // Aggregate data by user email
                val userDataMap = mutableMapOf<String, MutableList<org.allkapps.metrics.cursor.DailyUsageData>>()

                response.data.forEach { dailyData ->
                    userDataMap.getOrPut(dailyData.email) { mutableListOf() }.add(dailyData)
                }

                // Convert to UserUsageStats objects with aggregated data
                val userStats = userDataMap.map { (email, dailyDataList) ->
                    val activeDays = dailyDataList.count { it.isActive }
                    val totalAccepts = dailyDataList.sumOf { it.totalAccepts }
                    val totalRejects = dailyDataList.sumOf { it.totalRejects }
                    val totalApplies = dailyDataList.sumOf { it.totalApplies }
                    val acceptanceRate = if (totalApplies > 0) (totalAccepts.toDouble() / totalApplies.toDouble()) * 100 else 0.0

                    val totalTabsShown = dailyDataList.sumOf { it.totalTabsShown }
                    val totalTabsAccepted = dailyDataList.sumOf { it.totalTabsAccepted }
                    val tabAcceptanceRate = if (totalTabsShown > 0) (totalTabsAccepted.toDouble() / totalTabsShown.toDouble()) * 100 else 0.0

                    // Find most common values
                    val mostCommonModel = dailyDataList.groupBy { it.mostUsedModel }
                        .maxByOrNull { it.value.size }?.key ?: "N/A"
                    val mostCommonApplyExt = dailyDataList.groupBy { it.applyMostUsedExtension }
                        .maxByOrNull { it.value.size }?.key ?: "N/A"
                    val mostCommonTabExt = dailyDataList.groupBy { it.tabMostUsedExtension }
                        .maxByOrNull { it.value.size }?.key ?: "N/A"
                    val latestVersion = dailyDataList.maxByOrNull { it.date }?.clientVersion ?: "N/A"

                    UserUsageStats(
                        email = email,
                        totalDaysActive = activeDays,
                        totalLinesAdded = dailyDataList.sumOf { it.totalLinesAdded },
                        totalLinesDeleted = dailyDataList.sumOf { it.totalLinesDeleted },
                        acceptedLinesAdded = dailyDataList.sumOf { it.acceptedLinesAdded },
                        acceptedLinesDeleted = dailyDataList.sumOf { it.acceptedLinesDeleted },
                        totalApplies = totalApplies,
                        totalAccepts = totalAccepts,
                        totalRejects = totalRejects,
                        acceptanceRate = acceptanceRate,
                        totalTabsShown = totalTabsShown,
                        totalTabsAccepted = totalTabsAccepted,
                        tabAcceptanceRate = tabAcceptanceRate,
                        composerRequests = dailyDataList.sumOf { it.composerRequests },
                        chatRequests = dailyDataList.sumOf { it.chatRequests },
                        agentRequests = dailyDataList.sumOf { it.agentRequests },
                        totalAiRequests = dailyDataList.sumOf { it.composerRequests + it.chatRequests + it.agentRequests },
                        cmdkUsages = dailyDataList.sumOf { it.cmdkUsages },
                        subscriptionIncludedReqs = dailyDataList.sumOf { it.subscriptionIncludedReqs },
                        apiKeyReqs = dailyDataList.sumOf { it.apiKeyReqs },
                        usageBasedReqs = dailyDataList.sumOf { it.usageBasedReqs },
                        bugbotUsages = dailyDataList.sumOf { it.bugbotUsages },
                        mostCommonModel = mostCommonModel,
                        mostCommonApplyExtension = mostCommonApplyExt,
                        mostCommonTabExtension = mostCommonTabExt,
                        latestClientVersion = latestVersion
                    )
                }.sortedByDescending { it.totalAiRequests }

                // Publish OpenTelemetry metrics
                publishCursorMetrics(meter, userStats)
            }
        } catch (e: Exception) {
            echo("Error collecting Cursor metrics: ${e.message}")
            e.printStackTrace()
        } finally {
            cursorApi.close()
        }
    }

    private fun publishCursorMetrics(meter: Meter, userStats: List<UserUsageStats>) {
        meter.gaugeBuilder("engmetrics_cursor_days_active")
            .setDescription("Number of days user was active in Cursor")
            .setUnit("d")
            .buildWithCallback { result ->
                userStats.forEach { stat ->
                    result.record(
                        stat.totalDaysActive.toDouble(),
                        Attributes.builder()
                            .put("user", stat.email)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_cursor_chat_requests")
            .setDescription("Total chat requests by user")
            .setUnit("{request}")
            .buildWithCallback { result ->
                userStats.forEach { stat ->
                    result.record(
                        stat.chatRequests.toDouble(),
                        Attributes.builder()
                            .put("user", stat.email)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_cursor_agent_requests")
            .setDescription("Total agent requests by user")
            .setUnit("{request}")
            .buildWithCallback { result ->
                userStats.forEach { stat ->
                    result.record(
                        stat.agentRequests.toDouble(),
                        Attributes.builder()
                            .put("user", stat.email)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_cursor_lines_added")
            .setDescription("Total lines added via Cursor by user")
            .setUnit("{line}")
            .buildWithCallback { result ->
                userStats.forEach { stat ->
                    result.record(
                        stat.totalLinesAdded.toDouble(),
                        Attributes.builder()
                            .put("user", stat.email)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        meter.gaugeBuilder("engmetrics_cursor_lines_deleted")
            .setDescription("Total lines deleted via Cursor by user")
            .setUnit("{line}")
            .buildWithCallback { result ->
                userStats.forEach { stat ->
                    result.record(
                        stat.totalLinesDeleted.toDouble(),
                        Attributes.builder()
                            .put("user", stat.email)
                            .put("days", days.toString())
                            .build()
                    )
                }
            }

        echo("Published Cursor metrics for ${userStats.size} users")
    }

    // TODO: Implement Builder.io Fusion metrics collection
    private fun collectBuilderMetrics(meter: Meter) {
        // Placeholder for Builder.io Fusion API metrics
        // This would integrate with Builder.io Fusion API to fetch:
        // - Visual development metrics
        // - Component usage
        // - Content updates
        echo("Builder.io Fusion metrics collection not yet implemented")
    }
}