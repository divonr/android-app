package com.example.ApI.data.sync

/**
 * Google identity returned after a successful platform sign-in.
 *
 * @param idToken  A Google-issued ID token (JWT) that the client posts to
 *                 `POST {serverBaseUrl}/auth/google` to obtain a server-minted
 *                 session token.  Valid for ~1 hour; not stored long-term.
 * @param email    The Google account's primary email — used for display only
 *                 before the server confirms the canonical [username].
 */
data class GoogleIdentity(val idToken: String, val email: String)

/**
 * Platform-specific sign-in abstraction.
 *
 * Implementations:
 * - **Android**: `SyncGoogleSignInProvider` (Step 3) — reuses the existing
 *   Google Sign-In SDK with minimal scopes (`openid`, `email`, `profile`).
 * - **Desktop**: `DesktopGoogleSignInProvider` (Step 4) — loopback OAuth flow
 *   on `http://127.0.0.1:53682/`.
 *
 * The interface lives in `:shared` so [DataRepository.signInToSync] can accept
 * it without depending on platform SDKs.
 */
interface GoogleSignInProvider {
    /**
     * Launch the platform-native Google sign-in UI and return a [GoogleIdentity]
     * on success, or a [Throwable]-wrapped failure.
     *
     * Must be called from a coroutine.  Implementations should suspend until the
     * user completes (or cancels) the sign-in flow.
     */
    suspend fun signIn(): Result<GoogleIdentity>
}
