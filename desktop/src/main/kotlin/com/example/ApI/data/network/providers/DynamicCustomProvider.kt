package com.example.ApI.data.network.providers

import android.content.Context
import com.example.ApI.data.model.CustomProviderConfig
import java.net.HttpURLConnection

/**
 * Dynamic provider that can be configured at runtime for user-defined
 * OpenAI-compatible endpoints. Extends OpenAICompatibleProvider to reuse
 * all streaming, tool calling, and reasoning detection logic.
 */
class DynamicCustomProvider(
    context: Context,
    private val config: CustomProviderConfig
) : OpenAICompatibleProvider(context) {

    override val providerName: String = config.providerKey

    override val logTag: String = "CustomProvider[${config.name}]"

    override fun addCustomHeaders(connection: HttpURLConnection) {
        // Add extra headers from config
        config.extraHeaders.forEach { (name, value) ->
            connection.setRequestProperty(name, value)
        }
    }

    override fun applyAuthorizationHeader(connection: HttpURLConnection, apiKey: String) {
        // Use the custom auth header format
        val headerValue = config.authHeaderFormat.replace("{key}", apiKey)
        connection.setRequestProperty(config.authHeaderName, headerValue)
    }
}
