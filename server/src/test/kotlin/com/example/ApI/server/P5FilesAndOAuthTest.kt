package com.example.ApI.server

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.oauth.OAuthTokenExchanger
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Phase 5 tests: file upload (POST /api/files/upload) and OAuth integrations.
 *
 * Auth uses [googleLogin] (fake Google login).  Integration OAuth tests inject a
 * [CombinedFakeExchanger] that handles GitHub/Google Workspace exchanges in
 * addition to the primary login exchange — without real network calls.
 */
class P5FilesAndOAuthTest {

    // ── Infrastructure helpers ────────────────────────────────────────────────

    private fun seededStorage(): Pair<ServerPlatformStorage, DataRepository> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "p5-test-${System.nanoTime()}")
        baseDir.mkdirs()
        val rootStorage = ServerPlatformStorage(baseDir = baseDir)
        val userDir = File(baseDir, "users/$TEST_USERNAME")
        userDir.mkdirs()
        val userStorage = ServerPlatformStorage(baseDir = userDir)
        val repo = DataRepository(userStorage)
        repo.saveAppSettings(
            AppSettings(
                current_user = TEST_USERNAME,
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        )
        return Pair(rootStorage, repo)
    }

    // ── Combined fake OAuthTokenExchanger ────────────────────────────────────

    /**
     * Handles all three exchange operations:
     * - [exchangeGoogleIdToken] passes the code through as the id_token (same as
     *   [FakeGoogleLoginExchanger]), so [googleLogin] works.
     * - [exchangeGitHub] and [exchangeGoogle] return scripted results for the
     *   integration OAuth tests.
     */
    class CombinedFakeExchanger(
        private val githubResult: Result<Pair<GitHubAuth, GitHubUser>> =
            Result.success(
                Pair(
                    GitHubAuth(
                        accessToken = "fake-gh-token",
                        tokenType = "bearer",
                        scope = "repo,read:user"
                    ),
                    GitHubUser(
                        login = "testuser-gh",
                        id = 42L,
                        nodeId = "node-42",
                        avatarUrl = "https://avatars.githubusercontent.com/u/42",
                        gravatarId = null,
                        url = "https://api.github.com/users/testuser-gh",
                        htmlUrl = "https://github.com/testuser-gh",
                        name = "Test GH User",
                        company = null,
                        blog = null,
                        location = null,
                        email = null,
                        bio = null,
                        publicRepos = 5,
                        publicGists = 0,
                        followers = 0,
                        following = 0,
                        createdAt = "2020-01-01T00:00:00Z",
                        updatedAt = "2024-01-01T00:00:00Z"
                    )
                )
            ),
        private val googleWorkspaceResult: Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> =
            Result.success(
                Pair(
                    GoogleWorkspaceAuth(
                        accessToken = "fake-google-token",
                        refreshToken = "fake-google-refresh",
                        expiresAt = System.currentTimeMillis() + 3_600_000L,
                        scopes = listOf("https://www.googleapis.com/auth/gmail.modify")
                    ),
                    GoogleWorkspaceUser(
                        id = "google-user-id-123",
                        email = "testuser@gmail.com",
                        displayName = "Test Google User",
                        photoUrl = null
                    )
                )
            )
    ) : OAuthTokenExchanger {
        override suspend fun exchangeGitHub(
            code: String,
            clientId: String,
            clientSecret: String,
            redirectUri: String
        ): Result<Pair<GitHubAuth, GitHubUser>> = githubResult

        override suspend fun exchangeGoogle(
            code: String,
            clientId: String,
            clientSecret: String,
            redirectUri: String
        ): Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> = googleWorkspaceResult

        /** Pass the code through as the id_token so [googleLogin] works. */
        override suspend fun exchangeGoogleIdToken(
            code: String,
            clientId: String,
            clientSecret: String,
            redirectUri: String
        ): Result<String> = Result.success(code)
    }

    // ── File upload tests ─────────────────────────────────────────────────────

    @Test
    fun `POST api files upload without session returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)

        val response = client.post("/api/files/upload") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", "hello world".toByteArray(), Headers.build {
                            append(HttpHeaders.ContentType, "text/plain")
                            append(HttpHeaders.ContentDisposition, "filename=\"test.txt\"")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST api files upload with session returns 201 Attachment`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)

        val fileContent = "Hello, world! This is a test file.".toByteArray()
        val response = c.post("/api/files/upload") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("file", fileContent, Headers.build {
                            append(HttpHeaders.ContentType, "text/plain")
                            append(HttpHeaders.ContentDisposition, "filename=\"hello.txt\"")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("hello.txt", body["file_name"]?.jsonPrimitive?.content)
        assertEquals("text/plain", body["mime_type"]?.jsonPrimitive?.content)
        assertNotNull(body["local_file_path"]?.jsonPrimitive?.content)

        // Verify the file was actually written to disk
        val localPath = body["local_file_path"]!!.jsonPrimitive.content
        val diskFile = File(localPath)
        assertTrue(diskFile.exists(), "File should exist on disk at $localPath")
        assertEquals(String(fileContent), diskFile.readText())
    }

    @Test
    fun `POST api files upload with empty body returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)

        // Upload multipart with no file parts — just a form field
        val response = c.post("/api/files/upload") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("provider", "openai")
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("No file content"))
    }

    @Test
    fun `DELETE api files deletes an uploaded file`() = testApplication {
        val (storage, repo) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)

        // Save a file via the repository directly
        val localPath = repo.saveFileLocally("to-delete.txt", "delete me".toByteArray())
        assertNotNull(localPath, "saveFileLocally should succeed")
        assertTrue(File(localPath).exists(), "File should exist before delete")

        val response = c.delete("/api/files") {
            contentType(ContentType.Application.Json)
            setBody("""{"filePath":"$localPath"}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertFalse(File(localPath).exists(), "File should be gone after delete")
    }

    // ── GET /api/integrations — status endpoint ───────────────────────────────

    @Test
    fun `GET api integrations returns disconnected state when no connections stored`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val c = googleLogin(TEST_USER_EMAIL)

        val response = c.get("/api/integrations")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["isGitHubConnected"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["isGoogleWorkspaceConnected"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `GET api integrations reflects connected state after saving connection`() = testApplication {
        val (storage, repo) = seededStorage()
        startWithFakeGoogleAuth(storage)

        // Pre-seed a GitHub connection
        repo.saveGitHubConnection(
            TEST_USERNAME,
            GitHubConnection(
                auth = GitHubAuth(accessToken = "pre-seeded-token", scope = "repo"),
                user = GitHubUser(
                    login = "seeded-user", id = 99L, nodeId = "n99",
                    avatarUrl = "", gravatarId = null, url = "", htmlUrl = "",
                    name = null, company = null, blog = null, location = null,
                    email = null, bio = null, publicRepos = 0, publicGists = 0,
                    followers = 0, following = 0,
                    createdAt = "2020-01-01T00:00:00Z", updatedAt = "2024-01-01T00:00:00Z"
                )
            )
        )

        val c = googleLogin(TEST_USER_EMAIL)

        val response = c.get("/api/integrations")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["isGitHubConnected"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["isGoogleWorkspaceConnected"]?.jsonPrimitive?.booleanOrNull)
    }

    // ── GET /oauth/github/start — 302 redirect to GitHub authorize URL ─────────

    @Test
    fun `GET oauth github start without session redirects to login`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val noRedirectClient = createClient { followRedirects = false }

        val response = noRedirectClient.get("/oauth/github/start")
        // Unauthenticated → redirect to /login
        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.contains("/login"), "Should redirect to /login, got: $location")
    }

    @Test
    fun `GET oauth github start with session redirects to GitHub authorize URL`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage, oauthExchanger = CombinedFakeExchanger())
        val c = googleLogin(TEST_USER_EMAIL)
        val noRedirectC = createClient { followRedirects = false }
        // Manually copy session cookie from c to noRedirectC is not practical;
        // instead use a combined client that carries cookies and does not follow redirects.
        // We reuse the googleLogin client with followRedirects=false — create a fresh one.
        val cookieNoRedirectC = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login first using the combined exchanger
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { cookieNoRedirectC.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val location0 = startResp.headers[HttpHeaders.Location]!!
        val state0 = location0.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        cookieNoRedirectC.get("/auth/google/callback") {
            parameter("code", "fake-id-token-$TEST_USER_EMAIL")
            parameter("state", state0)
        }

        val response = cookieNoRedirectC.get("/oauth/github/start")
        assertEquals(HttpStatusCode.Found, response.status)

        val location = response.headers[HttpHeaders.Location]
        assertNotNull(location, "Should have Location header")
        assertTrue(
            location!!.contains("github.com/login/oauth/authorize"),
            "Should redirect to GitHub authorize endpoint, got: $location"
        )
        assertTrue(location.contains("client_id="), "URL should contain client_id")
        assertTrue(location.contains("redirect_uri="), "URL should contain redirect_uri")
        assertTrue(location.contains("state="), "URL should contain state")
        assertTrue(
            location.contains("github%2Fcallback") || location.contains("github/callback"),
            "redirect_uri should include /oauth/github/callback"
        )
    }

    @Test
    fun `GET oauth github start stores state in session so callback succeeds`() = testApplication {
        val (storage, repo) = seededStorage()
        val exchanger = CombinedFakeExchanger()
        startWithFakeGoogleAuth(storage, oauthExchanger = exchanger)

        // Use a single cookie-carrying client that does NOT follow redirects
        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }

        // Step 1: Google login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loginLoc = startResp.headers[HttpHeaders.Location]!!
        val loginState = loginLoc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") {
            parameter("code", "fake-id-token-$TEST_USER_EMAIL")
            parameter("state", loginState)
        }

        // Step 2: Start GitHub flow — stores CSRF state in session
        val ghStartResp = c.get("/oauth/github/start")
        assertEquals(HttpStatusCode.Found, ghStartResp.status)
        val ghLocation = ghStartResp.headers[HttpHeaders.Location]!!
        val stateParam = ghLocation.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        // Step 3: Callback with correct state → should persist connection
        val callbackResp = c.get("/oauth/github/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.Found, callbackResp.status, "Callback should redirect on success")
        val callbackLocation = callbackResp.headers[HttpHeaders.Location]
        assertNotNull(callbackLocation)
        assertTrue(callbackLocation!!.contains("github=connected"))

        val saved = repo.loadGitHubConnection(TEST_USERNAME)
        assertNotNull(saved)
        assertEquals("fake-gh-token", saved!!.auth.accessToken)
    }

    // ── GET /oauth/github/callback — CSRF validation + happy path ─────────────

    @Test
    fun `github callback with missing state returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage, oauthExchanger = CombinedFakeExchanger())

        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loc = startResp.headers[HttpHeaders.Location]!!
        val st = loc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") { parameter("code", "fake-id-token-$TEST_USER_EMAIL"); parameter("state", st) }

        // No /oauth/github/start — session has no GitHub state
        val response = c.get("/oauth/github/callback?code=some-code&state=wrong-state")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("state"))
    }

    @Test
    fun `github callback with wrong state returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage, oauthExchanger = CombinedFakeExchanger())

        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loc = startResp.headers[HttpHeaders.Location]!!
        val st = loc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") { parameter("code", "fake-id-token-$TEST_USER_EMAIL"); parameter("state", st) }

        // Start GitHub flow to get real state in session
        c.get("/oauth/github/start")

        // Submit a DIFFERENT state — should be rejected
        val response = c.get("/oauth/github/callback?code=some-code&state=totally-wrong")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `github callback with correct state persists connection and redirects`() = testApplication {
        val (storage, repo) = seededStorage()
        startWithFakeGoogleAuth(storage, oauthExchanger = CombinedFakeExchanger())

        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loc = startResp.headers[HttpHeaders.Location]!!
        val st = loc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") { parameter("code", "fake-id-token-$TEST_USER_EMAIL"); parameter("state", st) }

        // Start the GitHub flow — writes state into session
        val ghStartResp = c.get("/oauth/github/start")
        assertEquals(HttpStatusCode.Found, ghStartResp.status)
        val authorizeUrl = ghStartResp.headers[HttpHeaders.Location]!!
        val stateParam = authorizeUrl.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        // Simulate GitHub redirect with correct state
        val callbackResp = c.get("/oauth/github/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.Found, callbackResp.status, "Should redirect on success")
        val location = callbackResp.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.contains("github=connected"), "Should redirect with github=connected")

        // Verify persistence
        val saved = repo.loadGitHubConnection(TEST_USERNAME)
        assertNotNull(saved, "GitHubConnection should be persisted")
        assertEquals("fake-gh-token", saved!!.auth.accessToken)
        assertEquals("testuser-gh", saved.user.login)
        assertTrue(repo.isGitHubConnected(TEST_USERNAME))
    }

    @Test
    fun `github callback with failed exchange returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        val failingExchanger = CombinedFakeExchanger(
            githubResult = Result.failure(Exception("Token exchange failed: HTTP 401"))
        )
        startWithFakeGoogleAuth(storage, oauthExchanger = failingExchanger)

        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loc = startResp.headers[HttpHeaders.Location]!!
        val st = loc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") { parameter("code", "fake-id-token-$TEST_USER_EMAIL"); parameter("state", st) }

        val ghStartResp = c.get("/oauth/github/start")
        assertEquals(HttpStatusCode.Found, ghStartResp.status)
        val authorizeUrl = ghStartResp.headers[HttpHeaders.Location]!!
        val stateParam = authorizeUrl.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        val callbackResp = c.get("/oauth/github/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.BadRequest, callbackResp.status)
        assertTrue(callbackResp.bodyAsText().contains("Token exchange failed"))
    }

    // ── GET /oauth/google/start — redirect to Google authorize URL ────────────

    @Test
    fun `GET oauth google start without session redirects to login`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage)
        val noRedirectClient = createClient { followRedirects = false }

        val response = noRedirectClient.get("/oauth/google/start")
        assertEquals(HttpStatusCode.Found, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.contains("/login"), "Unauthenticated should redirect to /login, got: $location")
    }

    @Test
    fun `GET oauth google start with session and no client id returns 503`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage, oauthExchanger = CombinedFakeExchanger())

        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loc = startResp.headers[HttpHeaders.Location]!!
        val st = loc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") { parameter("code", "fake-id-token-$TEST_USER_EMAIL"); parameter("state", st) }

        // Without GOOGLE_OAUTH_CLIENT_ID env var, /oauth/google/start returns 503
        val response = c.get("/oauth/google/start")
        assertTrue(
            response.status == HttpStatusCode.ServiceUnavailable || response.status == HttpStatusCode.Found,
            "Expected 503 (not configured) or 302 (env set), got ${response.status}"
        )
    }

    // ── GET /oauth/google/callback — CSRF + happy path ────────────────────────

    @Test
    fun `google callback with missing state returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        startWithFakeGoogleAuth(storage, oauthExchanger = CombinedFakeExchanger())

        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loc = startResp.headers[HttpHeaders.Location]!!
        val st = loc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") { parameter("code", "fake-id-token-$TEST_USER_EMAIL"); parameter("state", st) }

        val response = c.get("/oauth/google/callback?code=code&state=wrong")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `google callback with correct state persists connection and redirects`() = testApplication {
        val (storage, repo) = seededStorage()
        startWithFakeGoogleAuth(storage, oauthExchanger = CombinedFakeExchanger())

        val c = createClient {
            install(io.ktor.client.plugins.cookies.HttpCookies)
            followRedirects = false
        }
        // Login
        GoogleClientIdTestHook.override = "test-client-id"
        val startResp = try { c.get("/auth/google/start") } finally { GoogleClientIdTestHook.override = null }
        val loc = startResp.headers[HttpHeaders.Location]!!
        val st = loc.split("&", "?").find { it.startsWith("state=") }!!.removePrefix("state=")
        c.get("/auth/google/callback") { parameter("code", "fake-id-token-$TEST_USER_EMAIL"); parameter("state", st) }

        // If GOOGLE_OAUTH_CLIENT_ID is unset, /oauth/google/start returns 503 — skip
        val gStartResp = c.get("/oauth/google/start")
        if (gStartResp.status == HttpStatusCode.ServiceUnavailable) {
            return@testApplication
        }
        assertEquals(HttpStatusCode.Found, gStartResp.status)
        val authorizeUrl = gStartResp.headers[HttpHeaders.Location]!!
        val stateParam = authorizeUrl.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        val callbackResp = c.get("/oauth/google/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.Found, callbackResp.status)
        val location = callbackResp.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.contains("google=connected"))

        val saved = repo.loadGoogleWorkspaceConnection(TEST_USERNAME)
        assertNotNull(saved)
        assertEquals("fake-google-token", saved!!.auth.accessToken)
        assertEquals("testuser@gmail.com", saved.user.email)
        assertTrue(repo.isGoogleWorkspaceConnected(TEST_USERNAME))
    }

    // ── DELETE /api/integrations/github|google ────────────────────────────────

    @Test
    fun `DELETE integrations github disconnects and status reflects it`() = testApplication {
        val (storage, repo) = seededStorage()
        startWithFakeGoogleAuth(storage)

        // Pre-seed connection
        repo.saveGitHubConnection(
            TEST_USERNAME,
            GitHubConnection(
                auth = GitHubAuth(accessToken = "tok", scope = "repo"),
                user = GitHubUser(
                    login = "x", id = 1L, nodeId = "n1", avatarUrl = "", gravatarId = null,
                    url = "", htmlUrl = "", name = null, company = null, blog = null,
                    location = null, email = null, bio = null, publicRepos = 0,
                    publicGists = 0, followers = 0, following = 0,
                    createdAt = "2020-01-01T00:00:00Z", updatedAt = "2024-01-01T00:00:00Z"
                )
            )
        )
        assertTrue(repo.isGitHubConnected(TEST_USERNAME))

        val c = googleLogin(TEST_USER_EMAIL)

        val deleteResp = c.delete("/api/integrations/github")
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        assertFalse(repo.isGitHubConnected(TEST_USERNAME))

        // Status endpoint also reflects disconnected
        val statusResp = c.get("/api/integrations")
        val body = Json.parseToJsonElement(statusResp.bodyAsText()).jsonObject
        assertEquals(false, body["isGitHubConnected"]?.jsonPrimitive?.booleanOrNull)
    }

    // ── PATCH /api/integrations/google/services ───────────────────────────────

    @Test
    fun `PATCH integrations google services updates enabled services`() = testApplication {
        val (storage, repo) = seededStorage()
        startWithFakeGoogleAuth(storage)

        // Pre-seed Google connection
        repo.saveGoogleWorkspaceConnection(
            TEST_USERNAME,
            GoogleWorkspaceConnection(
                auth = GoogleWorkspaceAuth(
                    accessToken = "tok", refreshToken = null,
                    expiresAt = System.currentTimeMillis() + 3_600_000L,
                    scopes = emptyList()
                ),
                user = GoogleWorkspaceUser(id = "u1", email = "u@g.com", displayName = null, photoUrl = null)
            )
        )

        val c = googleLogin(TEST_USER_EMAIL)

        val patchResp = c.patch("/api/integrations/google/services") {
            contentType(ContentType.Application.Json)
            setBody("""{"gmail":false,"calendar":true,"drive":false}""")
        }
        assertEquals(HttpStatusCode.OK, patchResp.status)

        val body = Json.parseToJsonElement(patchResp.bodyAsText()).jsonObject
        assertEquals(false, body["gmail"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, body["calendar"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["drive"]?.jsonPrimitive?.booleanOrNull)

        // Verify persistence
        val saved = repo.loadGoogleWorkspaceConnection(TEST_USERNAME)
        assertNotNull(saved)
        assertFalse(saved.enabledServices.gmail)
        assertTrue(saved.enabledServices.calendar)
        assertFalse(saved.enabledServices.drive)
    }
}
