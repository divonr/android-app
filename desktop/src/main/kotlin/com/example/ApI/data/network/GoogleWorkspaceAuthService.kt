package com.example.ApI.data.network

import android.content.Context
import com.example.ApI.data.model.GoogleWorkspaceAuth
import com.example.ApI.data.model.GoogleWorkspaceUser

/**
 * Google Workspace authentication service.
 * Desktop stub: Google Sign-In SDK is Android-only.
 * On desktop, Google Workspace OAuth is not supported via this service.
 * Users must connect via the Android app and the connection will sync through shared data files.
 */
class GoogleWorkspaceAuthService(private val context: Context) {

    companion object {
        private const val TAG = "GoogleWorkspaceAuth"
        const val SCOPE_GMAIL_MODIFY = "https://www.googleapis.com/auth/gmail.modify"
        const val SCOPE_CALENDAR = "https://www.googleapis.com/auth/calendar"
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }

    /** Not available on desktop. */
    fun getSignInIntent(): Any {
        throw UnsupportedOperationException("Google Sign-In is not supported on desktop. Please connect via the Android app.")
    }

    /** Not available on desktop. */
    suspend fun handleSignInResult(data: Any): Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> {
        return Result.failure(UnsupportedOperationException("Google Sign-In is not supported on desktop."))
    }

    /** Sign out stub. */
    suspend fun signOut() {
        // No-op on desktop
    }
}
