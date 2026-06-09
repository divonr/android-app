package com.example.ApI.server

import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.oauth.OAuthTokenExchanger
import com.example.ApI.server.oauth.RealOAuthTokenExchanger
import com.example.ApI.server.streaming.ChatEngine
import com.example.ApI.server.streaming.RepositoryChatEngine
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
 * A single [DataRepository] is created at startup (with [ServerPlatformStorage])
 * and stored here so all route extensions can access it via
 * `application.appModule.repository`.
 *
 * A [ChatEngine] is also stored so that the streaming route can be tested with a
 * fake engine that drives [StreamingCallback] deterministically without network calls.
 * In production [chatEngine] is a [RepositoryChatEngine]; tests supply a [FakeChatEngine].
 *
 * An [OAuthTokenExchanger] is stored so that OAuth routes can be tested with a fake
 * exchanger that returns scripted results without making real network calls.
 *
 * A [TitleGenerator] is stored so that the generate-title route can be tested with a
 * fake that returns a deterministic title without real LLM calls.
 */
class AppModule(
    val repository: DataRepository,
    val chatEngine: ChatEngine = RepositoryChatEngine(repository),
    val oauthExchanger: OAuthTokenExchanger = RealOAuthTokenExchanger(),
    val titleGenerator: TitleGenerator = object : TitleGenerator {
        override suspend fun generate(username: String, chatId: String, provider: String?): String =
            repository.generateConversationTitle(username, chatId, provider)
    }
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
