package com.example.ApI.data.network

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ApI.data.model.GoogleWorkspaceAuth
import com.example.ApI.data.model.GoogleWorkspaceUser
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.auth.GoogleAuthUtil
import android.accounts.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Service for handling Google Workspace authentication using Google Sign-In SDK
 */
class GoogleWorkspaceAuthService(private val context: Context) {

    companion object {
        private const val TAG = "GoogleWorkspaceAuth"

        // OAuth 2.0 Client ID from Google Cloud Console
        // OAuth 2.0 Web Client ID (Required for requestIdToken/requestServerAuthCode)
        private const val CLIENT_ID = "926212364522-f5ppm0o9cj95te2rerborkdg62e8qlc4.apps.googleusercontent.com"

        // OAuth Scopes for Google Workspace APIs
        const val SCOPE_GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
        const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }

    private var googleSignInClient: GoogleSignInClient? = null

    /**
     * Get the configured GoogleSignInClient
     */
    private fun getGoogleSignInClient(): GoogleSignInClient {
        if (googleSignInClient == null) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(CLIENT_ID)
                .requestEmail()
                .requestProfile()
                .requestScopes(
                    Scope(SCOPE_GMAIL_MODIFY),
                    Scope(SCOPE_CALENDAR),
                    Scope(SCOPE_DRIVE_FILE)
                )
                .requestServerAuthCode(CLIENT_ID, true) // Request server auth code for token refresh
                .build()

            googleSignInClient = GoogleSignIn.getClient(context, gso)
        }
        return googleSignInClient!!
    }

    /**
     * Get sign-in intent to launch Google account picker
     * @return Intent to start for result
     */
    fun getSignInIntent(): Intent {
        Log.d(TAG, "Creating sign-in intent")
        return getGoogleSignInClient().signInIntent
    }

    /**
     * Handle sign-in result from the sign-in intent
     * @param data Intent data from onActivityResult
     * @return Result containing GoogleWorkspaceAuth and User info, or error
     */
    suspend fun handleSignInResult(data: Intent): Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> = withContext(Dispatchers.IO) {
        try {
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            if (account == null) {
                return@withContext Result.failure(Exception("Sign-in result is null"))
            }

            Log.d(TAG, "Sign-in successful for ${account.email}")

            // Get proper OAuth 2.0 access token using GoogleAuthUtil
            // The ID token from the intent is NOT sufficient for calling Gmail/Drive APIs
            val email = account.email ?: throw Exception("Email not found in Google account")
            val accountObj = account.account ?: Account(email, "com.google")
            val scopes = "oauth2:$SCOPE_GMAIL_MODIFY $SCOPE_CALENDAR $SCOPE_DRIVE_FILE"
            
            val accessToken = try {
                Log.d(TAG, "Attempting to get OAuth2 token for scopes: $scopes")
                val token = GoogleAuthUtil.getToken(context, accountObj, scopes)
                Log.d(TAG, "Successfully retrieved token via GoogleAuthUtil")
                token
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get access token via GoogleAuthUtil", e)
                // Fallback to idToken if available, though it might not work for all APIs
                if (account.idToken != null) {
                    Log.w(TAG, "Falling back to ID token")
                    account.idToken!!
                } else {
                    Log.e(TAG, "No ID token available for fallback")
                    throw e
                }
            }

            Log.d(TAG, "Retrieved access token: ${accessToken.take(10)}...")

            // Get server auth code for refresh token if available
            val serverAuthCode = account.serverAuthCode
            Log.d(TAG, "Server auth code: ${if (serverAuthCode != null) "present" else "not present"}")

            // Extract user info
            val user = GoogleWorkspaceUser(
                id = account.id ?: "",
                email = account.email ?: "",
                displayName = account.displayName,
                photoUrl = account.photoUrl?.toString()
            )

            // Calculate token expiration (typically 1 hour for Google tokens)
            val expiresAt = System.currentTimeMillis() + (3600 * 1000)

            // Get granted scopes
            val grantedScopes = account.grantedScopes.map { it.scopeUri }
            Log.d(TAG, "Granted scopes: $grantedScopes")

            val auth = GoogleWorkspaceAuth(
                accessToken = accessToken,
                refreshToken = serverAuthCode, // Server auth code can be used to get refresh token
                expiresAt = expiresAt,
                scopes = grantedScopes,
                createdAt = System.currentTimeMillis()
            )

            Result.success(Pair(auth, user))
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed with status code: ${e.statusCode}", e)
            Result.failure(Exception("Sign-in failed: ${e.localizedMessage}"))
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sign-in result", e)
            Result.failure(e)
        }
    }

    /**
     * Get the currently signed-in account, if any
     * @return GoogleSignInAccount or null
     */
    fun getLastSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    /**
     * Check if user is currently signed in
     */
    fun isSignedIn(): Boolean {
        return getLastSignedInAccount() != null
    }

    /**
     * Get fresh access token for the signed-in user
     * This will automatically refresh the token if needed
     * @return Access token or null
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        try {
            val account = getLastSignedInAccount() ?: return@withContext null

            // Return the ID token from the last signed-in account
            val token = account.idToken

            Log.d(TAG, "Retrieved access token: ${if (token != null) "present" else "null"}")
            token
        } catch (e: Exception) {
            Log.e(TAG, "Error getting access token", e)
            null
        }
    }

    /**
     * Refresh access token using refresh token
     * Note: Google Sign-In SDK handles token refresh automatically
     * This method is kept for compatibility but delegates to getAccessToken()
     */
    suspend fun refreshToken(refreshToken: String): Result<GoogleWorkspaceAuth> = withContext(Dispatchers.IO) {
        try {
            val account = getLastSignedInAccount()
                ?: return@withContext Result.failure(Exception("No signed-in account"))

            val newAccessToken = getAccessToken()
                ?: return@withContext Result.failure(Exception("Failed to refresh token"))

            val expiresAt = System.currentTimeMillis() + (3600 * 1000)
            val grantedScopes = account.grantedScopes.map { it.scopeUri }

            val auth = GoogleWorkspaceAuth(
                accessToken = newAccessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                scopes = grantedScopes,
                createdAt = System.currentTimeMillis()
            )

            Log.d(TAG, "Token refreshed successfully")
            Result.success(auth)
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            Result.failure(e)
        }
    }

    /**
     * Sign out and disconnect the Google account
     */
    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Signing out")
            getGoogleSignInClient().signOut().await()
            Log.d(TAG, "Sign-out successful")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing out", e)
            Result.failure(e)
        }
    }

    /**
     * Revoke access and disconnect the app from the Google account
     * This removes all permissions and requires re-authentication
     */
    suspend fun revokeAccess(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Revoking access")
            getGoogleSignInClient().revokeAccess().await()
            Log.d(TAG, "Access revoked successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error revoking access", e)
            Result.failure(e)
        }
    }

    /**
     * Check if all required scopes are granted
     */
    fun hasRequiredScopes(): Boolean {
        val account = getLastSignedInAccount() ?: return false
        val grantedScopes = account.grantedScopes.map { it.scopeUri }

        val requiredScopes = listOf(SCOPE_GMAIL_MODIFY, SCOPE_CALENDAR, SCOPE_DRIVE_FILE)
        return requiredScopes.all { it in grantedScopes }
    }

    /**
     * Get user info from the current signed-in account
     */
    fun getUserInfo(): GoogleWorkspaceUser? {
        val account = getLastSignedInAccount() ?: return null

        return GoogleWorkspaceUser(
            id = account.id ?: "",
            email = account.email ?: "",
            displayName = account.displayName,
            photoUrl = account.photoUrl?.toString()
        )
    }
}
