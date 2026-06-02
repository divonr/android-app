package com.example.ApI.ui.managers.io

import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Manages chat export and import functionality.
 * Desktop adaptation: no Android share intents or notifications.
 * Uses clipboard (AWT), file saves to user home, and system file dialogs.
 */
class ExportImportManager(
    private val deps: ManagerDependencies,
    private val selectChat: (Chat) -> Unit,
    private val navigateToScreen: (Screen) -> Unit
) {

    fun openChatExportDialog() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        deps.scope.launch {
            val chatJson = withContext(Dispatchers.IO) {
                deps.repository.getChatJson(currentUser, currentChat.chat_id)
            }.orEmpty()

            val hasShareLink = currentChat.shareLink.isNotEmpty() && currentChat.shareId.isNotEmpty()

            deps.updateUiState(deps.uiState.value.copy(
                showChatExportDialog = true,
                chatExportJson = chatJson,
                isChatExportEditable = false,
                isShareLinkActive = hasShareLink,
                isShareLinkLoading = false,
                showShareLinkMenu = false
            ))

            if (chatJson.isBlank()) {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "No content to export"))
            }
        }
    }

    fun closeChatExportDialog() {
        deps.updateUiState(deps.uiState.value.copy(
            showChatExportDialog = false,
            isChatExportEditable = false,
            isShareLinkActive = false,
            isShareLinkLoading = false,
            showShareLinkMenu = false
        ))
    }

    // ==================== Share Link ====================

    private val httpClient = OkHttpClient()
    private val SHARE_API_BASE = "https://api-divonr.xyz/share"

    private fun generateEncryptionKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun encryptAES(plaintext: String, hexKey: String): String {
        val keyBytes = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun copyToClipboard(text: String) {
        try {
            val selection = StringSelection(text)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        } catch (e: Exception) {
            Log.e("ExportImportManager", "Failed to copy to clipboard", e)
        }
    }

    fun createShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val content = deps.uiState.value.chatExportJson
        val currentUser = deps.appSettings.value.current_user

        if (content.isBlank()) {
            deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "No content to export"))
            return
        }

        deps.updateUiState(deps.uiState.value.copy(isShareLinkLoading = true))

        deps.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val key = generateEncryptionKey()
                    val encryptedContent = encryptAES(content, key)
                    val requestBody = encryptedContent.toRequestBody("text/plain".toMediaType())
                    val request = Request.Builder().url(SHARE_API_BASE).post(requestBody).build()
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("Server error: ${response.code}")
                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    val jsonResponse = KJson.parseToJsonElement(responseBody).jsonObject
                    val uuid = jsonResponse["id"]?.jsonPrimitive?.content ?: throw Exception("No id in response")
                    val fullLink = "https://api-divonr.xyz/viewer/?id=$uuid#key=$key"
                    deps.repository.updateChatShareLink(currentUser, currentChat.chat_id, fullLink, uuid)
                    fullLink
                }

                copyToClipboard(result)

                val updatedHistory = withContext(Dispatchers.IO) { deps.repository.loadChatHistory(currentUser) }
                val updatedChat = updatedHistory.chat_history.find { it.chat_id == currentChat.chat_id }

                deps.updateUiState(deps.uiState.value.copy(
                    isShareLinkLoading = false,
                    isShareLinkActive = true,
                    showShareLinkMenu = true,
                    currentChat = updatedChat ?: currentChat,
                    chatHistory = updatedHistory.chat_history,
                    snackbarMessage = "Link created and copied to clipboard"
                ))
            } catch (e: Exception) {
                Log.e("ShareLink", "Failed to create share link", e)
                deps.updateUiState(deps.uiState.value.copy(
                    isShareLinkLoading = false,
                    snackbarMessage = "Error creating link: ${e.message}"
                ))
            }
        }
    }

    fun deleteShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val shareId = currentChat.shareId
        val currentUser = deps.appSettings.value.current_user
        if (shareId.isEmpty()) return

        deps.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url("$SHARE_API_BASE/$shareId").delete().build()
                    httpClient.newCall(request).execute()
                    deps.repository.updateChatShareLink(currentUser, currentChat.chat_id, "", "")
                }
                val updatedHistory = withContext(Dispatchers.IO) { deps.repository.loadChatHistory(currentUser) }
                val updatedChat = updatedHistory.chat_history.find { it.chat_id == currentChat.chat_id }
                deps.updateUiState(deps.uiState.value.copy(
                    isShareLinkActive = false,
                    showShareLinkMenu = false,
                    currentChat = updatedChat ?: currentChat,
                    chatHistory = updatedHistory.chat_history,
                    snackbarMessage = "Link removed"
                ))
            } catch (e: Exception) {
                Log.e("ShareLink", "Failed to delete share link", e)
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error removing link: ${e.message}"))
            }
        }
    }

    private fun extractKeyFromShareLink(link: String): String? {
        val hashIndex = link.indexOf("#key=")
        return if (hashIndex != -1) link.substring(hashIndex + 5) else null
    }

    fun updateShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val shareId = currentChat.shareId
        val existingLink = currentChat.shareLink
        val content = deps.uiState.value.chatExportJson
        val currentUser = deps.appSettings.value.current_user

        if (shareId.isEmpty()) { createShareLink(); return }
        val existingKey = extractKeyFromShareLink(existingLink) ?: run { createShareLink(); return }
        if (content.isBlank()) {
            deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "No content to export"))
            return
        }

        deps.updateUiState(deps.uiState.value.copy(isShareLinkLoading = true, showShareLinkMenu = false))

        deps.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val encryptedContent = encryptAES(content, existingKey)
                    val requestBody = encryptedContent.toRequestBody("text/plain".toMediaType())
                    val request = Request.Builder().url("$SHARE_API_BASE/$shareId").put(requestBody).build()
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("Server error: ${response.code}")
                }
                copyToClipboard(existingLink)
                deps.updateUiState(deps.uiState.value.copy(
                    isShareLinkLoading = false,
                    showShareLinkMenu = true,
                    snackbarMessage = "Link updated and copied to clipboard"
                ))
            } catch (e: Exception) {
                Log.e("ShareLink", "Failed to update share link", e)
                deps.updateUiState(deps.uiState.value.copy(
                    isShareLinkLoading = false,
                    snackbarMessage = "Error updating link: ${e.message}"
                ))
            }
        }
    }

    fun copyShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val link = currentChat.shareLink
        if (link.isNotEmpty()) {
            copyToClipboard(link)
            deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Link copied to clipboard"))
        }
    }

    fun toggleShareLinkMenu() {
        deps.updateUiState(deps.uiState.value.copy(showShareLinkMenu = !deps.uiState.value.showShareLinkMenu))
    }

    fun dismissShareLinkMenu() {
        deps.updateUiState(deps.uiState.value.copy(showShareLinkMenu = false))
    }

    fun enableChatExportEditing() {
        deps.updateUiState(deps.uiState.value.copy(isChatExportEditable = true))
    }

    fun updateChatExportContent(content: String) {
        deps.updateUiState(deps.uiState.value.copy(chatExportJson = content))
    }

    /** Desktop: save export JSON to user's Downloads folder (no Android share intent). */
    fun shareChatExportContent() {
        saveChatExportToDownloads()
    }

    fun saveChatExportToDownloads() {
        val content = deps.uiState.value.chatExportJson
        val chatId = deps.uiState.value.currentChat?.chat_id

        if (content.isBlank()) {
            deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "No content to export"))
            return
        }
        if (chatId == null) {
            deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "No chat selected"))
            return
        }

        deps.scope.launch {
            val result = withContext(Dispatchers.IO) {
                deps.repository.saveChatJsonToDownloads(chatId, content)
            }
            if (result != null) {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Exported to: $result"))
            } else {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Export failed"))
            }
        }
    }

    // ==================== Chat Import ====================

    fun importPendingChatJson() {
        val pending = deps.uiState.value.pendingChatImport ?: return
        deps.scope.launch {
            try {
                val currentUser = deps.appSettings.value.current_user
                val importedChatId = deps.repository.importSingleChat(pending.jsonContent, currentUser)

                if (importedChatId != null) {
                    val chatHistory = deps.repository.loadChatHistory(currentUser)
                    deps.updateUiState(deps.uiState.value.copy(
                        chatHistory = chatHistory.chat_history,
                        groups = chatHistory.groups,
                        pendingChatImport = null
                    ))
                    val importedChat = chatHistory.chat_history.find { it.chat_id == importedChatId }
                    if (importedChat != null) {
                        selectChat(importedChat)
                        navigateToScreen(Screen.Chat)
                    }
                    deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Chat imported successfully"))
                } else {
                    deps.updateUiState(deps.uiState.value.copy(
                        pendingChatImport = null,
                        snackbarMessage = "Error importing chat"
                    ))
                }
            } catch (e: Exception) {
                deps.updateUiState(deps.uiState.value.copy(
                    pendingChatImport = null,
                    snackbarMessage = "Error importing chat: ${e.message}"
                ))
            }
        }
    }

    fun attachPendingJsonAsFile() {
        val pending = deps.uiState.value.pendingChatImport ?: return
        deps.updateUiState(deps.uiState.value.copy(pendingChatImport = null))
        // On desktop: save content to temp file and attach it
        deps.scope.launch {
            try {
                val tempFile = File.createTempFile(pending.fileName.removeSuffix(".json"), ".json")
                tempFile.writeText(pending.jsonContent)
                val fileData = tempFile.readBytes()
                val localPath = deps.repository.saveFileLocally(pending.fileName, fileData)
                if (localPath != null) {
                    val selectedFile = SelectedFile(uri = null, name = pending.fileName, mimeType = pending.mimeType, localPath = localPath)
                    deps.updateUiState(deps.uiState.value.copy(selectedFiles = deps.uiState.value.selectedFiles + selectedFile))
                }
                tempFile.delete()
            } catch (e: Exception) {
                println("Error attaching JSON as file: ${e.message}")
            }
        }
    }

    fun dismissChatImportDialog() {
        deps.updateUiState(deps.uiState.value.copy(pendingChatImport = null))
    }

    fun exportChatHistory() {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val exportPath = deps.repository.exportChatHistory(currentUser)
            if (exportPath != null) {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Chat history exported to: $exportPath"))
            }
        }
    }

    fun importChatHistoryFromFile(file: File) {
        deps.scope.launch {
            try {
                val data = file.readBytes()
                val currentUser = deps.appSettings.value.current_user
                deps.repository.importChatHistoryJson(data, currentUser)
                val chatHistory = deps.repository.loadChatHistory(currentUser)
                val currentChat = chatHistory.chat_history.lastOrNull()
                deps.updateUiState(deps.uiState.value.copy(
                    chatHistory = chatHistory.chat_history,
                    groups = chatHistory.groups,
                    currentChat = currentChat,
                    snackbarMessage = "Chat history imported successfully"
                ))
            } catch (e: Exception) {
                deps.updateUiState(deps.uiState.value.copy(snackbarMessage = "Error importing: ${e.message}"))
            }
        }
    }
}
