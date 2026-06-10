package com.example.ApI.server

import com.example.ApI.data.model.RemoteSyncSettings
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.server.streaming.RepositoryChatEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.File

// ── UserContext ───────────────────────────────────────────────────────────────

/**
 * All per-user services for a single authenticated user.
 *
 * Each [UserContext] owns an isolated [ServerPlatformStorage] and [DataRepository]
 * rooted at `{dataRoot}/users/{username}/`.  The [chatEngine] and [titleGenerator]
 * are created from the same factory lambdas used at application startup so tests
 * can inject fakes uniformly.
 */
data class UserContext(
    val username: String,
    val storage: ServerPlatformStorage,
    val repository: DataRepository,
    val chatEngine: ChatEngine,
    val titleGenerator: TitleGenerator
)

// ── UserRegistry ──────────────────────────────────────────────────────────────

/**
 * Lazy, mutex-guarded registry of per-user [UserContext] instances.
 *
 * On first access for a given username the registry:
 *   1. Creates `{baseDir}/users/{username}/` (mkdirs).
 *   2. Constructs a fresh [ServerPlatformStorage], [DataRepository], [ChatEngine],
 *      and [TitleGenerator] via the injected factory lambdas.
 *   3. If [startEngine] is true **and** the user's `app_settings.json` already
 *      contains valid sync credentials (restart-rehydration case), starts the
 *      [DataRepository.startSync] + periodic pull loop immediately.
 *
 * On login ([bootstrapUserSync]) the registry seeds sync settings into the
 * user's `app_settings.json`, then starts the engine + pull loop.
 *
 * All pull loops are cancelled via [stopAll] on `ApplicationStopping`.
 *
 * @param baseDir              Root data directory (e.g. `~/.llm-api-web`).
 * @param syncServerUrl        Base URL of the sync server.
 * @param pullIntervalSeconds  Seconds between periodic background pulls.
 * @param startEngine          When false the sync engine and pull loop are NEVER
 *                             started — used in tests to suppress network calls.
 * @param chatEngineFactory    Factory that creates a [ChatEngine] from a [DataRepository].
 * @param titleGeneratorFactory Factory that creates a [TitleGenerator] from a [DataRepository].
 */
class UserRegistry(
    private val baseDir: File,
    private val syncServerUrl: String = "http://localhost:8090",
    private val pullIntervalSeconds: Long = 20L,
    val startEngine: Boolean = true,
    private val chatEngineFactory: (DataRepository) -> ChatEngine = { repo -> RepositoryChatEngine(repo) },
    private val titleGeneratorFactory: (DataRepository) -> TitleGenerator = { repo ->
        TitleGenerator { username, chatId, provider ->
            repo.generateConversationTitle(username, chatId, provider)
        }
    }
) {
    private val log = LoggerFactory.getLogger(UserRegistry::class.java)
    private val mutex = Mutex()
    private val contexts = HashMap<String, UserContext>()
    private val pullScopes = HashMap<String, CoroutineScope>()

    // ── Context access ────────────────────────────────────────────────────────

    /**
     * Returns (creating if absent) the [UserContext] for [username].
     *
     * Thread-safe: a [Mutex] serialises concurrent first-access for the same
     * username.  Subsequent calls for already-created contexts return immediately.
     */
    suspend fun context(username: String): UserContext {
        return mutex.withLock {
            contexts.getOrPut(username) { createContext(username) }
        }
    }

    // ── Per-user context creation ─────────────────────────────────────────────

    private fun createContext(username: String): UserContext {
        val userDir = File(baseDir, "users/$username")
        userDir.mkdirs()
        val storage = ServerPlatformStorage(userDir)
        val repository = DataRepository(storage)
        val chatEngine = chatEngineFactory(repository)
        val titleGenerator = titleGeneratorFactory(repository)

        // Restart-rehydration: if the user already has a valid sync token in
        // their settings (left over from a previous login) restart the engine
        // immediately so data stays current without a re-login.
        if (startEngine) {
            val settings = repository.loadAppSettings()
            if (settings.remoteSync.enabled && settings.remoteSync.authToken.isNotBlank()) {
                log.info("Rehydrating sync for user '$username'")
                repository.startSync()
                launchPullLoop(username, repository)
            }
        }

        return UserContext(
            username = username,
            storage = storage,
            repository = repository,
            chatEngine = chatEngine,
            titleGenerator = titleGenerator
        )
    }

    // ── Sync bootstrap (called at Google login callback) ──────────────────────

    /**
     * Seeds sync credentials into the user's `app_settings.json` and starts
     * the sync engine + periodic pull loop.
     *
     * Idempotent: calling it a second time (e.g. re-login) updates the token
     * and restarts the pull loop only if one is not already running.
     *
     * @param username  Canonical username from the sync server.
     * @param email     Google account email (stored for display).
     * @param syncToken Bearer token minted by the sync server.
     */
    suspend fun bootstrapUserSync(username: String, email: String, syncToken: String) {
        val ctx = context(username)

        val current = ctx.repository.loadAppSettings()
        val updated = current.copy(
            remoteSync = RemoteSyncSettings(
                enabled = true,
                serverBaseUrl = syncServerUrl,
                authToken = syncToken,
                accountEmail = email
            ),
            current_user = username
        )
        ctx.repository.saveAppSettings(updated)
        log.info("Sync credentials seeded for user '$username'")

        if (!startEngine) return

        ctx.repository.startSync()
        ctx.repository.pullNow()
        launchPullLoop(username, ctx.repository)
    }

    // ── Pull loop ─────────────────────────────────────────────────────────────

    private fun launchPullLoop(username: String, repo: DataRepository) {
        if (pullScopes.containsKey(username)) {
            log.debug("Pull loop already running for '$username' — skipping")
            return
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pullScopes[username] = scope
        scope.launch {
            while (isActive) {
                delay(pullIntervalSeconds * 1_000L)
                try {
                    repo.pullNow()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    log.warn("Periodic pull failed for '$username': ${e.message}")
                }
            }
        }
        log.info("Pull loop started for '$username' (interval=${pullIntervalSeconds}s)")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Cancels all per-user pull loops.  Called on `ApplicationStopping` so
     * threads do not linger after the server shuts down.
     */
    fun stopAll() {
        pullScopes.values.forEach { it.cancel() }
        pullScopes.clear()
        log.info("All pull loops stopped")
    }
}
