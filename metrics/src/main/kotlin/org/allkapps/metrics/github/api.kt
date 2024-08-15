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
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.allkapps.metrics.Github
import kotlinx.coroutines.*

fun createGitHubClient(): HttpClient {
    val token = Settings().getString(Github.Config.KEY_GITHUB_DEFAULT, "")
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
            level = LogLevel.INFO
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

val client = createGitHubClient()

class GitHubApi {
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

    suspend fun prDetails(prs: List<GitHubIssue>): List<GraphQLResponse<Map<String, Map<String, GraphQLPullRequestNode>>>> = coroutineScope {
        val repos = prs.groupBy { it.repositoryUrl }.mapValues { (k, v) ->
            v.mapIndexed { index, gitHubIssue ->
                "pullRequest${index + 1}: pullRequest(number: ${gitHubIssue.number}) { number additions deletions changedFiles totalCommentsCount title author { login } url }"
            }.joinToString("\n")
        }
        val fullQuery = repos.entries.mapIndexed { index, entry ->
            val (org, repoName) = parseRepoUrlRegex(entry.key)
            val repoQuery = "repo${index + 1}: repository(owner:\"$org\", name: \"$repoName\") { \n ${entry.value} \n}"
            async { graphql<Map<String, Map<String, GraphQLPullRequestNode>>>("query { $repoQuery }") }
        }
        fullQuery.awaitAll()
    }

    suspend fun searchPRs(
        org: String?,
        team: String? = null,
        author: String? = null,
        startDate: LocalDate? = null,
        page: Int = 1,
        perPage: Int = 100
    ): HttpResponse {
        val queryTerms = mutableListOf<String>().apply {
            add("is:pr")
            if (org != null) {
                add("org:$org")
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
            if (startDate != null) {
                add("created:>${startDate}")
            }
        }
        return client.get("/search/issues") {
            url {
                parameters.apply {
                    append("q", queryTerms.joinToString(" "))
                    append("page", page.toString())
                    append("per_page", perPage.toString())
                }
            }
        }
    }

    suspend fun getTeamMembers(org: String, team: String): List<User> {
        return client.get("/orgs/$org/teams/$team/members").body<List<User>>()
    }
}