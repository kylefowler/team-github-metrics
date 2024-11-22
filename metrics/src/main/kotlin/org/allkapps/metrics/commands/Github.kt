package org.allkapps.metrics.commands

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.jakewharton.picnic.TextAlignment
import com.jakewharton.picnic.renderText
import com.jakewharton.picnic.table
import com.russhwolf.settings.Settings
import io.ktor.client.call.*
import io.ktor.http.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import org.allkapps.metrics.github.*
import java.io.File

const val KEY_GITHUB_DEFAULT: String = "github_access_key_default"
const val KEY_OPENAI_DEFAULT: String = "openai_api_key_default"

class Github : CliktCommand() {
    override fun run() {
        if (!Settings().hasKey(KEY_GITHUB_DEFAULT) && currentContext.invokedSubcommand?.commandName != "config") {
            throw UsageError("Run the config command before trying to execute any other commands")
        }
    }

    class Config : CliktCommand(help = "Configure your github personal access token and other keys") {
        val githubToken by option().help("Optional GitHub access token")
        val openAiKey by option().help("Optional OpenAI API key")

        override fun run() {
            val settings = Settings()

            if (githubToken != null && openAiKey != null) {
                settings.putString(KEY_GITHUB_DEFAULT, githubToken!!)
                settings.putString(KEY_OPENAI_DEFAULT, openAiKey!!)
                return
            }
            if (settings.hasKey(KEY_GITHUB_DEFAULT)) {
                val verify = terminal.prompt("GitHub token already exists, replace?", "Y", showDefault = true, showChoices = true, choices = listOf("Y", "N"))
                if (verify == "Y") {
                    terminal.prompt("Enter your GitHub personal access token")?.also {
                        settings.putString(KEY_GITHUB_DEFAULT, it)
                    }
                }
            } else {
                terminal.prompt("Enter your GitHub personal access token")?.also {
                    settings.putString(KEY_GITHUB_DEFAULT, it)
                }
            }
            if (settings.hasKey(KEY_OPENAI_DEFAULT)) {
                val verify = terminal.prompt("OpenAI key already exists, replace?", "Y", showDefault = true, showChoices = true, choices = listOf("Y", "N"))
                if (verify == "Y") {
                    terminal.prompt("Enter your OpenAI key")?.also {
                        settings.putString(KEY_OPENAI_DEFAULT, it)
                    }
                }
            } else {
                terminal.prompt("Enter your OpenAi key")?.also {
                    settings.putString(KEY_OPENAI_DEFAULT, it)
                }
            }
        }
    }

    class RateLimit: CliktCommand(help = "Check your github api key rate limit status") {
        override fun run() {
            val githubApi = GitHubApi()
            val output = runBlocking { githubApi.ratelimit() }
            println(output)
            githubApi.close()
        }
    }

    abstract class GithubPRCommand(help: String = ""): CliktCommand(help = help) {
        val org by option().default("builderio").help("The primary github organization to filter activity by")
        val team by option().help("Will look at activity by all members of the team")
        val author by option().help("Will look at activity only by this github user")
        val days by option().int().default(30).help("Look at the last N days of activity")
        open val fullDetails by option().flag().help("Fetch more detailed PR data (slower)")

        open val fetchReviewsInDetail = false
        open val fetchCommentsInDetail = false

        private val githubApi = GitHubApi()

        fun shutdown() {
            githubApi.close()
        }

        override fun run() {
            runCommand()
            shutdown()
        }

        abstract fun runCommand()

        protected suspend fun loadPrs(filterForOrg: Boolean = true): List<UserPrs> {
            var page = 1
            val perPage = 100
            var hasNextPage = true
            val allResults = mutableListOf<GitHubIssue>()

            val startDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

            while (hasNextPage) {
                val prResponse = githubApi.searchPRs(
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
            val detailUpdate = if (fullDetails) {
                val prDetails = githubApi.prDetails(allPRs, fetchReviewsInDetail, fetchCommentsInDetail).flatMap { req ->
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

    class TeamReviewParticipation : GithubPRCommand(help = "Get a report for how active someone is in participating in code reviews") {
        override val fetchReviewsInDetail = true
        override val fetchCommentsInDetail = true
        override val fullDetails = true

        override fun runCommand() {
            val prs = runBlocking {
                loadPrs()
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
                            cell("Last $days days of PR review activity") {
                                alignment = TextAlignment.TopCenter
                                columnSpan = 6
                            }
                        }
                        val headers = mutableListOf<Any?>().apply {
                            add("User")
                            add("Total PRs Reviewed")
                            add("Comments Left")
                            add("Prs Approved")
                            add("Requested Changes")
                            add("Pending")
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
                        Stats.computeReviewStatsForTeam(prs).sortedByDescending { it.prsReviewed }.forEach { stat ->
                            val cells = mutableListOf<Any>().apply {
                                add(stat.user.login)
                                add(stat.prsReviewed)
                                add(stat.commentsLeft)
                                add(stat.prsApproved)
                                add(stat.prsRequestedChanges)
                                add(stat.reviewsPending)
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

    class UserPrStats : GithubPRCommand(help = "Get a report of PR activity for a given user") {
        override fun runCommand() {
            if (author == null) {
                throw UsageError("Need to supply an author", "author")
            }
            val prs = runBlocking {
                loadPrs(filterForOrg = false)
            }
            val userStats = prs
            val columns = if (fullDetails) {
                9
            } else {
                6
            }
            val output = table {
                header {
                    cellStyle {
                        border = true
                        paddingLeft = 1
                        paddingRight = 1
                    }
                    row {
                        cell(" Last $days days of PRs for $author") {
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
                    Stats.computeActivityStatsForUser(userStats).sortedByDescending { it.merged }.forEach { stat ->
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
            println(output)
        }
    }

    class TeamPrStats : GithubPRCommand(help = "Get a report of PR activity by user for a given team") {
        override fun runCommand() {
            val prs = runBlocking {
                loadPrs()
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
                            cell(" Last $days days of PRs ") {
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
                        Stats.computeActivityStats(userStats).sortedByDescending { it.merged }.forEach { stat ->
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

    class Changelog : CliktCommand() {
        val org by option().default("builderio").help("The primary github organization to filter activity by")
        val days by option().int().default(7).help("Look at the last N days of activity")

        override fun run() {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val since = today.minus(DateTimeUnit.DayBased(days))
            val githubApi = GitHubApi()
            val mergedPrs = runBlocking { githubApi.searchPRs(org, startDate = since, mergedOnly = true).body<GithubIssueSearch>().items }
            val changelogContent = mergedPrs.joinToString("\n\n") {
                "<pr><repo>${it.repositoryUrl}</repo><title>${it.title}</title><body>${it.body}</body><link>${it.htmlUrl}</link></pr>"
            }

            val openAi = OpenAI(Settings().getString(KEY_OPENAI_DEFAULT, ""), logging = LoggingConfig(LogLevel.None))
            val output = runBlocking {
                chat(openAi, changelogContent)
            }
            File("changelog_${since}_${today}.md").writeText(output)
            githubApi.close()
        }

        suspend fun chat(openAI: OpenAI, changelogContent: String): String {
            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId("gpt-4o"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = "You are a product marketing manager trying to create a public facing changelog from " +
                                "engineering pull request titles and descriptions. This needs to be something that customers will understand " +
                                "but they wont have intimate knowledge of the details. You need to decide from the list of PR details, each surrounded with <pr></pr> tags," +
                                " how to best summarize the all of the PRs per repo in a changelog format. You should not necessarily have one line per PR." +
                                "The items should summarize for a customer what has changed and what they can expect. Dont go into too many details " +
                                "about PRs that are labelled as fixes and where possible combine those together more generally in a fixes line. When generating summaries, " +
                                " if possible include links to all of the PRs that went into generating that summary, the link to the PR is in the <link> tag for each PR in the input." +
                                "Ignore ones with 'test:' or 'chore:' in the title"
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = "Turn the following prs into a public changelog: \n $changelogContent”"
                    )
                )
            )
            val result = StringBuilder()
            openAI.chatCompletions(chatCompletionRequest)
                .onEach { result.append(it.choices.firstOrNull()?.delta?.content.orEmpty()) }
                .onCompletion { result.append("\n") }
                .collect()
            return result.toString()
        }
    }
}

data class UserPrs(
    val user: User,
    val prs: List<GitHubIssue>
)

object Stats {
    private val excludeBotUsers = setOf(
        "github-actions",
        "relativeci",
        "nx-cloud",
        "changeset-bot",
        "gitguardian",
        "netlify",
        "cloudflare-pages",
        "vercel",
        "sentry-io",
        "aws-amplify-us-east-1",
        "VitaliyHr",
        "cloudflare-workers-and-pages"
    )

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
    data class ReviewStats(
        val user: User,
        val prsReviewed: Int,
        val commentsLeft: Int,
        val prsApproved: Int,
        val prsRequestedChanges: Int,
        val reviewsPending: Int
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

    fun computeReviewStatsForTeam(prs: List<UserPrs>): List<ReviewStats> {
        val allReviewsByAuthor = prs.flatMap {
            it.prs.flatMap {
                issue -> issue.moreDetails?.reviews?.nodes?.map {
                    r -> Pair(r, issue)
                } ?: emptyList()
            }
        }.groupBy { it.first.author }
        val allCommentsByAuthor = prs.flatMap {
            it.prs.flatMap {
                issue -> issue.moreDetails?.comments?.nodes?.map {
                    c -> Pair(c, issue)
                } ?: emptyList()
            }
        }.groupBy { it.first.author }

        val authors = allReviewsByAuthor.keys.toSet() + allCommentsByAuthor.keys.toSet()

        return authors.filterNot { excludeBotUsers.contains(it.login) }.map { author ->
            val reviews = allReviewsByAuthor[author] ?: emptyList()
            val comments = allCommentsByAuthor[author]?.filter { it.first.author.login != it.second.user.login } ?: emptyList()
            ReviewStats(
                User(author.login),
                (reviews.map { it.second.number } + comments.map { it.second.number }).distinct().size,
                comments.size + reviews.count { it.first.state == ReviewState.COMMENTED },
                reviews.count { it.first.state == ReviewState.APPROVED },
                reviews.count { it.first.state == ReviewState.CHANGES_REQUESTED },
                reviews.count { it.first.state == ReviewState.PENDING },
            )
        }
    }
}

fun Double.format(digits: Int) = "%.${digits}f".format(this)