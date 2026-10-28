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

@Serializable
data class ChangelogProduct(
    val name: String,
    val audience: String,
    val description: String,
    val repos: List<String>
)

const val KEY_GITHUB_DEFAULT: String = "github_access_key_default"
const val KEY_OPENAI_DEFAULT: String = "openai_api_key_default"
const val KEY_CURSOR_API: String = "cursor_api_key"
const val KEY_BUILDER_API: String = "builder_private_key"
const val KEY_CLAUDE_API: String = "claude_api_key"
const val KEY_CLAUDE_ENTERPRISE_API: String = "claude_enterprise_api_key"

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
                fetchCommentsInDetail: Boolean = false,
                includeBodies: Boolean = true
            ): List<UserPrs> {
                val today = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
                val startDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                    .minus(days, DateTimeUnit.DAY)
                val allResults = githubApi.searchAllPRs(
                    org,
                    team = team,
                    author = author,
                    startDate = startDate,
                    endDate = maxOf(today, startDate)
                )
                val allPRs = if (filterForOrg && org != null) {
                    allResults.filter { pr -> pr.url.lowercase().contains(org.lowercase()) }
                } else {
                    allResults
                }
                val detailUpdate = if (fullDetails) {
                    val prDetails = allPRs.chunked(10).flatMap { chunk ->
                        githubApi.prDetails(chunk, fetchReviewsInDetail, fetchCommentsInDetail, includeBodies).flatMap { req ->
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

        val model by option("--model", envvar = "CHANGELOG_MODEL", help = "OpenAI model used to write the changelog")
            .default("gpt-6.1-sol")

        val glossaryPath by option("--glossary", help = "JSON file mapping repos to the products, audiences and plain-language descriptions readers know them by")

        private val maxPrBodyChars = 8_000
        private val maxChunkChars = 2_200_000

        override fun run() {
            val today = if (startDate != null) {
                LocalDate.parse(startDate!!)
            } else {
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            }
            val since = today.minus(DateTimeUnit.DayBased(days))
            val githubApi = GitHubApi()

            val mergedPrs = runBlocking {
                githubApi.searchAllPRs(
                    org,
                    startDate = since,
                    endDate = if (startDate != null) today else Clock.System.now().toLocalDateTime(TimeZone.UTC).date,
                    mergedOnly = true,
                    repo = repo
                )
            }

            val products = glossaryPath?.let { loadGlossary(File(it)) }.orEmpty()
            val productsByRepo = products.flatMap { p -> p.repos.map { it.lowercase() to p } }.toMap()
            val unmappedRepos = mutableSetOf<String>()

            // Group by product (not repo) so everything a reader knows as one product reads together.
            val changelogEntries = mergedPrs
                .map { pr -> pr to repoName(pr.repositoryUrl) }
                .map { (pr, name) ->
                    val product = productsByRepo[name.lowercase()]
                    if (product == null) unmappedRepos += name
                    Triple(pr, name, product?.name ?: if (products.isEmpty()) name else "Other ($name)")
                }
                .sortedBy { it.third }
                .map { (pr, _, productName) ->
                    val trimmedBody = pr.body.orEmpty().take(maxPrBodyChars)
                    "<pr><product>$productName</product><title>${pr.title}</title><body>${trimmedBody}</body><link>${pr.htmlUrl}</link></pr>"
                }

            if (products.isNotEmpty() && unmappedRepos.isNotEmpty()) {
                System.err.println("WARNING: no glossary entry for repos: ${unmappedRepos.sorted().joinToString(", ")} (add them to the glossary file)")
            }
            val activeProducts = products.filter { p -> p.repos.any { r -> mergedPrs.any { repoName(it.repositoryUrl).equals(r, ignoreCase = true) } } }
            println("Got ${mergedPrs.size} merged PRs to consider for changelog")

            val openAi = OpenAI(Settings().getString(KEY_OPENAI_DEFAULT, ""), logging = LoggingConfig(LogLevel.None), timeout = Timeout(5.minutes, 2.minutes, 5.minutes))
            val output = runBlocking {
                chat(openAi, changelogEntries, activeProducts)
            }
            File("changelog_${since}_${today}.md").writeText(output)
            githubApi.close()
        }

        // "https://api.github.com/repos/BuilderIO/ai-services" -> "BuilderIO/ai-services"
        private fun repoName(repositoryUrl: String): String =
            repositoryUrl.removePrefix("https://api.github.com/repos/")

        private fun loadGlossary(file: File): List<ChangelogProduct> =
            Json { ignoreUnknownKeys = true }.decodeFromString<List<ChangelogProduct>>(file.readText())

        private fun glossarySection(products: List<ChangelogProduct>): String {
            if (products.isEmpty()) return ""
            val entries = products.joinToString("\n") { "- ${it.name} (${it.audience}): ${it.description}" }
            return """

                Each <pr> has a <product> tag. These are the products and systems readers know, and what each one is. Use these exact names as section headings and never mention repository names:
                $entries
            """.trimIndent()
        }

        suspend fun chat(openAI: OpenAI, changelogEntries: List<String>, products: List<ChangelogProduct>): String {
            val prompt = if (persona == "marketing") {
                "You are a product marketing manager trying to create a public facing changelog from engineering pull request titles and descriptions. This needs to be something that customers will understand but they wont have intimate knowledge of the details. You need to decide from the list of PR details, each surrounded with <pr></pr> tags, how to best summarize the all of the PRs per repo in a changelog format. You should not necessarily have one line per PR. The items should summarize for a customer what has changed and what they can expect. Dont go into too many details about PRs that are labelled as fixes and where possible combine those together more generally in a fixes line. When generating summaries, if possible include links to all of the PRs that went into generating that summary, the link to the PR is in the <link> tag for each PR in the input. Ignore ones with 'test:' or 'chore:' in the title" + glossarySection(products)
            } else {
                """
                    You write the weekly changelog for Builder.io employees who are not engineers: sales, support, customer success, marketing and leadership.
                    They know our products by the names customers see. They do not know repository names, code, or technical terms, and they read this to answer
                    "what changed, who does it affect, and what can I tell a customer?"

                    You get details about each PR in a structured format, each surrounded with <pr></pr> tags. Summarize them into a changelog with this structure:
                    - One `##` section per product, using the exact product name from the <product> tag. Put customer-facing products first, then internal tools and systems.
                    - Under each product, group items under these headings when they apply: `### New`, `### Improved`, `### Fixed`. Skip empty headings.
                    - Each item starts with a short bold headline in plain words (e.g. `**Disconnect Jira and Slack from the app**`), followed by one or two plain sentences. Lead with what a person can now do or what problem went away,
                      then say who it affects (all customers, a specific customer, a plan, or an internal team). Name a specific customer whenever the PR mentions one and say what changed for them.

                    Writing rules:
                    - Never use repository names, package names, file names, function, flag or environment variable names, ticket IDs, or PR numbers in the text of an item.
                    - Write for a salesperson. Do not use these terms unexplained: MCP, OAuth, API, endpoint, CLI, SDK, token, webhook, cache, latency, feature flag, container, Kubernetes, refactor, schema, migration, regression, model routing.
                      Say what it does for people instead ("sign in with Google", "the command-line tool developers use", "a setting that turns the feature on for some customers"), or name the product it belongs to.
                    - Describe the outcome, not the implementation. "Pages load faster for returning visitors" beats "cached the manifest lookup".
                    - Relevance test: if you cannot say why a customer, a seller or a support person would care in one plain sentence, do not give it its own item. Visual cleanups, internal code reorganization, icon or styling systems,
                      test tooling and AI model plumbing go into the single `Behind the scenes` line for that product, e.g. "Routine maintenance and under-the-hood improvements (6 changes)" with the PR links.
                    - Combine small fixes within a product into one `Fixes and polish` item that gives two or three plain examples.
                    - Skip changes with no visible effect (refactors, dependency bumps, CI, tests, tooling) other than that one `Behind the scenes` line. Ignore PRs with 'test:' or 'chore:' in the title.
                    - If a PR has no body, use the title. If there is not enough information to describe it, list its link at the end under `## Not summarized`.
                    - End each item with its source PRs as `PRs: [link text](url)`, using the link in each <pr>'s <link> tag.
                    - You should not necessarily have one item per PR. Keep each product section short enough to skim.
                """.trimIndent() + glossarySection(products)
            }

            val chunks = changelogEntries.chunkByMaxChars(maxChunkChars)
            val partials = chunks.mapIndexed { index, chunk ->
                val chunkPrompt = """
                    This is chunk ${index + 1} of ${chunks.size}. Summarize only this chunk in markdown.
                    Preserve PR links and group by product.

                    ${chunk.joinToString("\n\n")}
                """.trimIndent()
                streamCompletion(openAI, prompt, chunkPrompt)
            }

            if (partials.size == 1) {
                return partials.first()
            }

            val synthesisPrompt = """
                Merge the chunk summaries into one final changelog.
                Deduplicate overlapping themes and keep PR links.
                Group output by product, keep the same section structure, and keep it concise and in plain language.

                ${partials.joinToString("\n\n")}
            """.trimIndent()

            return streamCompletion(openAI, prompt, synthesisPrompt)
        }

        private suspend fun streamCompletion(openAI: OpenAI, systemPrompt: String, userPrompt: String): String {
            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId(model),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = systemPrompt
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = userPrompt
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

        private fun List<String>.chunkByMaxChars(maxChars: Int): List<List<String>> {
            if (this.isEmpty()) return emptyList()

            val chunks = mutableListOf<MutableList<String>>()
            var current = mutableListOf<String>()
            var currentChars = 0

            for (entry in this) {
                val entryChars = entry.length + 2
                if (current.isNotEmpty() && currentChars + entryChars > maxChars) {
                    chunks += current
                    current = mutableListOf()
                    currentChars = 0
                }
                current += entry
                currentChars += entryChars
            }

            if (current.isNotEmpty()) {
                chunks += current
            }

            return chunks
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
                You are a senior QA engineer creating a concise daily test plan for a 2-person manual QA team.
                You will be given a list of pull requests merged between $since and $today.
                Your job is NOT to write a plan per PR — read across all PRs and produce a single cohesive plan split into two product sections.

                The team has exactly 2 people, one per product. Capacity is limited to roughly a day of testing each.
                Be ruthless about prioritization: only surface what genuinely needs human eyes today. Skip anything Low risk entirely.

                --- PRODUCT DEFINITIONS ---

                **Fusion** — changes from the ai-services repo, plus anything in builder-internal touching: projects, branches. Ignore the electron app and the vscode plugin for now as those need releases which may not have happened yet.

                **Publish** — everything else in builder-internal: content API, plugins, subscriptions, user management, payments, content entries, content model, assets, and other CMS features.

                If a PR clearly belongs to one product, put it there. If it's shared infrastructure that affects both, mention it briefly in both sections.

                --- OUTPUT FORMAT ---

                # QA Test Plan – $today

                ---

                ## 🔷 Fusion

                ### Priority Focus
                1–3 bullets max — the single most important thing(s) to verify today for Fusion. One sentence each.

                ### Areas to Test
                Pick the top 3 most important areas — no more. Only Medium and High risk. Group by feature, not by PR. For each area:

                #### [Feature / Area Name] — [Medium | High]
                **PRs:** links
                **What changed:** 1–2 sentences.
                **Scenarios:** numbered list, 2–4 items max, one line each.
                **Watch for:** one line of nearby functionality to spot-check.

                ---

                ## 🔶 Publish

                ### Priority Focus
                1–3 bullets max — the single most important thing(s) to verify today for Publish. One sentence each.

                ### Areas to Test
                Pick the top 3 most important areas — no more. Only Medium and High risk. Group by feature, not by PR. For each area:

                #### [Feature / Area Name] — [Medium | High]
                **PRs:** links
                **What changed:** 1–2 sentences.
                **Scenarios:** numbered list, 2–4 items max, one line each.
                **Watch for:** one line of nearby functionality to spot-check.

                ---

                ## Needs Clarification
                PRs with vague titles and no description — links only.

                --- GUIDELINES ---
                - Write for testers who know the product but are not developers. No code or jargon.
                - Do not create a section for every PR. Synthesize and group aggressively.
                - Hard limit: maximum 3 areas per product section. If there are more candidates, pick the 3 highest risk and drop the rest.
                - Skip "test:", "chore:", "ci:", and dependency-bump PRs entirely.
                - Skip any Low risk area — do not mention it at all.
                - If a product had no meaningful changes today, write "No significant changes today." under that product and move on.
                - The whole document should be scannable in under 5 minutes. Brevity is a feature.
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