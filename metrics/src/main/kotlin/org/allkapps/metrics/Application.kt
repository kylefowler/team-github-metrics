package org.allkapps.metrics

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors
import com.jakewharton.picnic.TextAlignment
import com.jakewharton.picnic.renderText
import com.jakewharton.picnic.table
import com.russhwolf.settings.Settings
import io.ktor.client.call.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import org.allkapps.metrics.Github.Config.Companion.KEY_GITHUB_DEFAULT
import org.allkapps.metrics.github.*

class Metrics : NoOpCliktCommand() {
}

class Github : CliktCommand() {
    override fun run() {
        if (!Settings().hasKey(KEY_GITHUB_DEFAULT)) {
            throw UsageError("Run the config command before trying to execute any other commands")
        }
    }

    class Config :CliktCommand() {
        override fun run() {
            val settings = Settings()
            if (settings.hasKey(KEY_GITHUB_DEFAULT)) {
                val verify = terminal.prompt("GitHub token already exists, replace?", "Y", showDefault = true, showChoices = true, choices = listOf("Y", "N"))
                if (verify == "Y") {
                    terminal.prompt("Enter your GitHub personal access token")?.also {
                        settings.putString("github_access_key_default", it)
                    }
                }
            } else {
                terminal.prompt("Enter your GitHub personal access token")?.also {
                    settings.putString("github_access_key_default", it)
                }
            }
        }

        companion object {
            const val KEY_GITHUB_DEFAULT: String = "github_access_key_default"
        }
    }

    class RateLimit: CliktCommand() {
        override fun run() {
            val output = runBlocking { GitHubApi().ratelimit() }
            println(output)
        }
    }

    abstract class GithubPRCommand: CliktCommand() {
        val org by option().default("builderio")
        val team by option()
        val author by option()
        val days by option().int().default(30)
        val fullDetails by option().flag(defaultForHelp = "Fetch detailed PR data, slows down the command")

        protected suspend fun loadPrs(filterForOrg: Boolean = true, fetchDetails: Boolean = false): List<UserPrs> {
            var page = 1
            val perPage = 100
            var hasNextPage = true
            val allResults = mutableListOf<GitHubIssue>()

            val startDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

            while (hasNextPage) {
                val prResponse = GitHubApi().searchPRs(
                    if (filterForOrg) org else null,
                    team = team,
                    author = author,
                    page = page,
                    perPage = perPage,
                    startDate = startDate.date.minus(days, DateTimeUnit.DAY)
                )
                val linkHeader = prResponse.headers[HttpHeaders.Link]
                hasNextPage = linkHeader?.contains("rel=\"next\"") == true

                allResults.addAll(prResponse.body<GithubIssueSearch>().items)
                page++
            }
            val allPRs = allResults.filter { pr -> if (filterForOrg) pr.url.lowercase().contains(org.lowercase()) else true }
            val detailUpdate = if (fetchDetails) {
                val prDetails = GitHubApi().prDetails(allPRs).flatMap { req ->
                    req.data.values.flatMap { repo -> repo.values }
                }.associateBy { pr -> pr.url }
                allPRs.map { it.copy(moreDetails = prDetails[it.htmlUrl]) }
            } else {
                allPRs
            }
            return detailUpdate
                .groupBy { it.user }
                .map { UserPrs(it.key, it.value) }
        }
    }

    class UserPrStats : GithubPRCommand() {
        override fun run() {
            if (author == null) {
                throw UsageError("Need to supply an author", "author")
            }
            val prs = runBlocking {
                loadPrs(filterForOrg = false, fetchDetails = fullDetails)
            }
            val userStats = prs
            val columns = if (fullDetails) {
                9
            } else {
                6
            }
            println(
                table {
                    header {
                        cellStyle {
                            border = true
                            paddingLeft = 1
                            paddingRight = 1
                        }
                        row {
                            cell(TextColors.brightBlue.bg(" Last $days days of PRs for $author")) {
                                alignment = TextAlignment.TopCenter
                                columnSpan = columns
                            }
                        }
                        val headers = mutableListOf<Any?>().apply {
                            add("Repo")
                            add("Total PRs")
                            add("Open")
                            add("Merged")
                            add("Avg Days Open")
                            add("Comments")
                            if (fullDetails) {
                                add("Lines Added")
                                add("Lines Deleted")
                                add("Changed Files")
                            }
                        }
                        row {
                            headers.forEach { cell(it) }
                        }
                    }
                    body {
                        cellStyle {
                            paddingLeft = 1
                            paddingRight = 1
                        }
                        Stats.computeActivityStatsForUser(userStats).sortedByDescending { it.total }.forEach { stat ->
                            val cells = mutableListOf<Any>().apply {
                                add(stat.repo)
                                add(stat.total)
                                add(stat.open)
                                add(stat.merged)
                                add(stat.avgDaysOpen)
                                add(stat.comments)
                                if (fullDetails) {
                                    add(stat.additions)
                                    add(stat.subtractions)
                                    add(stat.filesChanged)
                                }
                            }
                            row {
                                cells.forEach { cell(it) }
                            }
                        }
                    }
                }.renderText()
            )
        }
    }

    class TeamPrStats : GithubPRCommand() {
        override fun run() {
            val prs = runBlocking {
                loadPrs(fetchDetails = fullDetails)
            }
            val userStats = prs
            val columns = if (fullDetails) {
                9
            } else {
                6
            }
            println(
                table {
                    header {
                        cellStyle {
                            border = true
                            paddingLeft = 1
                            paddingRight = 1
                        }
                        row {
                            cell(TextColors.brightBlue.bg(" Last $days days of PRs ")) {
                                alignment = TextAlignment.TopCenter
                                columnSpan = columns
                            }
                        }
                        val headers = mutableListOf<Any?>().apply {
                            add("User")
                            add("Total PRs")
                            add("Open")
                            add("Merged")
                            add("Avg Days Open")
                            add("Comments")
                            if (fullDetails) {
                                add("Lines Added")
                                add("Lines Deleted")
                                add("Changed Files")
                            }
                        }
                        row {
                            headers.forEach { cell(it) }
                        }
                    }
                    body {
                        cellStyle {
                            paddingLeft = 1
                            paddingRight = 1
                        }
                        Stats.computeActivityStats(userStats).sortedByDescending { it.total }.forEach { stat ->
                            val cells = mutableListOf<Any>().apply {
                                add(stat.user.login)
                                add(stat.total)
                                add(stat.open)
                                add(stat.merged)
                                add(stat.avgDaysOpen)
                                add(stat.comments)
                                if (fullDetails) {
                                    add(stat.additions)
                                    add(stat.subtractions)
                                    add(stat.filesChanged)
                                }
                            }
                            row {
                                cells.forEach { cell(it) }
                            }
                        }
                    }
                }.renderText()
            )
        }
    }
}

data class UserPrs(
    val user: User,
    val prs: List<GitHubIssue>
)

object Stats {
    data class ActivityStats(
        val user: User,
        val total: Int,
        val open: Int,
        val merged: Int,
        val avgDaysOpen: String,
        val comments: Int,
        val additions: Int,
        val subtractions: Int,
        val filesChanged: Int
    )
    data class RepoStats(
        val repo: String,
        val total: Int,
        val open: Int,
        val merged: Int,
        val avgDaysOpen: String,
        val comments: Int,
        val additions: Int,
        val subtractions: Int,
        val filesChanged: Int
    )

    fun computeActivityStats(prs: List<UserPrs>): List<ActivityStats> {
        return prs.map { up ->
            val user = up.user
            val (open, closed) = up.prs.partition { it.state == State.open }
            ActivityStats(
                user,
                up.prs.size,
                open.size,
                closed.count { it.pullRequest?.mergedAt != null },
                ((open.sumOf { it.daysOpen() } + closed.sumOf { it.daysOpen() }) / up.prs.size).format(2),
                open.sumOf { it.comments } + closed.sumOf { it.comments },
                up.prs.sumOf { it.moreDetails?.additions ?: 0 },
                up.prs.sumOf { it.moreDetails?.deletions ?: 0 },
                up.prs.sumOf { it.moreDetails?.changedFiles ?: 0 }
            )
        }
    }

    fun computeActivityStatsForUser(prs: List<UserPrs>): List<RepoStats> {
        return prs.flatMap { up ->
            up.prs.groupBy { it.repositoryUrl }.mapValues { (repo, prList) ->
                val (open, closed) = prList.partition { it.state == State.open }
                RepoStats(
                    repo.removePrefix("https://api.github.com/repos/"),
                    prList.size,
                    open.size,
                    closed.count { it.pullRequest?.mergedAt != null },
                    ((open.sumOf { it.daysOpen() } + closed.sumOf { it.daysOpen() }) / prList.size).format(2),
                    open.sumOf { it.comments } + closed.sumOf { it.comments },
                    prList.sumOf { it.moreDetails?.additions ?: 0 },
                    prList.sumOf { it.moreDetails?.deletions ?: 0 },
                    prList.sumOf { it.moreDetails?.changedFiles ?: 0 }
                )
            }.values
        }
    }
}

fun Double.format(digits: Int) = "%.${digits}f".format(this)

fun main(args: Array<String>) = Metrics()
    .subcommands(
        Github()
            .subcommands(
                Github.Config(),
                Github.RateLimit(),
                Github.TeamPrStats(),
                Github.UserPrStats()
            )
    ).main(args)