package com.example.ApI.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * GitHub Repository
 */
@Serializable
data class GitHubRepository(
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val owner: GitHubOwner,
    val private: Boolean,
    @SerialName("html_url") val htmlUrl: String,
    val description: String?,
    val fork: Boolean,
    val url: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("pushed_at") val pushedAt: String?,
    val size: Int,
    @SerialName("stargazers_count") val stargazersCount: Int,
    @SerialName("watchers_count") val watchersCount: Int,
    val language: String?,
    @SerialName("forks_count") val forksCount: Int,
    @SerialName("open_issues_count") val openIssuesCount: Int,
    @SerialName("default_branch") val defaultBranch: String,
    val permissions: GitHubPermissions? = null
)

/**
 * Repository owner (user or organization)
 */
@Serializable
data class GitHubOwner(
    val login: String,
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    @SerialName("avatar_url") val avatarUrl: String,
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    val type: String // "User" or "Organization"
)

/**
 * Repository permissions
 */
@Serializable
data class GitHubPermissions(
    val admin: Boolean,
    val push: Boolean,
    val pull: Boolean
)

/**
 * GitHub File/Directory Content
 */
@Serializable
data class GitHubContent(
    val type: String, // "file", "dir", "symlink", "submodule"
    val encoding: String? = null, // "base64" for files
    val size: Int,
    val name: String,
    val path: String,
    val content: String? = null, // Base64 encoded for files
    val sha: String,
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("git_url") val gitUrl: String,
    @SerialName("download_url") val downloadUrl: String?
) {
    /**
     * Decode the base64 content to string
     */
    fun getDecodedContent(): String? {
        if (content == null || encoding != "base64") return null
        return try {
            String(android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * GitHub Branch
 */
@Serializable
data class GitHubBranch(
    val name: String,
    val commit: GitHubCommitRef,
    val protected: Boolean
)

/**
 * Commit reference
 */
@Serializable
data class GitHubCommitRef(
    val sha: String,
    val url: String
)

/**
 * GitHub Commit (detailed)
 */
@Serializable
data class GitHubCommit(
    val sha: String,
    @SerialName("node_id") val nodeId: String,
    val commit: GitHubCommitDetail,
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    val author: GitHubUser?,
    val committer: GitHubUser?,
    val parents: List<GitHubCommitRef>
)

/**
 * Commit details
 */
@Serializable
data class GitHubCommitDetail(
    val author: GitHubCommitAuthor,
    val committer: GitHubCommitAuthor,
    val message: String,
    val tree: GitHubTreeRef,
    @SerialName("comment_count") val commentCount: Int = 0
)

/**
 * Commit author/committer
 */
@Serializable
data class GitHubCommitAuthor(
    val name: String,
    val email: String,
    val date: String
)

/**
 * Tree reference
 */
@Serializable
data class GitHubTreeRef(
    val sha: String,
    val url: String
)

/**
 * GitHub Pull Request
 */
@Serializable
data class GitHubPullRequest(
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val number: Int,
    val state: String, // "open", "closed"
    val title: String,
    val body: String?,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("closed_at") val closedAt: String?,
    @SerialName("merged_at") val mergedAt: String?,
    val user: GitHubOwner,
    val head: GitHubPRRef,
    val base: GitHubPRRef,
    val draft: Boolean = false,
    val merged: Boolean = false
)

/**
 * Pull request branch reference
 */
@Serializable
data class GitHubPRRef(
    val label: String,
    val ref: String,
    val sha: String,
    val user: GitHubOwner,
    val repo: GitHubRepository?
)

/**
 * GitHub Issue
 */
@Serializable
data class GitHubIssue(
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val number: Int,
    val title: String,
    val body: String?,
    val state: String, // "open", "closed"
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("closed_at") val closedAt: String?,
    val user: GitHubOwner,
    val labels: List<GitHubLabel>,
    val assignees: List<GitHubOwner>
)

/**
 * GitHub Label
 */
@Serializable
data class GitHubLabel(
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val name: String,
    val color: String,
    val description: String?
)

/**
 * GitHub Reference (branch/tag)
 */
@Serializable
data class GitHubReference(
    val ref: String, // "refs/heads/branch-name" or "refs/tags/tag-name"
    @SerialName("node_id") val nodeId: String,
    val url: String,
    @SerialName("object") val objectData: GitHubReferenceObject
)

/**
 * Reference object
 */
@Serializable
data class GitHubReferenceObject(
    val type: String, // "commit", "tag"
    val sha: String,
    val url: String
)

/**
 * Request to create/update file
 */
@Serializable
data class GitHubCreateUpdateFileRequest(
    val message: String,
    val content: String, // Base64 encoded
    val branch: String,
    val sha: String? = null, // Required for updates
    val committer: GitHubCommitAuthorRequest? = null,
    val author: GitHubCommitAuthorRequest? = null
)

/**
 * Commit author for requests
 */
@Serializable
data class GitHubCommitAuthorRequest(
    val name: String,
    val email: String
)

/**
 * Response from create/update file
 */
@Serializable
data class GitHubCreateUpdateFileResponse(
    val content: GitHubContent?,
    val commit: GitHubCommitDetail
)

/**
 * Request to create a pull request
 */
@Serializable
data class GitHubCreatePullRequestRequest(
    val title: String,
    val body: String?,
    val head: String, // Branch name or username:branch
    val base: String, // Base branch name
    val draft: Boolean = false,
    @SerialName("maintainer_can_modify") val maintainerCanModify: Boolean = true
)

/**
 * Request to create a branch (reference)
 */
@Serializable
data class GitHubCreateReferenceRequest(
    val ref: String, // "refs/heads/branch-name"
    val sha: String // Commit SHA to create branch from
)

/**
 * Request to create an issue
 */
@Serializable
data class GitHubCreateIssueRequest(
    val title: String,
    val body: String?,
    val labels: List<String>? = null,
    val assignees: List<String>? = null
)

/**
 * Code search result
 */
@Serializable
data class GitHubCodeSearchResult(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("incomplete_results") val incompleteResults: Boolean,
    val items: List<GitHubCodeSearchItem>
)

/**
 * Code search item
 */
@Serializable
data class GitHubCodeSearchItem(
    val name: String,
    val path: String,
    val sha: String,
    val url: String,
    @SerialName("git_url") val gitUrl: String,
    @SerialName("html_url") val htmlUrl: String,
    val repository: GitHubRepository,
    val score: Double
)

/**
 * Error response from GitHub API
 */
@Serializable
data class GitHubError(
    val message: String,
    @SerialName("documentation_url") val documentationUrl: String? = null,
    val errors: List<GitHubErrorDetail>? = null
)

/**
 * Detailed error information
 */
@Serializable
data class GitHubErrorDetail(
    val resource: String? = null,
    val field: String? = null,
    val code: String
)
