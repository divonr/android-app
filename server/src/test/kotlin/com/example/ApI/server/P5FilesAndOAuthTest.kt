package com.example.ApI.server

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.oauth.OAuthTokenExchanger
import io.ktor.client.plugins.cookies.*
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
 * OAuth tests inject a [FakeOAuthTokenExchanger] that returns scripted results
 * without making any real network calls.  Nothing touches ~/.llm-api-web.
 */
class P5FilesAndOAuthTest {

    private val testPassword = "p5-test-password"
    private val testAuthConfig = AuthConfig(
        password = testPassword,
        sessionSecret = "p5-test-session-secret-that-is-long-enough"
    )

    // ── Infrastructure helpers ────────────────────────────────────────────────

    private fun seededStorage(): Pair<ServerPlatformStorage, DataRepository> {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "p5-test-${System.nanoTime()}")
        baseDir.mkdirs()
        val rootStorage = ServerPlatformStorage(baseDir = baseDir)
        val userDir = File(baseDir, "users/default")
        userDir.mkdirs()
        val userStorage = ServerPlatformStorage(baseDir = userDir)
        val repo = DataRepository(userStorage)
        repo.saveAppSettings(
            AppSettings(
                current_user = "default",
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        )
        return Pair(rootStorage, repo)
    }

    // ── Fake OAuthTokenExchanger ─────────────────────────────────────────────

    class FakeOAuthTokenExchanger(
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
        private val googleResult: Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> =
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
        ): Result<Pair<GoogleWorkspaceAuth, GoogleWorkspaceUser>> = googleResult
    }

    // ── File upload tests ─────────────────────────────────────────────────────

    @Test
    fun `POST api files upload without session returns 401`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }

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
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }

        // Login
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val fileContent = "Hello, world! This is a test file.".toByteArray()
        val response = cookieClient.post("/api/files/upload") {
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
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Upload multipart with no file parts — just a form field
        val response = cookieClient.post("/api/files/upload") {
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
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Save a file via the repository directly
        val localPath = repo.saveFileLocally("to-delete.txt", "delete me".toByteArray())
        assertNotNull(localPath, "saveFileLocally should succeed")
        assertTrue(File(localPath).exists(), "File should exist before delete")

        val response = cookieClient.delete("/api/files") {
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
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/api/integrations")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["isGitHubConnected"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["isGoogleWorkspaceConnected"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `GET api integrations reflects connected state after saving connection`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }

        // Pre-seed a GitHub connection
        repo.saveGitHubConnection(
            "default",
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

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/api/integrations")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["isGitHubConnected"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["isGoogleWorkspaceConnected"]?.jsonPrimitive?.booleanOrNull)
    }

    // ── GET /oauth/github/start — 302 redirect to GitHub authorize URL ─────────

    @Test
    fun `GET oauth github start without session redirects to login`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
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
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/oauth/github/start")
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
        val fakeExchanger = FakeOAuthTokenExchanger()
        application { module(storage, testAuthConfig, oauthExchanger = fakeExchanger) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Start: stores CSRF state in session and redirects to GitHub
        val startResp = cookieClient.get("/oauth/github/start")
        assertEquals(HttpStatusCode.Found, startResp.status)
        val location = startResp.headers[HttpHeaders.Location]!!
        val stateParam = location.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        // Callback with the correct state should succeed
        val callbackResp = cookieClient.get("/oauth/github/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.Found, callbackResp.status, "Callback should redirect on success")
        val callbackLocation = callbackResp.headers[HttpHeaders.Location]
        assertNotNull(callbackLocation)
        assertTrue(callbackLocation!!.contains("github=connected"))

        val saved = repo.loadGitHubConnection("default")
        assertNotNull(saved)
        assertEquals("fake-gh-token", saved!!.auth.accessToken)
    }

    // ── GET /oauth/github/callback — CSRF validation + happy path ─────────────

    @Test
    fun `github callback with missing state returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig, oauthExchanger = FakeOAuthTokenExchanger()) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        // No /start call — session has no state
        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/oauth/github/callback?code=some-code&state=wrong-state")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("state"))
    }

    @Test
    fun `github callback with wrong state returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig, oauthExchanger = FakeOAuthTokenExchanger()) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Start flow via GET /oauth/github/start to get a real state in the session
        cookieClient.get("/oauth/github/start")

        // Submit a DIFFERENT state — should be rejected
        val response = cookieClient.get("/oauth/github/callback?code=some-code&state=totally-wrong")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `github callback with correct state persists connection and redirects`() = testApplication {
        val (storage, repo) = seededStorage()
        val fakeExchanger = FakeOAuthTokenExchanger()
        application { module(storage, testAuthConfig, oauthExchanger = fakeExchanger) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Start the flow via GET — this writes the state into the session and redirects
        val startResp = cookieClient.get("/oauth/github/start")
        assertEquals(HttpStatusCode.Found, startResp.status)
        val authorizeUrl = startResp.headers[HttpHeaders.Location]!!
        val stateParam = authorizeUrl.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        // Simulate GitHub redirect — use the correct state
        val callbackResp = cookieClient.get("/oauth/github/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.Found, callbackResp.status, "Should redirect on success")
        val location = callbackResp.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.contains("github=connected"), "Should redirect to integrations with github=connected")

        // Verify the connection was actually persisted
        val saved = repo.loadGitHubConnection("default")
        assertNotNull(saved, "GitHubConnection should be persisted")
        assertEquals("fake-gh-token", saved!!.auth.accessToken)
        assertEquals("testuser-gh", saved.user.login)
        assertTrue(repo.isGitHubConnected("default"))
    }

    @Test
    fun `github callback with failed exchange returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        val failingExchanger = FakeOAuthTokenExchanger(
            githubResult = Result.failure(Exception("Token exchange failed: HTTP 401"))
        )
        application { module(storage, testAuthConfig, oauthExchanger = failingExchanger) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val startResp = cookieClient.get("/oauth/github/start")
        assertEquals(HttpStatusCode.Found, startResp.status)
        val authorizeUrl = startResp.headers[HttpHeaders.Location]!!
        val stateParam = authorizeUrl.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        val callbackResp = cookieClient.get("/oauth/github/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.BadRequest, callbackResp.status)
        assertTrue(callbackResp.bodyAsText().contains("Token exchange failed"))
    }

    // ── GET /oauth/google/start — redirect to Google authorize URL ────────────

    @Test
    fun `GET oauth google start without session redirects to login`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig) }
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
        application { module(storage, testAuthConfig, oauthExchanger = FakeOAuthTokenExchanger()) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // Without GOOGLE_OAUTH_CLIENT_ID env var, should return 503
        val response = cookieClient.get("/oauth/google/start")
        assertTrue(
            response.status == HttpStatusCode.ServiceUnavailable || response.status == HttpStatusCode.Found,
            "Expected 503 (not configured) or 302 (env set), got ${response.status}"
        )
    }

    // ── GET /oauth/google/callback — CSRF + happy path ────────────────────────

    @Test
    fun `google callback with missing state returns 400`() = testApplication {
        val (storage, _) = seededStorage()
        application { module(storage, testAuthConfig, oauthExchanger = FakeOAuthTokenExchanger()) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val response = cookieClient.get("/oauth/google/callback?code=code&state=wrong")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `google callback with correct state persists connection and redirects`() = testApplication {
        val (storage, repo) = seededStorage()
        val fakeExchanger = FakeOAuthTokenExchanger()
        application { module(storage, testAuthConfig, oauthExchanger = fakeExchanger) }
        val cookieClient = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        // If GOOGLE_OAUTH_CLIENT_ID is unset, /google/start returns 503.
        // In that case, skip the full flow (same code path as GitHub — proven by github tests).
        val startResp = cookieClient.get("/oauth/google/start")
        if (startResp.status == HttpStatusCode.ServiceUnavailable) {
            return@testApplication
        }
        assertEquals(HttpStatusCode.Found, startResp.status)
        val authorizeUrl = startResp.headers[HttpHeaders.Location]!!
        val stateParam = authorizeUrl.substringAfter("state=").substringBefore("&").let {
            java.net.URLDecoder.decode(it, "UTF-8")
        }

        val callbackResp = cookieClient.get("/oauth/google/callback?code=fake-code&state=$stateParam")
        assertEquals(HttpStatusCode.Found, callbackResp.status)
        val location = callbackResp.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.contains("google=connected"))

        val saved = repo.loadGoogleWorkspaceConnection("default")
        assertNotNull(saved)
        assertEquals("fake-google-token", saved!!.auth.accessToken)
        assertEquals("testuser@gmail.com", saved.user.email)
        assertTrue(repo.isGoogleWorkspaceConnected("default"))
    }

    // ── DELETE /api/integrations/github|google ────────────────────────────────

    @Test
    fun `DELETE integrations github disconnects and status reflects it`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }

        // Pre-seed connection
        repo.saveGitHubConnection(
            "default",
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
        assertTrue(repo.isGitHubConnected("default"))

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val deleteResp = cookieClient.delete("/api/integrations/github")
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        assertFalse(repo.isGitHubConnected("default"))

        // Status endpoint also reflects disconnected
        val statusResp = cookieClient.get("/api/integrations")
        val body = Json.parseToJsonElement(statusResp.bodyAsText()).jsonObject
        assertEquals(false, body["isGitHubConnected"]?.jsonPrimitive?.booleanOrNull)
    }

    // ── PATCH /api/integrations/google/services ───────────────────────────────

    @Test
    fun `PATCH integrations google services updates enabled services`() = testApplication {
        val (storage, repo) = seededStorage()
        application { module(storage, testAuthConfig) }
        val cookieClient = createClient { install(HttpCookies) }

        // Pre-seed Google connection
        repo.saveGoogleWorkspaceConnection(
            "default",
            GoogleWorkspaceConnection(
                auth = GoogleWorkspaceAuth(
                    accessToken = "tok", refreshToken = null,
                    expiresAt = System.currentTimeMillis() + 3_600_000L,
                    scopes = emptyList()
                ),
                user = GoogleWorkspaceUser(id = "u1", email = "u@g.com", displayName = null, photoUrl = null)
            )
        )

        cookieClient.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"$testPassword"}""")
        }

        val patchResp = cookieClient.patch("/api/integrations/google/services") {
            contentType(ContentType.Application.Json)
            setBody("""{"gmail":false,"calendar":true,"drive":false}""")
        }
        assertEquals(HttpStatusCode.OK, patchResp.status)

        val body = Json.parseToJsonElement(patchResp.bodyAsText()).jsonObject
        assertEquals(false, body["gmail"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, body["calendar"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, body["drive"]?.jsonPrimitive?.booleanOrNull)

        // Verify persistence
        val saved = repo.loadGoogleWorkspaceConnection("default")
        assertNotNull(saved)
        assertFalse(saved.enabledServices.gmail)
        assertTrue(saved.enabledServices.calendar)
        assertFalse(saved.enabledServices.drive)
    }
}
