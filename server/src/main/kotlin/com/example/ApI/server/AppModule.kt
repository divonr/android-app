package com.example.ApI.server

import com.example.ApI.data.repository.DataRepository
import io.ktor.server.application.*

/**
 * Application-level dependency holder.
 *
 * A single [DataRepository] is created at startup (with [ServerPlatformStorage])
 * and stored here so all route extensions can access it via
 * `application.appModule.repository`.
 */
class AppModule(val repository: DataRepository)

// Ktor attribute key for AppModule
private val AppModuleKey = io.ktor.util.AttributeKey<AppModule>("AppModule")

/** Install (store) the [AppModule] in the application attributes. */
fun Application.installAppModule(module: AppModule) {
    attributes.put(AppModuleKey, module)
}

/** Retrieve the [AppModule] from the application attributes. */
val Application.appModule: AppModule
    get() = attributes[AppModuleKey]
