package com.example.ApI.server

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.GitHubAuth
import com.example.ApI.data.model.GitHubUser
import com.example.ApI.data.model.GoogleWorkspaceAuth
import com.example.ApI.data.model.GoogleWorkspaceUser
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.auth.FakeSyncAuthClient
import com.example.ApI.server.auth.GoogleClaims
import com.example.ApI.server.auth.GoogleTokenVerifier
import com.example.ApI.server.oauth.OAuthTokenExchanger
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File

// ── Canonical test identity ───────────────────────────────────────────────────

/** Email used by the canonical test identity across all test suites. */
const val TEST_USER_EMAIL = "test@example.com"

/** Sanitized username derived from [TEST_USER_EMAIL] (mirrors sync-server logic). */
const val TEST_USERNAME = "test_example_com"

// ── Fake implementations ──────────────────────────────────────────────────────

/**
 * Fake [GoogleTokenVerifier] for tests.
 *
 * Accepts tokens in the format `"fake-id-token-{email}"` and returns
 * [GoogleClaims] without any network calls.  All other tokens return null.
 */
class FakeGoogleTokenVerifier : GoogleTokenVerifier {
    override suspend fun verify(idToken: String): GoogleClaims? {
        if (!idToken.startsWith("fake-id-token-")) return null
        val email = idToken.removePrefix("fake-id-token-")
        return GoogleClaims(
            sub = "fake-sub-" + email.replace(Regex("[^a-z0-9]"), "_"),
            email = email,
            aud = "test-client-id"
        )
    }
}

/**
 * Fake [OAuthTokenExchanger] for the Google login flow.
 *
 * [exchangeGoogleIdToken] passes the `code` through as the id_token so callers
 * can use `code = "fake-id-token-{email}"` and have the verifier extract the
 * email deterministically.  GitHub / Google Workspace methods are not used by
 * the login flow — they throw to surface any accidental calls.
 */
class FakeGoogleLoginExchanger : OAuthTokenExchanger {
    override suspend fun exchangeGitHub(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Result<Pair<GitHubAuth, GitHubUser>> =
        Result.failure(UnsupportedOperationException("not used in Google login tests"))

    override suspend fun exchangeGoogle(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> =
        Result.failure(UnsupportedOperationException("not used in Google login tests"))

    /** Pass the code through as the id_token — callers use `"fake-id-token-{email}"`. */
    override suspend fun exchangeGoogleIdToken(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): Result<String> = Result.success(code)
}

// ── Module helper ─────────────────────────────────────────────────────────────

/**
 * Configures the application with all fakes wired for Google login tests.
 *
 * Pass [allowedGoogleEmails] to test allowlist enforcement.
 * Set [startRegistry] = false (default) to suppress all sync engine network calls.
 *
 * Pass a custom [oauthExchanger] when a test also exercises GitHub / Google
 * Workspace integration OAuth (P5 tests).  The exchanger MUST implement
 * [OAuthTokenExchanger.exchangeGoogleIdToken] so that [googleLogin] works.
 * Defaults to [FakeGoogleLoginExchanger] which covers the login flow only.
 */
fun ApplicationTestBuilder.startWithFakeGoogleAuth(
    storage: ServerPlatformStorage,
    allowedGoogleEmails: Set<String> = emptySet(),
    startRegistry: Boolean = false,
    syncAuthClient: com.example.ApI.server.auth.SyncAuthClient = FakeSyncAuthClient(),
    oauthExchanger: OAuthTokenExchanger = FakeGoogleLoginExchanger(),
    chatEngineFactory: ((com.example.ApI.data.repository.DataRepository) -> com.example.ApI.server.streaming.ChatEngine)? = null,
    titleGeneratorFactory: ((com.example.ApI.data.repository.DataRepository) -> TitleGenerator)? = null
) {
    application {
        module(
            storage,
            oauthExchanger = oauthExchanger,
            googleTokenVerifier = FakeGoogleTokenVerifier(),
            syncAuthClient = syncAuthClient,
            startRegistry = startRegistry,
            allowedGoogleEmails = allowedGoogleEmails,
            chatEngineFactory = chatEngineFactory,
            titleGeneratorFactory = titleGeneratorFactory
        )
    }
}

// ── Login helper ──────────────────────────────────────────────────────────────

/**
 * Drives the full Google login callback flow and returns an authenticated
 * cookie-carrying HTTP client.
 *
 * Requires the application to be started with [startWithFakeGoogleAuth] (or an
 * equivalent manual module configuration with the fake injections).
 *
 * The [GoogleClientIdTestHook] is set only for the duration of the
 * `GET /auth/google/start` call so it does not interfere with concurrent tests.
 */
suspend fun ApplicationTestBuilder.googleLogin(
    email: String = TEST_USER_EMAIL
): io.ktor.client.HttpClient {
    // Use a no-redirect client to GET /auth/google/start so the test engine does
    // not follow the 302 → https://accounts.google.com (which it cannot resolve).
    val noRedirectClient = createClient {
        install(HttpCookies)
        followRedirects = false
    }

    // Step 1: GET /auth/google/start — stores CSRF state in session cookie and
    // returns 302 to Google.  The Location header contains the state parameter.
    GoogleClientIdTestHook.override = "test-client-id"
    val startResponse = try {
        noRedirectClient.get("/auth/google/start")
    } finally {
        GoogleClientIdTestHook.override = null
    }

    val location = startResponse.headers[HttpHeaders.Location]
        ?: error("GET /auth/google/start did not return a Location header (status ${startResponse.status})")

    // Parse state from the redirect URL query string
    val state = location.split("&", "?")
        .find { it.startsWith("state=") }
        ?.removePrefix("state=")
        ?: error("Could not parse 'state' from Location header: $location")

    // Step 2: GET /auth/google/callback — the cookie jar carries the session with
    // googleLoginState; the fake exchanger passes the code through as the id_token.
    // The callback redirects to "/" on success — that redirect is fine to follow.
    noRedirectClient.get("/auth/google/callback") {
        parameter("code", "fake-id-token-$email")
        parameter("state", state)
    }

    return noRedirectClient
}

// ── Seeded storage helpers ────────────────────────────────────────────────────

/**
 * Creates an isolated temp storage pre-seeded with data for [username].
 *
 * Returns (rootStorage, perUserRepository) — rootStorage is passed to
 * [Application.module] while perUserRepository is used in tests to assert
 * on the state of persisted files.
 *
 * Default: seeds under `users/test_example_com/` matching [TEST_USERNAME].
 */
fun seededStorageForUser(
    username: String = TEST_USERNAME,
    chatName: String = "Hello World Chat",
    groupName: String = "Test Group",
    apiKeyProvider: String = "openai",
    apiKey: String = "sk-thisisafakeapikey1234"
): Pair<ServerPlatformStorage, DataRepository> {
    val baseDir = File(System.getProperty("java.io.tmpdir"), "auth-test-${System.nanoTime()}")
    baseDir.mkdirs()
    val rootStorage = ServerPlatformStorage(baseDir = baseDir)
    val userDir = File(baseDir, "users/$username")
    userDir.mkdirs()
    val userStorage = ServerPlatformStorage(baseDir = userDir)
    val repo = DataRepository(userStorage)

    repo.saveAppSettings(
        AppSettings(
            current_user = username,
            selected_provider = "openai",
            selected_model = "gpt-4o"
        )
    )

    val chat = repo.createNewChat(username, chatName)
    repo.addMessageToChat(
        username, chat.chat_id,
        com.example.ApI.data.model.Message(role = "user", text = "Hello!")
    )

    repo.createNewGroup(username, groupName)

    repo.addApiKey(
        username,
        ApiKey(
            id = "key-1234",
            provider = apiKeyProvider,
            key = apiKey,
            isActive = true,
            customName = "My OpenAI Key"
        )
    )

    return rootStorage to repo
}

/** Creates a minimal temp storage with no pre-seeded data. */
fun tempStorage(): ServerPlatformStorage {
    val dir = File(System.getProperty("java.io.tmpdir"), "server-test-${System.nanoTime()}")
    dir.mkdirs()
    return ServerPlatformStorage(baseDir = dir)
}
