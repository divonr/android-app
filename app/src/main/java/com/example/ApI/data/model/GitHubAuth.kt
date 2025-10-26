package com.example.ApI.data.model

import kotlinx.serialization.Serializable

/**
 * GitHub OAuth authentication data
 */
@Serializable
data class GitHubAuth(
    val accessToken: String,
    val tokenType: String = "bearer",
    val scope: String,
    val expiresIn: Long? = null,
    val refreshToken: String? = null,
    val refreshTokenExpiresIn: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Check if the access token is expired
     */
    fun isExpired(): Boolean {
        if (expiresIn == null) return false
        val expirationTime = createdAt + (expiresIn * 1000)
        return System.currentTimeMillis() >= expirationTime
    }

    /**
     * Check if the refresh token is expired
     */
    fun isRefreshTokenExpired(): Boolean {
        if (refreshToken == null || refreshTokenExpiresIn == null) return true
        val expirationTime = createdAt + (refreshTokenExpiresIn * 1000)
        return System.currentTimeMillis() >= expirationTime
    }
}

/**
 * GitHub user information
 */
@Serializable
data class GitHubUser(
    val login: String,
    val id: Long,
    val nodeId: String,
    val avatarUrl: String,
    val gravatarId: String?,
    val url: String,
    val htmlUrl: String,
    val name: String?,
    val company: String?,
    val blog: String?,
    val location: String?,
    val email: String?,
    val bio: String?,
    val publicRepos: Int,
    val publicGists: Int,
    val followers: Int,
    val following: Int,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Complete GitHub connection state for a user
 */
@Serializable
data class GitHubConnection(
    val auth: GitHubAuth,
    val user: GitHubUser,
    val connectedAt: Long = System.currentTimeMillis()
)

/**
 * GitHub OAuth configuration
 */
data class GitHubOAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val scopes: List<String> = listOf(
        "repo", // Full control of private repositories
        "read:user", // Read user profile data
        "user:email", // Access user email addresses
        "read:org" // Read org and team membership
    )
) {
    companion object {
        // GitHub OAuth URLs
        const val AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

        // Default redirect URI for the app
        const val DEFAULT_REDIRECT_URI = "chatapi://github-oauth-callback"
    }

    /**
     * Generate the authorization URL for OAuth flow
     */
    fun getAuthorizationUrl(state: String): String {
        val scopeString = scopes.joinToString(",")
        return "$AUTHORIZE_URL?client_id=$clientId&redirect_uri=$redirectUri&scope=$scopeString&state=$state"
    }
}
