package com.example.ApI.server.auth

import com.example.ApI.server.UserRegistry
import com.example.ApI.server.UserSession
import com.example.ApI.util.JsonConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// ── whoami seam ───────────────────────────────────────────────────────────────

/** The account a sync token belongs to (the sync server's `GET /auth/whoami`). */
data class SyncIdentity(val username: String, val email: String)

/**
 * Seam for asking the sync server who a bearer token belongs to.  The real implementation calls
 * `GET {SYNC_SERVER_URL}/auth/whoami`; tests inject a fake (same pattern as [SyncAuthClient]).
 */
fun interface SyncWhoamiClient {
    /** The identity of [token], or null when the sync server rejects it or is unavailable. */
    suspend fun whoami(token: String): SyncIdentity?
}

class RealSyncWhoamiClient(
    private val syncServerUrl: String = com.example.ApI.server.resolveSyncServerUrl()
) : SyncWhoamiClient {

    override suspend fun whoami(token: String): SyncIdentity? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$syncServerUrl/auth/whoami").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                val json = JsonConfig.standard.parseToJsonElement(
                    conn.inputStream.bufferedReader().use { it.readText() }
                ).jsonObject
                val username = json["username"]?.jsonPrimitive?.content ?: return@withContext null
                val email = json["email"]?.jsonPrimitive?.content ?: ""
                SyncIdentity(username, email)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Fake [SyncWhoamiClient] for tests: knows the tokens put in [identities]. */
class FakeSyncWhoamiClient(
    val identities: MutableMap<String, SyncIdentity> = ConcurrentHashMap()
) : SyncWhoamiClient {
    /** Every token asked about (to assert on caching). */
    val calls = java.util.concurrent.CopyOnWriteArrayList<String>()

    override suspend fun whoami(token: String): SyncIdentity? {
        calls += token
        return identities[token]
    }
}

// ── Bearer authentication of /api ─────────────────────────────────────────────

/**
 * Validates `Authorization: Bearer <sync token>` on `/api`: the token is the one a device holds
 * for the sync server, checked there through [whoami] (cached by token hash for [cacheTtlMs];
 * tokens are never logged or stored).  The Google login's allowed-emails rule applies, and a
 * user whose server-side copy is not syncing yet (never logged in on the web, or its stored
 * token was rejected) is bootstrapped with the presented token, like the login callback does.
 */
class SyncBearerAuth(
    private val whoami: SyncWhoamiClient,
    private val registry: UserRegistry,
    private val allowedEmails: Set<String>,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val CACHE_TTL_MS = 5 * 60_000L
        private const val MAX_CACHE_ENTRIES = 10_000
        private val USERNAME = Regex("^[a-z0-9_]{1,128}$")
    }

    private class Entry(val identity: SyncIdentity, val expiresAt: Long)

    private val log = LoggerFactory.getLogger(SyncBearerAuth::class.java)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** The session of the owner of [token], or null when the token is not accepted. */
    suspend fun authenticate(token: String): UserSession? {
        if (token.isBlank()) return null
        val key = sha256(token)
        val time = now()
        val identity = cache[key]?.takeIf { it.expiresAt > time }?.identity
            ?: whoami.whoami(token)?.also { fresh ->
                if (cache.size >= MAX_CACHE_ENTRIES) cache.entries.removeIf { it.value.expiresAt <= time }
                if (cache.size < MAX_CACHE_ENTRIES) cache[key] = Entry(fresh, time + cacheTtlMs)
            }
            ?: return null

        // The username names a directory: only the sync server's canonical form is accepted
        if (!USERNAME.matches(identity.username)) {
            log.warn("Bearer token rejected: unexpected username form from the sync server")
            return null
        }
        if (allowedEmails.isNotEmpty() && identity.email !in allowedEmails) {
            log.warn("Bearer token rejected: email '${identity.email}' not in allowlist")
            return null
        }
        return try {
            registry.ensureUserSync(identity.username, identity.email, token)
            UserSession(authenticated = true, username = identity.username, email = identity.email)
        } catch (e: Exception) {
            log.warn("Bearer sync bootstrap of '${identity.username}' failed: ${e.message}")
            null
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** Name of the Ktor authentication provider backed by [SyncBearerAuth]. */
const val SYNC_BEARER_AUTH = "sync-bearer"

/**
 * Ktor authentication provider for `Authorization: Bearer <sync token>`; registered next to the
 * session-cookie provider (`authenticate("session", SYNC_BEARER_AUTH)`).  Answers the same 401
 * body as the session provider.
 */
class SyncBearerAuthProvider(private val config: Config) : AuthenticationProvider(config) {

    class Config(name: String, val auth: SyncBearerAuth) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val header = call.request.headers[HttpHeaders.Authorization]
        val token = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val session = token?.let { config.auth.authenticate(it) }
        if (session != null) {
            context.principal(session)
            return
        }
        val cause = if (token == null) AuthenticationFailedCause.NoCredentials else AuthenticationFailedCause.InvalidCredentials
        context.challenge(SYNC_BEARER_AUTH, cause) { challenge, challengedCall ->
            challengedCall.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
            challenge.complete()
        }
    }
}
