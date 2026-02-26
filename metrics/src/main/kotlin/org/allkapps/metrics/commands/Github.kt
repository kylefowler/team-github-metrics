package org.allkapps.metrics.commands

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
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
import com.github.ajalt.clikt.parameters.types.choice
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.allkapps.metrics.github.*
import java.io.File
import kotlin.time.Duration.Companion.minutes

const val KEY_GITHUB_DEFAULT: String = "github_access_key_default"
const val KEY_OPENAI_DEFAULT: String = "openai_api_key_default"
const val KEY_CURSOR_API: String = "cursor_api_key"
const val KEY_BUILDER_API: String = "builder_private_key"

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
                val verify = terminal.prompt(
                    "GitHub token already exists, replace?",
                    "Y",
                    showDefault = true,
                    showChoices = true,
                    choices = listOf("Y", "N")
                )
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
                val verify = terminal.prompt(
                    "OpenAI key already exists, replace?",
                    "Y",
                    showDefault = true,
                    showChoices = true,
                    choices = listOf("Y", "N")
                )
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

    class RateLimit : CliktCommand(help = "Check your github api key rate limit status") {
        override fun run() {
            val githubApi = GitHubApi()
            val output = runBlocking { githubApi.ratelimit() }
            println(output)
            githubApi.close()
        }
    }

    abstract class GithubPRCommand(help: String = "") : CliktCommand(help = help) {
        val org by option().default("builderio").help("The primary github organization to filter activity by")
        val team by option().help("Will look at activity by all members of the team")
        val author by option().help("Will look at activity only by this github user")
        val days by option().int().default(30).help("Look at the last N days of activity")
        open val fullDetails by option().flag().help("Fetch more detailed PR data (slower)")
        val json by option().flag().help("Output results in JSON format instead of table")

        open val fetchReviewsInDetail = false
        open val fetchCommentsInDetail = false

        private val githubApi = GitHubApi()
        protected val jsonSerializer = Json { prettyPrint = true }

        fun getGithubApi(): GitHubApi = githubApi

        fun shutdown() {
            githubApi.close()
        }

        override fun run() {
            runCommand()
            shutdown()
        }

        abstract fun runCommand()

        protected suspend fun loadPrs(filterForOrg: Boolean = true): List<UserPrs> {
            return loadPrsInternal(
                githubApi = githubApi,
                org = if (filterForOrg) org else null,
                team = team,
                author = author,
                days = days,
                filterForOrg = filterForOrg,
                fullDetails = fullDetails,
                fetchReviewsInDetail = fetchReviewsInDetail,
                fetchCommentsInDetail = fetchCommentsInDetail
            )
        }

        companion object {
            suspend fun loadPrsInternal(
                githubApi: GitHubApi,
                org: String?,
                team: String?,
                author: String?,
                days: Int,
                filterForOrg: Boolean = true,
                fullDetails: Boolean = false,
                fetchReviewsInDetail: Boolean = false,
                fetchCommentsInDetail: Boolean = false
            ): List<UserPrs> {
                var page = 1
                val perPage = 50
                var hasNextPage = true
                val allResults = mutableListOf<GitHubIssue>()

                val startDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

                while (hasNextPage) {
                    val prResponse = githubApi.searchPRs(
                        org,
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
                val allPRs = if (filterForOrg && org != null) {
                    allResults.filter { pr -> pr.url.lowercase().contains(org.lowercase()) }
                } else {
                    allResults
                }
                val detailUpdate = if (fullDetails) {
                    val prDetails = allPRs.chunked(25).flatMap { chunk ->
                        githubApi.prDetails(chunk, fetchReviewsInDetail, fetchCommentsInDetail).flatMap { req ->
                            req.data.values.flatMap { repo -> repo.values }
                        }
                    }.associateBy { pr -> pr.url }
                    allPRs.map { it.copy(moreDetails = prDetails[it.htmlUrl]) }
                } else {
                    allPRs
                }
                return detailUpdate
                    .groupBy { it.user }
                    .map { UserPrs(it.key, it.value) }
                    .filter { !Stats.isExcludedUser(it.user.login) }
            }
        }
    }

    class TeamReviewParticipation :
        GithubPRCommand(help = "Get a report for how active someone is in participating in code reviews") {
        override val fetchReviewsInDetail = true
        override val fetchCommentsInDetail = true
        override val fullDetails = true

        override fun runCommand() {
            val prs = runBlocking {
                loadPrs()
            }

            val stats = Stats.computeReviewStatsForTeam(prs).sortedByDescending { it.prsReviewed }

            if (json) {
                println(jsonSerializer.encodeToString(stats))
                return
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
                        stats.forEach { stat ->
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

    class UserReviewParticipation :
        GithubPRCommand(help = "Get a report for how active someone is in participating in code reviews") {
        override val fetchReviewsInDetail = true
        override val fetchCommentsInDetail = true
        override val fullDetails = true

        val user by option().help("Only show activity for a given user")

        override fun runCommand() {
            val prs = runBlocking {
                loadPrs()
            }

            val stats = Stats.computeReviewStatsForUser(prs, user!!)

            if (json) {
                val simplified = stats.map { stat ->
                    Stats.PRDetailSimplified(
                        prUrl = stat.pr.htmlUrl,
                        prTitle = stat.pr.title,
                        reviews = stat.reviews.map { it.body },
                        comments = stat.comments.map { it.bodyText }
                    )
                }
                println(jsonSerializer.encodeToString(simplified))
                return
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
                            cell("Last $days days of PR review activity for ${user}") {
                                alignment = TextAlignment.TopCenter
                                columnSpan = 3
                            }
                        }
                        val headers = mutableListOf<Any?>().apply {
                            add("PR")
                            add("Reviews")
                            add("Comments")
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
                        stats.forEach { stat ->
                            val cells = mutableListOf<Any>().apply {
                                add(stat.pr.htmlUrl)
                                add(stat.reviews.map { it.body }.joinToString("\n"))
                                add(stat.comments.map { it.bodyText }.joinToString("\n"))
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
            val stats = Stats.computeActivityStatsForUser(userStats).sortedByDescending { it.merged }

            if (json) {
                println(jsonSerializer.encodeToString(stats))
                return
            }

            val columns = if (fullDetails) {
                10
            } else {
                7
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
                        add("Closed")
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
                    stats.forEach { stat ->
                        val cells = mutableListOf<Any>().apply {
                            add(stat.repo)
                            add(stat.total)
                            add(stat.open)
                            add(stat.merged)
                            add(stat.closed)
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
            val stats = Stats.computeActivityStats(userStats).sortedByDescending { it.merged }

            if (json) {
                println(jsonSerializer.encodeToString(stats))
                return
            }

            val columns = if (fullDetails) {
                10
            } else {
                7
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
                            add("Median Days Open")
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
                        stats.forEach { stat ->
                            val cells = mutableListOf<Any>().apply {
                                add(stat.user.login)
                                add(stat.total)
                                add(stat.open)
                                add(stat.merged)
                                add(stat.avgDaysOpen)
                                add(stat.medianDaysOpen)
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
        val startDate by option().help("The start date to look at activity from in the format of yyyy-MM-dd")
        val repo by option().help("A specific repo to look at")
        val persona by option().choice("marketing", "engineering").default("marketing")
            .help("The persona to write the changelog for, marketing will be more high level, engineering more detailed")

        override fun run() {
            val today = if (startDate != null) {
                LocalDate.parse(startDate!!)
            } else {
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            }
            val since = today.minus(DateTimeUnit.DayBased(days))
            val githubApi = GitHubApi()

            val mergedPrs = runBlocking {
                var page = 1
                val perPage = 50
                var hasNextPage = true
                val allResults = mutableListOf<GitHubIssue>()

                while (hasNextPage) {
                    val prResponse = githubApi.searchPRs(
                        org,
                        startDate = since,
                        mergedOnly = true,
                        endDate = if (startDate != null) today else null,
                        repo = repo,
                        page = page,
                        perPage = perPage
                    )
                    val linkHeader = prResponse.headers[HttpHeaders.Link]
                    hasNextPage = linkHeader?.contains("rel=\"next\"") == true

                    allResults.addAll(prResponse.body<GithubIssueSearch>().items)
                    page++
                }
                allResults
            }

            val changelogContent = mergedPrs.joinToString("\n\n") {
                "<pr><repo>${it.repositoryUrl}</repo><title>${it.title}</title><body>${it.body}</body><link>${it.htmlUrl}</link></pr>"
            }

            println("Got ${mergedPrs.size} merged PRs to consider for changelog")

            val openAi = OpenAI(Settings().getString(KEY_OPENAI_DEFAULT, ""), logging = LoggingConfig(LogLevel.None), timeout = Timeout(5.minutes, 2.minutes, 5.minutes))
            val output = runBlocking {
                chat(openAi, changelogContent)
            }
            File("changelog_${since}_${today}.md").writeText(output)
            githubApi.close()
        }

        suspend fun chat(openAI: OpenAI, changelogContent: String): String {
            val prompt = if (persona == "marketing") {
                "You are a product marketing manager trying to create a public facing changelog from engineering pull request titles and descriptions. This needs to be something that customers will understand but they wont have intimate knowledge of the details. You need to decide from the list of PR details, each surrounded with <pr></pr> tags, how to best summarize the all of the PRs per repo in a changelog format. You should not necessarily have one line per PR. The items should summarize for a customer what has changed and what they can expect. Dont go into too many details about PRs that are labelled as fixes and where possible combine those together more generally in a fixes line. When generating summaries, if possible include links to all of the PRs that went into generating that summary, the link to the PR is in the <link> tag for each PR in the input. Ignore ones with 'test:' or 'chore:' in the title"
            } else {
                """
                    You are an engineering manager tasked with creating an internal facing changelog from engineering pull request titles and descriptions for non-engineering team consumption. 
                    It is important that this changelog is detailed so that readers can understand the new features that were added, and the fixes that were being made. Call outs of customer impact and things done for a specific customer are very important to include. You will get details about each PR in a structured format, 
                    each surrounded with <pr></pr> tags. In order to create the changelog you need to summarize the details of the PRs per repo in a changelog format. If a PR doesnt have a body, just use the title. If there is not enough information to go on, ignore the PR for summarization but include the link to that PR
                     at the end with others that were ignored. You should not necessarily have one line per PR. The items should summarize for the team what has changed in terms of functionality, bug fixes, improvements for customers and what others can expect.
                    When generating summaries, if include links to all of the PRs that went into generating that summary, the link to the PR is in the <link> tag for each PR in the input. 
                    Ignore ones with 'test:' or 'chore:' in the title
                """.trimIndent()
            }
            println(prompt)
            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId("gpt-5"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = prompt
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = "Turn the following prs into a public changelog: \n $changelogContent”"
                    )
                )
            )
            val result = StringBuilder()
            openAI.chatCompletions(chatCompletionRequest)
                .onEach {
                    result.append(it.choices.firstOrNull()?.delta?.content.orEmpty())
                }
                .onCompletion { result.append("\n") }
                .collect()
            return result.toString()
        }
    }

    class QaTestPlan : CliktCommand(help = "Generate a QA test plan for changes merged in the last 24 hours") {
        val org by option().default("builderio").help("The primary github organization to filter activity by")
        val startDate by option().help("The start date to look at activity from in the format of yyyy-MM-dd")
        val repos by option().help("Comma-separated list of repo names to include (e.g. builder-internal,ai-services). Overrides --repo.")
            .default("builder-internal,ai-services")

        override fun run() {
            val today = if (startDate != null) {
                LocalDate.parse(startDate!!)
            } else {
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            }
            val since = today.minus(DateTimeUnit.DayBased(1))
            val githubApi = GitHubApi()
            val repoFilter = repos.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val mergedPrs = runBlocking {
                var page = 1
                val perPage = 50
                var hasNextPage = true
                val allResults = mutableListOf<GitHubIssue>()

                while (hasNextPage) {
                    val prResponse = githubApi.searchPRs(
                        org,
                        startDate = since,
                        mergedOnly = true,
                        endDate = if (startDate != null) today else null,
                        page = page,
                        perPage = perPage
                    )
                    val linkHeader = prResponse.headers[HttpHeaders.Link]
                    hasNextPage = linkHeader?.contains("rel=\"next\"") == true

                    allResults.addAll(prResponse.body<GithubIssueSearch>().items)
                    page++
                }

                println("Found ${allResults.size} merged PRs in the last 24 hours before filtering for repos")

                allResults.filter { issue ->
                    repoFilter.any { issue.repositoryUrl.contains(it, ignoreCase = true) }
                }
            }

            println("Filtering to repos: ${repoFilter.joinToString(", ")}")

            if (mergedPrs.isEmpty()) {
                println("No merged PRs found for the given time range.")
                githubApi.close()
                return
            }

            val prContent = mergedPrs.joinToString("\n\n") {
                "<pr>" +
                    "<repo>${it.repositoryUrl.removePrefix("https://api.github.com/repos/")}</repo>" +
                    "<title>${it.title}</title>" +
                    "<body>${it.body.orEmpty()}</body>" +
                    "<author>${it.user.login}</author>" +
                    "<link>${it.htmlUrl}</link>" +
                    "<labels>${it.labels.joinToString(", ") { l -> l.name }}</labels>" +
                    "</pr>"
            }

            println("Got ${mergedPrs.size} merged PRs to build a QA test plan from")

            val openAi = OpenAI(
                Settings().getString(KEY_OPENAI_DEFAULT, ""),
                logging = LoggingConfig(LogLevel.None),
                timeout = Timeout(5.minutes, 2.minutes, 5.minutes)
            )
            val output = runBlocking {
                generateQaTestPlan(openAi, prContent, since, today)
            }
            val outputFile = "qa_test_plan_${since}_${today}.md"
            File(outputFile).writeText(output)
            println("QA test plan written to $outputFile")
            githubApi.close()
        }

        private suspend fun generateQaTestPlan(openAI: OpenAI, prContent: String, since: LocalDate, today: LocalDate): String {
            val prompt = """
                You are a senior QA engineer creating a concise daily test plan for a manual / validation QA tester.
                You will be given a list of pull requests that were merged between $since and $today.
                Your job is NOT to write a plan per PR — instead, read across all of the PRs and produce a single cohesive test plan organized by product area or user-facing feature that was touched.

                Only include areas rated Medium or High risk. Skip anything Low risk entirely — there are a lot of PRs every day and the tester needs a focused, actionable document.

                Structure the output as follows:

                # QA Test Plan – $today

                ## Today's Focus
                2–4 bullet points covering only the highest-risk or most user-visible changes. Keep each bullet to one sentence.

                ## Areas to Test
                Only include Medium and High risk areas. Group related changes by product area or feature (not by PR or repo). For each area:

                ### [Area / Feature Name] — [Medium | High]
                **Related PRs:** comma-separated links

                **What changed:** One or two sentences — what is different from yesterday and why it matters.

                **Test scenarios**
                A tight numbered list (3–5 max) of the most important things to try. One line per scenario: what to do and what to expect. Only call out edge cases if they are genuinely risky.

                **Watch for regressions in:** A single line listing nearby functionality to spot-check.

                ---

                ## Needs Clarification
                PRs with vague titles and no description — list with links only, no explanation needed.

                ---

                Guidelines:
                - Write for someone who knows the product well but is not a developer — no code or technical jargon.
                - Do not create a separate section for every PR. Synthesize and group.
                - Skip PRs whose titles start with "test:", "chore:", "ci:", or are dependency-only bumps.
                - Skip any area you would rate as Low risk — do not include it at all.
                - Be brief. Prefer one clear sentence over a paragraph. This document should be scannable in under 5 minutes.
            """.trimIndent()

            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId("gpt-5"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = prompt
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = "Here are the pull requests merged today. Generate a holistic QA test plan organized by product area, not per PR:\n\n$prContent"
                    )
                )
            )
            val result = StringBuilder()
            openAI.chatCompletions(chatCompletionRequest)
                .onEach {
                    result.append(it.choices.firstOrNull()?.delta?.content.orEmpty())
                }
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
        "cloudflare-workers-and-pages",
        "speedcurve-ci",
        "dependabot[bot]",
        "devin-ai-integration[bot]",
    )

    fun isExcludedUser(username: String): Boolean {
        return excludeBotUsers.contains(username)
    }

    @Serializable
    data class ActivityStats(
        val user: User,
        val total: Int,
        val open: Int,
        val merged: Int,
        val avgDaysOpen: String,
        val medianDaysOpen: String,
        val comments: Int,
        val additions: Int,
        val subtractions: Int,
        val filesChanged: Int
    )

    @Serializable
    data class RepoStats(
        val repo: String,
        val total: Int,
        val open: Int,
        val merged: Int,
        val closed: Int,
        val avgDaysOpen: String,
        val comments: Int,
        val additions: Int,
        val subtractions: Int,
        val filesChanged: Int
    )

    @Serializable
    data class ReviewStats(
        val user: User,
        val prsReviewed: Int,
        val commentsLeft: Int,
        val prsApproved: Int,
        val prsRequestedChanges: Int,
        val reviewsPending: Int
    )

    data class PRDetail(
        val pr: GitHubIssue,
        val comments: List<GraphQLComment>,
        val reviews: List<GraphQLReview>
    )

    @Serializable
    data class PRDetailSimplified(
        val prUrl: String,
        val prTitle: String,
        val reviews: List<String>,
        val comments: List<String>
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
                (open.map { it.daysOpen() } + closed.map { it.daysOpen() }).sorted().let { it.getOrNull(it.size / 2) ?: 0.0 }.format(2),
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
                    closed.count { it.state == State.closed && it.pullRequest?.mergedAt == null },
                    ((open.sumOf { it.daysOpen() } + closed.sumOf { it.daysOpen() }) / prList.size).format(2),
                    open.sumOf { it.comments } + closed.sumOf { it.comments },
                    prList.sumOf { it.moreDetails?.additions ?: 0 },
                    prList.sumOf { it.moreDetails?.deletions ?: 0 },
                    prList.sumOf { it.moreDetails?.changedFiles ?: 0 }
                )
            }.values
        }
    }

    fun computeReviewStatsForUser(prs: List<UserPrs>, user: String): List<PRDetail> {
        val filteredPrs = prs.filter {
            it.user.login != user
        }.flatMap { it.prs }
            .filter { pr ->
                pr.moreDetails?.reviews?.nodes?.any { r -> r.author.login == user } == true || pr.moreDetails?.allComments()
                    ?.any { r -> r.author.login == user } == true
            }
            .map { pr ->
                PRDetail(
                    pr,
                    pr.moreDetails?.allComments()?.filter { r -> r.author.login == user } ?: emptyList(),
                    pr.moreDetails?.reviews?.nodes?.filter { r -> r.author.login == user } ?: emptyList()
                )
            }
        return filteredPrs
    }

    fun computeReviewStatsForTeam(prs: List<UserPrs>): List<ReviewStats> {
        val allReviewsByAuthor = prs.flatMap {
            it.prs.flatMap { issue ->
                issue.moreDetails?.reviews?.nodes?.map { r ->
                    Pair(r, issue)
                } ?: emptyList()
            }
        }.groupBy { it.first.author }
        val allCommentsByAuthor = prs.flatMap {
            it.prs.flatMap { issue ->
                issue.moreDetails?.allComments()?.map { c ->
                    Pair(c, issue)
                } ?: emptyList()
            }
        }.groupBy { it.first.author }

        val authors = allReviewsByAuthor.keys.toSet() + allCommentsByAuthor.keys.toSet()

        return authors.filterNot { excludeBotUsers.contains(it.login) }.map { author ->
            val reviews =
                allReviewsByAuthor[author]?.filter { it.first.author.login != it.second.user.login } ?: emptyList()
            val comments =
                allCommentsByAuthor[author]?.filter { it.first.author.login != it.second.user.login && it.first.bodyText.isNotBlank() }
                    ?: emptyList()

            ReviewStats(
                User(author.login),
                (reviews.map { it.second.number } + comments.map { it.second.number }).distinct().size,
                comments.size + reviews.count { it.first.state == ReviewState.COMMENTED },
                reviews.count { it.first.state == ReviewState.APPROVED },
                reviews.count { it.first.state == ReviewState.CHANGES_REQUESTED },
                reviews.count { it.first.state == ReviewState.PENDING }
            )
        }
    }
}

fun Double.format(digits: Int) = "%.${digits}f".format(this)