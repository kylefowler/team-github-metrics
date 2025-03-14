package org.allkapps.metrics.github

import kotlinx.datetime.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue

@Serializable
data class GithubIssueSearch(
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("incomplete_results") val incompleteResults: Boolean = false,
    val items: List<GitHubIssue> = emptyList()
)

@Serializable
enum class State {
    open,
    closed
}

@Serializable
data class GitHubIssue(
    val url: String,
    @SerialName("repository_url") val repositoryUrl: String,
    @SerialName("labels_url") val labelsUrl: String,
    @SerialName("comments_url") val commentsUrl: String,
    @SerialName("events_url") val eventsUrl: String,
    @SerialName("html_url") val htmlUrl: String,
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val number: Int,
    val title: String,
    val user: User,
    val labels: List<Label> = emptyList(),
    val state: State,
    val locked: Boolean,
    val assignee: Assignee? = null,
    val assignees: List<Assignee> = emptyList(),
    val milestone: Milestone? = null,
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
    @SerialName("closed_at") val closedAt: Instant? = null,
    @SerialName("author_association") val authorAssociation: String,
    @SerialName("active_lock_reason") val activeLockReason: String? = null,
    val draft: Boolean,
    @SerialName("pull_request") val pullRequest: PullRequest? = null,
    val body: String? = null,
    val reactions: Reactions,
    @SerialName("timeline_url") val timelineUrl: String,
    @SerialName("performed_via_github_app") val performedViaGithubApp: String? = null,
    @SerialName("state_reason") val stateReason: String? = null,
    val score: Double,
    val moreDetails: GraphQLPullRequestNode? = null
) {
    fun daysOpen(): Double {
        return if (closedAt != null) {
            createdAt.until(closedAt, DateTimeUnit.HOUR, TimeZone.currentSystemDefault()).absoluteValue / 24.0
        } else {
            createdAt.until(Clock.System.now(), DateTimeUnit.HOUR, TimeZone.currentSystemDefault()).absoluteValue / 24.0
        }
    }
}

@Serializable
data class GraphQLAuthor(val login: String)

@Serializable
data class GraphQLComment(val author: GraphQLAuthor, val body: String = "", val bodyText: String = "", val createdAt: Instant)

@Serializable
enum class ReviewState {
    PENDING,
    COMMENTED,
    APPROVED,
    CHANGES_REQUESTED,
    DISMISSED
}

@Serializable
data class GraphQLReview(val author: GraphQLAuthor, val body: String = "", val createdAt: Instant, val state: ReviewState)

@Serializable
data class GraphQLThread(val comments: GraphQLNodesList<GraphQLComment>)

@Serializable
data class GraphQLPullRequestNode(
    val number: Int,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val totalCommentsCount: Int,
    val title: String,
    val author: GraphQLAuthor,
    val url: String,
    val comments: GraphQLNodesList<GraphQLComment> = GraphQLNodesList(),
    val reviews: GraphQLNodesList<GraphQLReview> = GraphQLNodesList(),
    val reviewThreads: GraphQLNodesList<GraphQLThread> = GraphQLNodesList(),
) {
    fun allComments(): List<GraphQLComment> {
        return comments.nodes + reviewThreads.nodes.flatMap { it.comments.nodes }
    }
}

@Serializable
data class GraphQLNodesList<T>(
    val nodes: List<T> = emptyList()
)

@Serializable
data class User(
    val login: String,
    val id: Long = 0L,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("gravatar_id") val gravatarId: String = "",
    val url: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("followers_url") val followersUrl: String = "",
    @SerialName("following_url") val followingUrl: String = "",
    @SerialName("gists_url") val gistsUrl: String = "",
    @SerialName("starred_url") val starredUrl: String = "",
    @SerialName("subscriptions_url") val subscriptionsUrl: String = "",
    @SerialName("organizations_url") val organizationsUrl: String = "",
    @SerialName("repos_url") val reposUrl: String = "",
    @SerialName("events_url") val eventsUrl: String = "",
    @SerialName("received_events_url") val receivedEventsUrl: String = "",
    val type: String = "",
    @SerialName("site_admin") val siteAdmin: Boolean = false
)

@Serializable
data class Label(
    val id: Long,
    val name: String,
    val color: String,
    val default: Boolean
)

@Serializable
data class Assignee(
    val login: String,
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    @SerialName("avatar_url") val avatarUrl: String,
    @SerialName("gravatar_id") val gravatarId: String,
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("followers_url") val followersUrl: String,
    @SerialName("following_url") val followingUrl: String,
    @SerialName("gists_url") val gistsUrl: String,
    @SerialName("starred_url") val starredUrl: String,
    @SerialName("subscriptions_url") val subscriptionsUrl: String,
    @SerialName("organizations_url") val organizationsUrl: String,
    @SerialName("repos_url") val reposUrl: String,
    @SerialName("events_url") val eventsUrl: String,
    @SerialName("received_events_url") val receivedEventsUrl: String,
    val type: String,
    @SerialName("site_admin") val siteAdmin: Boolean
)

@Serializable
data class Milestone(
    val url: String,
    val htmlUrl: String,
    val labelsUrl: String,
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val number: Int,
    val title: String,
    val description: String,
    val creator: User,
    @SerialName("open_issues") val openIssues: Int,
    @SerialName("closed_issues") val closedIssues: Int,
    val state: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
    @SerialName("due_on") val dueOn: String,
    @SerialName("closed_at") val closedAt: Instant? = null
)

@Serializable
data class PullRequest(
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("diff_url") val diffUrl: String,
    @SerialName("patch_url") val patchUrl: String,
    @SerialName("merged_at") val mergedAt: Instant? = null
)

@Serializable
data class Reactions(
    val url: String? = null,
    @SerialName("total_count") val totalCount: Int = 0,
    @SerialName("+1") val plusOne: Int = 0,
    @SerialName("-1") val minusOne: Int = 0,
    val laugh: Int = 0,
    val hooray: Int = 0,
    val confused: Int = 0,
    val heart: Int = 0,
    val rocket: Int = 0,
    val eyes: Int = 0
)

@Serializable
data class Repository(
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val owner: User,
    val private: Boolean,
    @SerialName("html_url") val htmlUrl: String,
    val description: String,
    val fork: Boolean,
    val url: String,
    @SerialName("forks_url") val forksUrl: String,
    @SerialName("keys_url") val keysUrl: String,
    @SerialName("collaborators_url") val collaboratorsUrl: String,
    @SerialName("teams_url") val teamsUrl: String,
    @SerialName("hooks_url") val hooksUrl: String,
    @SerialName("issue_events_url") val issueEventsUrl: String,
    @SerialName("events_url") val eventsUrl: String,
    @SerialName("assignees_url") val assigneesUrl: String,
    @SerialName("branches_url") val branchesUrl: String,
    @SerialName("tags_url") val tagsUrl: String,
    @SerialName("blobs_url") val blobsUrl: String,
    @SerialName("git_tags_url") val gitTagsUrl: String,
    @SerialName("git_refs_url") val gitRefsUrl: String,
    @SerialName("trees_url") val treesUrl: String,
    @SerialName("statuses_url") val statusesUrl: String,
    @SerialName("languages_url") val languagesUrl: String,
    @SerialName("stargazers_url") val stargazersUrl: String,
    @SerialName("contributors_url") val contributorsUrl: String,
    @SerialName("subscribers_url") val subscribersUrl: String,
    @SerialName("subscription_url") val subscriptionUrl: String,
    @SerialName("commits_url") val commitsUrl: String,
    @SerialName("git_commits_url") val gitCommitsUrl: String,
    @SerialName("comments_url") val commentsUrl: String,
    @SerialName("issue_comment_url") val issueCommentUrl: String,
    @SerialName("contents_url") val contentsUrl: String,
    @SerialName("compare_url") val compareUrl: String,
    @SerialName("merges_url") val mergesUrl: String,
    @SerialName("archive_url") val archiveUrl: String,
)

@Serializable
data class CommitDetails(
    val author: User,
    val committer: User,
    val message: String,
    val url: String,
    @SerialName("comment_count") val commentCount: Int,
)
@Serializable
data class Commit(
    val sha: String,
    val node_id: String,
    val commit: CommitDetails,
    val url: String,
    val html_url: String,
    val comments_url: String,
    val author: User,
    val committer: User,
)