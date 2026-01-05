package com.example.ApI.data.repository

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.CustomProviderConfig
import com.example.ApI.data.model.FullCustomProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Manages local storage operations for API keys, app settings, and file attachments.
 */
class LocalStorageManager(
    private val internalDir: File,
    private val json: Json
) {
    // ============ API Keys ============

    fun loadApiKeys(username: String): List<ApiKey> {
        val file = File(internalDir, "api_keys_$username.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<List<ApiKey>>(content)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveApiKeys(username: String, apiKeys: List<ApiKey>) {
        val file = File(internalDir, "api_keys_$username.json")
        try {
            file.writeText(json.encodeToString(apiKeys))
        } catch (e: IOException) {
            // Handle error
        }
    }

    fun addApiKey(username: String, apiKey: ApiKey) {
        val currentKeys = loadApiKeys(username).toMutableList()

        // If adding a new key for same provider and it should be active,
        // deactivate all other keys for this provider
        if (apiKey.isActive) {
            for (i in currentKeys.indices) {
                if (currentKeys[i].provider == apiKey.provider) {
                    currentKeys[i] = currentKeys[i].copy(isActive = false)
                }
            }
        }

        currentKeys.add(apiKey)
        saveApiKeys(username, currentKeys)
    }

    fun toggleApiKeyStatus(username: String, keyId: String) {
        val currentKeys = loadApiKeys(username)
        val targetKey = currentKeys.find { it.id == keyId } ?: return
        val updatedKeys = currentKeys.map { key ->
            when {
                key.id == keyId -> {
                    // Toggle this key
                    val newActiveState = !key.isActive
                    // If activating this key, deactivate all other keys for same provider
                    if (newActiveState) {
                        key.copy(isActive = true)
                    } else {
                        key.copy(isActive = false)
                    }
                }
                key.provider == targetKey.provider && key.id != keyId && !targetKey.isActive -> {
                    // If we're activating the target key, deactivate others of same provider
                    key.copy(isActive = false)
                }
                else -> key
            }
        }
        saveApiKeys(username, updatedKeys)
    }

    fun deleteApiKey(username: String, keyId: String) {
        val currentKeys = loadApiKeys(username)
        val updatedKeys = currentKeys.filter { it.id != keyId }
        saveApiKeys(username, updatedKeys)
    }

    fun reorderApiKeys(username: String, fromIndex: Int, toIndex: Int) {
        val currentKeys = loadApiKeys(username).toMutableList()
        if (fromIndex in currentKeys.indices && toIndex in currentKeys.indices) {
            val item = currentKeys.removeAt(fromIndex)
            currentKeys.add(toIndex, item)
            saveApiKeys(username, currentKeys)
        }
    }

    // ============ App Settings ============

    fun loadAppSettings(): AppSettings {
        val file = File(internalDir, "app_settings.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<AppSettings>(content)
            } catch (e: Exception) {
                AppSettings(
                    current_user = "default",
                    selected_provider = "openai",
                    selected_model = "gpt-4o"
                )
            }
        } else {
            AppSettings(
                current_user = "default",
                selected_provider = "openai",
                selected_model = "gpt-4o"
            )
        }
    }

    fun saveAppSettings(settings: AppSettings) {
        val file = File(internalDir, "app_settings.json")
        try {
            file.writeText(json.encodeToString(settings))
        } catch (e: IOException) {
            // Handle error
        }
    }

    // ============ Custom Providers ============

    fun loadCustomProviders(username: String): List<CustomProviderConfig> {
        val file = File(internalDir, "custom_providers_$username.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<List<CustomProviderConfig>>(content)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveCustomProviders(username: String, providers: List<CustomProviderConfig>) {
        val file = File(internalDir, "custom_providers_$username.json")
        try {
            file.writeText(json.encodeToString(providers))
        } catch (e: IOException) {
            // Handle error
        }
    }

    fun addCustomProvider(username: String, provider: CustomProviderConfig) {
        val current = loadCustomProviders(username).toMutableList()
        current.add(provider)
        saveCustomProviders(username, current)
    }

    fun updateCustomProvider(username: String, providerId: String, updated: CustomProviderConfig) {
        val current = loadCustomProviders(username).toMutableList()
        val index = current.indexOfFirst { it.id == providerId }
        if (index >= 0) {
            current[index] = updated
            saveCustomProviders(username, current)
        }
    }

    fun deleteCustomProvider(username: String, providerId: String) {
        val current = loadCustomProviders(username).filter { it.id != providerId }
        saveCustomProviders(username, current)
    }

    // ============ Full Custom Providers ============

    fun loadFullCustomProviders(username: String): List<FullCustomProviderConfig> {
        val file = File(internalDir, "full_custom_providers_$username.json")
        return if (file.exists()) {
            try {
                val content = file.readText()
                json.decodeFromString<List<FullCustomProviderConfig>>(content)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveFullCustomProviders(username: String, providers: List<FullCustomProviderConfig>) {
        val file = File(internalDir, "full_custom_providers_$username.json")
        try {
            file.writeText(json.encodeToString(providers))
        } catch (e: IOException) {
            // Handle error
        }
    }

    fun addFullCustomProvider(username: String, provider: FullCustomProviderConfig) {
        val current = loadFullCustomProviders(username).toMutableList()
        current.add(provider)
        saveFullCustomProviders(username, current)
    }

    fun updateFullCustomProvider(username: String, providerId: String, updated: FullCustomProviderConfig) {
        val current = loadFullCustomProviders(username).toMutableList()
        val index = current.indexOfFirst { it.id == providerId }
        if (index >= 0) {
            current[index] = updated
            saveFullCustomProviders(username, current)
        }
    }

    fun deleteFullCustomProvider(username: String, providerId: String) {
        val current = loadFullCustomProviders(username).filter { it.id != providerId }
        saveFullCustomProviders(username, current)
    }

    // ============ File Management ============

    fun saveFileLocally(fileName: String, data: ByteArray): String? {
        val filesDir = File(internalDir, "attachments")
        if (!filesDir.exists()) {
            filesDir.mkdirs()
        }

        val file = File(filesDir, "${UUID.randomUUID()}_$fileName")
        return try {
            file.writeBytes(data)
            file.absolutePath
        } catch (e: IOException) {
            null
        }
    }

    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            false
        }
    }
}
