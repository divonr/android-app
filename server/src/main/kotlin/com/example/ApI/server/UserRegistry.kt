package com.example.ApI.server

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.MigrationResult
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.server.streaming.RepositoryChatEngine
import com.example.ApI.util.JsonConfig
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
 *   3. Pins the device-local `current_user` of the user's settings to [username] (the web is
 *      one sync device per account; its files are always the account's own, never another
 *      device's `current_user`).
 *   4. If [startEngine] is true **and** the user's `app_settings.json` already
 *      contains valid sync credentials (restart-rehydration case), starts the
 *      sync engine + periodic pull loop immediately.
 *
 * On server start [rehydrateAll] does step 4 for every user dir with sync credentials, so the
 * web keeps syncing before the user's first request.  On login ([bootstrapUserSync]) the
 * registry seeds sync settings, waits (bounded) for the first pull, and starts the pull loop.
 *
 * All pull loops are cancelled and the engines closed via [stopAll] on `ApplicationStopping`.
 *
 * @param baseDir              Root data directory (e.g. `~/.llm-api-web`).
 * @param syncServerUrl        Base URL of the sync server.
 * @param pullIntervalSeconds  Seconds between periodic background pulls.
 * @param startEngine          When false the sync engine and pull loop are NEVER
 *                             started — used in tests to suppress network calls.
 * @param loginPullTimeoutMs   How long a login waits for the user's first pull before the
 *                             callback redirects (the pull itself keeps running).
 * @param chatEngineFactory    Factory that creates a [ChatEngine] from a [DataRepository].
 * @param titleGeneratorFactory Factory that creates a [TitleGenerator] from a [DataRepository].
 */
class UserRegistry(
    private val baseDir: File,
    private val syncServerUrl: String = resolveSyncServerUrl(),
    private val pullIntervalSeconds: Long = 20L,
    val startEngine: Boolean = true,
    private val loginPullTimeoutMs: Long = LOGIN_PULL_TIMEOUT_MS,
    private val chatEngineFactory: (DataRepository) -> ChatEngine = { repo -> RepositoryChatEngine(repo) },
    private val titleGeneratorFactory: (DataRepository) -> TitleGenerator = { repo ->
        TitleGenerator { username, chatId, provider ->
            repo.generateConversationTitle(username, chatId, provider)
        }
    }
) {
    companion object {
        /** Bounded wait of a login for the user's first pull (plan §6). */
        const val LOGIN_PULL_TIMEOUT_MS = 8_000L

        private const val SHUTDOWN_FLUSH_TIMEOUT_MS = 3_000L
    }

    private val log = LoggerFactory.getLogger(UserRegistry::class.java)
    private val mutex = Mutex()
    private val contexts = HashMap<String, UserContext>()
    private val pullScopes = HashMap<String, CoroutineScope>()
    private val pullScopesLock = Any()

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

        pinDeviceSettings(username, repository)

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

    /**
     * The web's device-local settings belong to the server, not to the synced copy: the
     * `current_user` (which names the synced per-user files) is always the session username,
     * and an enabled sync always talks to this server's sync URL.  Only rewrites an existing
     * settings file (a user without one has nothing to sync yet).
     */
    private fun pinDeviceSettings(username: String, repository: DataRepository) {
        if (!File(storageFilesDir(username), APP_SETTINGS_FILE).exists()) return
        repository.updateAppSettings { current ->
            var updated = current
            if (updated.current_user != username) {
                log.warn("Pinning current_user of '$username' (was '${updated.current_user}')")
                updated = updated.copy(current_user = username)
            }
            if (updated.remoteSync.enabled && updated.remoteSync.serverBaseUrl != syncServerUrl) {
                updated = updated.copy(remoteSync = updated.remoteSync.copy(serverBaseUrl = syncServerUrl))
            }
            updated
        }
    }

    private fun storageFilesDir(username: String): File = File(baseDir, "users/$username/files/llm_data")

    // ── Startup rehydration ───────────────────────────────────────────────────

    /**
     * Start sync (engine + pull loop) for every `users/<u>` dir whose settings have sync enabled
     * and a token, so the web keeps syncing before the user's first request after a restart.
     * No-op when [startEngine] is false.  Returns the rehydrated usernames.
     */
    suspend fun rehydrateAll(): List<String> {
        if (!startEngine) return emptyList()
        val userDirs = File(baseDir, "users").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?: return emptyList()
        val started = mutableListOf<String>()
        for (dir in userDirs) {
            val username = dir.name
            val settingsFile = File(storageFilesDir(username), APP_SETTINGS_FILE)
            if (!settingsFile.isFile) continue
            val settings = try {
                JsonConfig.prettyPrint.decodeFromString<AppSettings>(settingsFile.readText())
            } catch (e: Exception) {
                log.warn("Skipping rehydration of '$username': unreadable settings (${e.message})")
                continue
            }
            if (!settings.remoteSync.enabled || settings.remoteSync.authToken.isBlank()) continue
            try {
                context(username)  // creating the context starts the engine + pull loop
                started += username
            } catch (e: Exception) {
                log.warn("Rehydration of '$username' failed: ${e.message}")
            }
        }
        if (started.isNotEmpty()) log.info("Rehydrated sync for ${started.size} user(s): $started")
        return started
    }

    // ── Sync bootstrap (called at Google login callback) ──────────────────────

    /**
     * Seeds sync credentials into the user's `app_settings.json`, clears a stale
     * re-authentication flag, runs the user's first pull (waiting at most
     * [loginPullTimeoutMs] for it, so a returning user's data is on disk before the
     * login redirect) and starts the periodic pull loop.
     *
     * Idempotent: calling it a second time (e.g. re-login) updates the token
     * and keeps the existing pull loop.
     *
     * The settings write is a transform of the on-disk settings: only the device-local
     * keys (`remoteSync`, `current_user`) change, so the account's synced settings are
     * never replaced by this device's copy (the first sync of a new device has no base:
     * the account's values win).
     *
     * @param username  Canonical username from the sync server.
     * @param email     Google account email (stored for display).
     * @param syncToken Bearer token minted by the sync server.
     */
    suspend fun bootstrapUserSync(username: String, email: String, syncToken: String) {
        val ctx = context(username)
        val repo = ctx.repository
        val engine = repo.syncEngine

        // Not under engine.runExclusive: that would wait (unbounded) for a pull in flight, and
        // the web needs no migration. A state reset meanwhile is generation-guarded.
        // The web's dir only ever holds this account: a reset state is re-owned here, a
        // state of this account keeps its bases (3-way merges)
        engine.prepareForSignIn(username, MigrationResult.NoOp)
        repo.updateAppSettings { current ->
            current.copy(
                remoteSync = current.remoteSync.copy(
                    enabled = true,
                    serverBaseUrl = syncServerUrl,
                    authToken = syncToken,
                    accountEmail = email
                    // syncApiKeys: the user's per-device opt-in survives re-login
                ),
                current_user = username
            )
        }
        // A new token: resume a sync stopped by a 401
        engine.clearReauth()
        log.info("Sync credentials seeded for user '$username'")

        if (!startEngine) return

        val scope = launchPullLoop(username, repo)
        // One pull (not cancelled by the timeout: it finishes in the background)
        val firstPull = scope.launch { engine.pull() }
        val finished = withTimeoutOrNull(loginPullTimeoutMs) { firstPull.join() } != null
        if (!finished) log.warn("First pull for '$username' still running after ${loginPullTimeoutMs}ms; continuing login")
    }

    // ── Pull loop ─────────────────────────────────────────────────────────────

    /** Start the periodic pull loop of [username] (once) and return its scope. */
    private fun launchPullLoop(username: String, repo: DataRepository): CoroutineScope = synchronized(pullScopesLock) {
        pullScopes[username]?.let {
            log.debug("Pull loop already running for '$username' — skipping")
            return it
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
        scope
    }

    /** Usernames whose pull loop is running (tests / diagnostics). */
    fun syncingUsers(): Set<String> = synchronized(pullScopesLock) { pullScopes.keys.toSet() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Cancels all per-user pull loops, flushes pending uploads (bounded) and closes every
     * user's sync engine.  Called on `ApplicationStopping` so threads do not linger after
     * the server shuts down.
     */
    fun stopAll() {
        synchronized(pullScopesLock) {
            pullScopes.values.forEach { it.cancel() }
            pullScopes.clear()
        }
        val repos = runBlocking { mutex.withLock { contexts.values.map { it.repository } } }
        if (startEngine) {
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MS) {
                    for (repo in repos) {
                        try {
                            repo.syncEngine.flushPendingUploads()
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            log.warn("Flushing uploads on shutdown failed: ${e.message}")
                        }
                    }
                }
            }
        }
        repos.forEach { it.syncEngine.close() }
        log.info("All pull loops stopped")
    }
}

private const val APP_SETTINGS_FILE = "app_settings.json"

/** The sync server URL from `SYNC_SERVER_URL` (default: the local sync server). */
fun resolveSyncServerUrl(): String =
    System.getenv("SYNC_SERVER_URL")?.takeIf { it.isNotBlank() } ?: "http://localhost:8090"
