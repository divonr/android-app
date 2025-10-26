package com.example.ApI

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * Activity that handles the OAuth callback from GitHub
 * This is triggered when GitHub redirects back to the app with the authorization code
 */
class GitHubOAuthCallbackActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GitHubOAuthCallback"
        const val EXTRA_AUTH_CODE = "github_auth_code"
        const val EXTRA_AUTH_STATE = "github_auth_state"
        const val EXTRA_ERROR = "github_error"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the URI that started this activity
        val data: Uri? = intent?.data

        if (data == null) {
            Log.e(TAG, "No data in intent")
            finish()
            return
        }

        Log.d(TAG, "Received callback URI: $data")

        // Extract parameters from the callback URL
        // GitHub OAuth callback format: chatapi://github-oauth-callback?code=...&state=...
        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        val error = data.getQueryParameter("error")
        val errorDescription = data.getQueryParameter("error_description")

        // Handle errors first
        if (error != null) {
            Log.e(TAG, "OAuth error: $error - $errorDescription")
            // Navigate back to main activity with error
            val resultIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ERROR, "$error: $errorDescription")
            }
            startActivity(resultIntent)
            finish()
            return
        }

        // Validate we have the required code and state
        if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
            Log.e(TAG, "Missing code or state parameter")
            val resultIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ERROR, "Invalid OAuth callback: missing parameters")
            }
            startActivity(resultIntent)
            finish()
            return
        }

        Log.d(TAG, "Authorization code received: ${code.take(10)}...")

        // Navigate back to MainActivity with the authorization code
        val resultIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_AUTH_CODE, code)
            putExtra(EXTRA_AUTH_STATE, state)
        }

        startActivity(resultIntent)
        finish()
    }
}
