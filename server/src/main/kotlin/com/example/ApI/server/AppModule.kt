package com.example.ApI.server

import com.example.ApI.server.auth.GoogleTokenVerifier
import com.example.ApI.server.auth.RealGoogleTokenVerifier
import com.example.ApI.server.auth.RealSyncAuthClient
import com.example.ApI.server.auth.SyncAuthClient
import com.example.ApI.server.oauth.OAuthTokenExchanger
import com.example.ApI.server.oauth.RealOAuthTokenExchanger
import com.example.ApI.server.runs.RunRegistry
import io.ktor.server.application.*

/**
 * Seam for title generation — allows tests to inject a fake that returns a
 * deterministic title without making real LLM network calls.
 *
 * Declared as a `fun interface` so tests can use SAM lambda syntax:
 * `TitleGenerator { username, chatId, provider -> "Fake Title" }`
 */
fun interface TitleGenerator {
    suspend fun generate(username: String, chatId: String, provider: String?): String
}

/**
 * Application-level dependency holder.
 *
 * A [UserRegistry] maps each authenticated username to its own isolated
 * [UserContext] (storage + repository + chat engine + title generator).  Route
 * handlers resolve their context via `call.userContext()` rather than accessing
 * a single shared repository.
 *
 * Injectable dependencies (for testing without real network calls):
 * - [oauthExchanger] — GitHub / Google Workspace token exchange.
 * - [googleTokenVerifier] — Google ID token validation (login flow).
 * - [syncAuthClient] — exchange ID token with the sync server for a bearer token.
 *
 * The sync-token bearer authentication of `/api` (its whoami seam included) is wired in
 * [module] as a Ktor authentication provider.
 */
class AppModule(
    val registry: UserRegistry,
    val oauthExchanger: OAuthTokenExchanger = RealOAuthTokenExchanger(),
    val googleTokenVerifier: GoogleTokenVerifier = RealGoogleTokenVerifier(),
    val syncAuthClient: SyncAuthClient = RealSyncAuthClient(),
    /** Owns every LLM run (cloud runs and the web's own sends). */
    val runRegistry: RunRegistry = RunRegistry(),
    /** How often (ms) an idle run event stream sends a ping comment. */
    val runPingIntervalMs: Long = 15_000L
)

// Ktor attribute key for AppModule
private val AppModuleKey = io.ktor.util.AttributeKey<AppModule>("AppModule")

/** Install (store) the [AppModule] in the application attributes. */
fun Application.installAppModule(module: AppModule) {
    attributes.put(AppModuleKey, module)
}

/** Retrieve the [AppModule] from the application attributes. */
val Application.appModule: AppModule
    get() = attributes[AppModuleKey]
