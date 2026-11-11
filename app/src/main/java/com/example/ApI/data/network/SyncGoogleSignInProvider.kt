package com.example.ApI.data.network

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ApI.data.sync.GoogleIdentity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android sign-in provider for the remote sync authentication flow.
 *
 * Uses MINIMAL scopes (ID token + email + profile only) — completely independent
 * from the Workspace integration consent.  Signs out the existing account before
 * returning the intent so the account chooser always appears, allowing the user
 * to switch accounts.
 *
 * Follows the same intent-based pattern as [GoogleWorkspaceAuthService]:
 *  1. Call [getSignInIntent] to obtain an [Intent] to launch via ActivityResultLauncher.
 *  2. Pass the returned [Intent] to [handleSignInResult] to extract a [GoogleIdentity].
 *
 * Reuses the same web client id as [GoogleWorkspaceAuthService.CLIENT_ID]; the
 * literal is defined only once.
 */
class SyncGoogleSignInProvider(private val context: Context) {

    companion object {
        private const val TAG = "SyncGoogleSignIn"
    }

    private var client: GoogleSignInClient? = null

    private fun getClient(): GoogleSignInClient {
        if (client == null) {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(GoogleWorkspaceAuthService.CLIENT_ID)
                .requestEmail()
                .requestProfile()
                // No Workspace scopes (gmail/calendar/drive) — sync only needs identity
                .build()
            client = GoogleSignIn.getClient(context, gso)
        }
        return client!!
    }

    /**
     * Returns a sign-in intent.  Fires a sign-out on the cached client first
     * so the account chooser always appears even if a previous session is cached.
     */
    fun getSignInIntent(): Intent {
        val c = getClient()
        c.signOut() // fire-and-forget; forces account picker on the next launch
        return c.signInIntent
    }

    /**
     * Processes the activity result returned by the sign-in intent and extracts
     * a [GoogleIdentity] containing the Google ID token and the account email.
     */
    suspend fun handleSignInResult(data: Intent): Result<GoogleIdentity> = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
                ?: return@withContext Result.failure(Exception("Sign-in result is null"))

            val idToken = account.idToken
                ?: return@withContext Result.failure(Exception("No ID token returned by Google"))
            val email = account.email
                ?: return@withContext Result.failure(Exception("No email returned by Google"))

            Log.d(TAG, "Sync sign-in succeeded for $email")
            Result.success(GoogleIdentity(idToken = idToken, email = email))
        } catch (e: ApiException) {
            Log.e(TAG, "Sync sign-in failed with status ${e.statusCode}", e)
            Result.failure(Exception("Sign-in failed (code ${e.statusCode})"))
        } catch (e: Exception) {
            Log.e(TAG, "Sync sign-in error", e)
            Result.failure(e)
        }
    }
}
