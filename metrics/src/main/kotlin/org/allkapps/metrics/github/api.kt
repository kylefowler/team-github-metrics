package org.allkapps.metrics.github

import com.russhwolf.settings.Settings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.allkapps.metrics.commands.KEY_GITHUB_DEFAULT
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun createGitHubClient(): HttpClient {
    val token = Settings().getString(KEY_GITHUB_DEFAULT, "")
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

        // Large GraphQL queries and search pages can take well over OkHttp's 10s default.
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
            requestTimeoutMillis = 300_000
        }

        // Retry timeouts, 5xx, and GitHub rate limiting (primary/secondary) with backoff.
        install(HttpRequestRetry) {
            maxRetries = 4
            retryOnExceptionIf { _, cause -> cause is HttpRequestTimeoutException || cause is java.io.IOException }
            retryIf { _, response ->
                response.status.value in 500..599 ||
                    response.status == HttpStatusCode.TooManyRequests ||
                    (response.status == HttpStatusCode.Forbidden && response.headers["Retry-After"] != null)
            }
            exponentialDelay(base = 2.0, maxDelayMs = 60_000, respectRetryAfterHeader = true)
        }

        install(Auth) {
            bearer {
                loadTokens {
                    BearerTokens(accessToken = token, refreshToken = "")
                }
            }
        }

        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.github.com"
                header(HttpHeaders.Accept, "application/vnd.github.v3+json")
            }
        }
    }
}

class GitHubApi {
    val client = createGitHubClient()

    fun close() {
        client.close()
    }

    suspend fun ratelimit(): String {
        return client.get("/rate_limit").body<String>()
    }

    @Serializable
    data class GraphQLRequest(val query: String)

    @Serializable
    data class GraphQLResponse<T>(val data: T)

    suspend inline fun <reified T> graphql(query: String): GraphQLResponse<T> {
        return client.post("/graphql") {
            headers {
                append("Content-Type", "application/json")
            }
            setBody(GraphQLRequest(query))
        }.body<GraphQLResponse<T>>()
    }

    private fun parseRepoUrlRegex(url: String): Pair<String, String> {
        val parts = url.removePrefix("https://api.github.com/").split("/")
        return Pair(parts[1], parts[2])
    }

    // Limits concurrent GraphQL requests so a large window doesn't trip secondary rate limits or timeouts.
    private val graphqlPermits = Semaphore(4)

    suspend fun prDetails(
        prs: List<GitHubIssue>,
        includeReviews: Boolean = false,
        includeComments: Boolean = false,
        includeBodies: Boolean = true
    ): List<GraphQLResponse<Map<String, Map<String, GraphQLPullRequestNode>>>> = coroutineScope {

        val repos = prs.groupBy { it.repositoryUrl }.mapValues { (k, v) ->
            v.mapIndexed { index, gitHubIssue ->
                "pullRequest${index + 1}: pullRequest(number: ${gitHubIssue.number}) { number additions deletions changedFiles totalCommentsCount title author { login } url ${if (includeComments) comments(includeBodies) else ""} ${if (includeComments) reviewThreads else ""} ${if (includeReviews) reviews(includeBodies) else ""} }"
            }.joinToString("\n")
        }
        val fullQuery = repos.entries.mapIndexed { index, entry ->
            val (org, repoName) = parseRepoUrlRegex(entry.key)
            val repoQuery = "repo${index + 1}: repository(owner:\"$org\", name: \"$repoName\") { \n ${entry.value} \n}"
            async { graphqlPermits.withPermit { graphql<Map<String, Map<String, GraphQLPullRequestNode>>>("query { $repoQuery }") } }
        }
        fullQuery.awaitAll()
    }

    suspend fun searchPRs(
        org: String?,
        team: String? = null,
        author: String? = null,
        startDate: LocalDate? = null,
        page: Int = 1,
        perPage: Int = 100,
        mergedOnly: Boolean = false,
        endDate: LocalDate? = null,
        repo: String? = null
    ): HttpResponse {
        val queryTerms = mutableListOf<String>().apply {
            add("is:pr")
            if (org != null && repo == null) {
                add("org:$org")
            }
            if (repo != null) {
                add("repo:$org/$repo")
            }
            if (team != null && org != null && author == null) {
                val teamMembers = getTeamMembers(org, team)
                teamMembers.forEach {
                    add("author:${it.login}")
                }
            }
            if (author != null) {
                add("author:$author")
            }

            val createdOrMerged = if (mergedOnly) "merged" else "created"
            if (startDate != null && endDate != null) {
                add("$createdOrMerged:$startDate..$endDate")
            } else if (startDate != null) {
                add("$createdOrMerged:$startDate..*")
            } else if (endDate != null) {
                add("$createdOrMerged:<$endDate")
            }
            if (mergedOnly) {
                add("is:merged")
            }
        }
        return client.get("/search/issues") {
            url {
                parameters.apply {
                    append("q", queryTerms.joinToString(" "))
                    // Default "best match" ordering isn't stable between pages, which drops and duplicates results.
                    append("sort", "created")
                    append("order", "asc")
                    append("page", page.toString())
                    append("per_page", perPage.toString())
                }
            }
        }
    }

    /**
     * Search results are capped at 1000 per query, so a wide window is bisected by creation date
     * until every slice fits, then each slice is paged through and the results are merged.
     */
    suspend fun searchAllPRs(
        org: String?,
        team: String? = null,
        author: String? = null,
        startDate: LocalDate,
        endDate: LocalDate,
        perPage: Int = 100,
        mergedOnly: Boolean = false,
        repo: String? = null
    ): List<GitHubIssue> {
        val results = LinkedHashMap<String, GitHubIssue>()
        var expected = 0

        suspend fun fetchPage(start: LocalDate, end: LocalDate, page: Int): GithubIssueSearch {
            var result = searchPRs(org, team = team, author = author, startDate = start, endDate = end, page = page, perPage = perPage, mergedOnly = mergedOnly, repo = repo)
                .body<GithubIssueSearch>()
            var attempts = 1
            while (result.incompleteResults && attempts < 3) {
                delay(5_000L * attempts)
                result = searchPRs(org, team = team, author = author, startDate = start, endDate = end, page = page, perPage = perPage, mergedOnly = mergedOnly, repo = repo)
                    .body<GithubIssueSearch>()
                attempts++
            }
            return result
        }

        suspend fun searchRange(start: LocalDate, end: LocalDate) {
            val first = fetchPage(start, end, 1)
            if (first.totalCount > SEARCH_RESULT_CAP) {
                if (start < end) {
                    val mid = start.plus(start.daysUntil(end) / 2, DateTimeUnit.DAY)
                    searchRange(start, mid)
                    searchRange(mid.plus(1, DateTimeUnit.DAY), end)
                    return
                }
                System.err.println("WARNING: ${first.totalCount} PRs created on $start exceeds the search cap of $SEARCH_RESULT_CAP; results for this day are truncated")
            }
            expected += minOf(first.totalCount, SEARCH_RESULT_CAP)
            first.items.forEach { results[it.htmlUrl] = it }
            val pages = (minOf(first.totalCount, SEARCH_RESULT_CAP) + perPage - 1) / perPage
            for (page in 2..pages) {
                fetchPage(start, end, page).items.forEach { results[it.htmlUrl] = it }
            }
        }

        searchRange(startDate, endDate)
        // New PRs created mid-run only add to the end, so fewer results than GitHub reported means pages were dropped.
        check(results.size >= expected * 0.99) {
            "PR search returned ${results.size} of $expected results for ${team ?: author ?: org} ($startDate..$endDate)"
        }
        return results.values.toList()
    }

    private val teamMembersCache = mutableMapOf<String, List<User>>()

    // Cached because searchPRs is called once per page/date slice; paginated so teams over 30 members aren't cut off.
    suspend fun getTeamMembers(org: String, team: String): List<User> {
        return teamMembersCache.getOrPut("$org/$team") {
            val members = mutableListOf<User>()
            var page = 1
            while (true) {
                val batch = client.get("/orgs/$org/teams/$team/members") {
                    url {
                        parameters.append("per_page", "100")
                        parameters.append("page", page.toString())
                    }
                }.body<List<User>>()
                members.addAll(batch)
                if (batch.size < 100) break
                page++
            }
            members
        }
    }

    suspend fun getRepos(org: String): List<Repository> {
        return client.get("/orgs/$org/repos") {
            url {
                parameters.append("sort", "updated")
            }
        }.body()
    }

    suspend fun getCommitsForRepo(org: String, repo: String, since: LocalDate): List<Commit> {
        return client.get("/repos/$org/$repo/commits") {
            url {
                parameters.append("since", since.toString())
            }
        }.body()
    }

    companion object {
        private const val SEARCH_RESULT_CAP = 1000

        internal fun comments(includeBody: Boolean = true) = """
            comments(first: 100) {
        nodes {
          author {
            login
          }
          ${if (includeBody) "body" else ""}
          createdAt
        }
      }
        """.trimIndent()

        internal fun reviews(includeBody: Boolean = true) = """
            reviews(first: 100) {
        nodes {
          author {
            login
          }
          ${if (includeBody) "body" else ""}
          state
          createdAt
        }
      }
        """.trimIndent()

        internal val reviewThreads = """
            reviewThreads(first: 50) {
        nodes {
              comments(first: 10) {
                nodes {
                  id
                  author {
                    login
                  }
                  body
                  createdAt
                  bodyText
                }
              }
            }
      }
        """.trimIndent()
    }
}