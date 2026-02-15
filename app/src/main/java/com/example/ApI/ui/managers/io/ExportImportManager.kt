package com.example.ApI.ui.managers.io

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages chat export and import functionality.
 * Handles single chat export, chat history import, and file sharing.
 * Extracted from ChatViewModel to reduce complexity.
 */
class ExportImportManager(
    private val deps: ManagerDependencies,
    private val selectChat: (Chat) -> Unit,
    private val navigateToScreen: (Screen) -> Unit,
    private val addFileFromUri: (Uri, String, String) -> Unit
) {

    // ==================== Chat Export ====================

    /**
     * Open the chat export dialog for the current chat.
     * Loads the chat JSON and displays it for editing/sharing.
     */
    fun openChatExportDialog() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val currentUser = deps.appSettings.value.current_user
        deps.scope.launch {
            val chatJson = withContext(Dispatchers.IO) {
                deps.repository.getChatJson(currentUser, currentChat.chat_id)
            }.orEmpty()

            // Check if this chat already has a share link
            val hasShareLink = currentChat.shareLink.isNotEmpty() && currentChat.shareId.isNotEmpty()

            deps.updateUiState(
                deps.uiState.value.copy(
                    showChatExportDialog = true,
                    chatExportJson = chatJson,
                    isChatExportEditable = false,
                    isShareLinkActive = hasShareLink,
                    isShareLinkLoading = false,
                    showShareLinkMenu = false
                )
            )

            if (chatJson.isBlank()) {
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = deps.context.getString(R.string.no_content_to_export)
                    )
                )
            }
        }
    }

    /**
     * Close the chat export dialog.
     */
    fun closeChatExportDialog() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showChatExportDialog = false,
                isChatExportEditable = false,
                isShareLinkActive = false,
                isShareLinkLoading = false,
                showShareLinkMenu = false
            )
        )
    }

    // ==================== Share Link ====================

    private val httpClient = OkHttpClient()
    private val SHARE_API_BASE = "https://api-divonr.xyz/share"

    /**
     * Generate a random 32-character hex key for AES encryption.
     */
    private fun generateEncryptionKey(): String {
        val bytes = ByteArray(16) // 16 bytes = 32 hex chars
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypt a string using AES/CBC/PKCS5Padding with the given hex key.
     * Returns the IV (16 bytes) prepended to the ciphertext, all Base64-encoded.
     */
    private fun encryptAES(plaintext: String, hexKey: String): String {
        val keyBytes = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Prepend IV to ciphertext
        val combined = iv + encrypted
        return android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
    }

    /**
     * Copy text to clipboard.
     */
    private fun copyToClipboard(text: String) {
        val clipboard = deps.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Share Link", text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Create a new share link for the current chat.
     * Encrypts the chat JSON with AES, uploads to server, saves link info locally.
     */
    fun createShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val content = deps.uiState.value.chatExportJson
        val currentUser = deps.appSettings.value.current_user

        if (content.isBlank()) {
            deps.updateUiState(
                deps.uiState.value.copy(
                    snackbarMessage = deps.context.getString(R.string.no_content_to_export)
                )
            )
            return
        }

        // Show loading state
        deps.updateUiState(deps.uiState.value.copy(isShareLinkLoading = true))

        deps.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Generate encryption key
                    val key = generateEncryptionKey()

                    // Encrypt the chat JSON
                    val encryptedContent = encryptAES(content, key)

                    // Upload to server
                    val requestBody = encryptedContent.toRequestBody("text/plain".toMediaType())
                    val request = Request.Builder()
                        .url(SHARE_API_BASE)
                        .post(requestBody)
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        throw Exception("Server error: ${response.code}")
                    }

                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    val jsonResponse = JSONObject(responseBody)
                    val uuid = jsonResponse.getString("id")

                    // Build the full link
                    val fullLink = "https://api-divonr.xyz/viewer/?id=$uuid#key=$key"

                    // Save share link info to chat locally
                    deps.repository.updateChatShareLink(currentUser, currentChat.chat_id, fullLink, uuid)

                    fullLink
                }

                // Copy link to clipboard
                copyToClipboard(result)

                // Refresh current chat to get updated share link fields
                val updatedHistory = withContext(Dispatchers.IO) {
                    deps.repository.loadChatHistory(currentUser)
                }
                val updatedChat = updatedHistory.chat_history.find { it.chat_id == currentChat.chat_id }

                // Update UI state
                deps.updateUiState(
                    deps.uiState.value.copy(
                        isShareLinkLoading = false,
                        isShareLinkActive = true,
                        showShareLinkMenu = true,
                        currentChat = updatedChat ?: currentChat,
                        chatHistory = updatedHistory.chat_history,
                        snackbarMessage = "הקישור נוצר והועתק ללוח"
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("ShareLink", "Failed to create share link", e)
                deps.updateUiState(
                    deps.uiState.value.copy(
                        isShareLinkLoading = false,
                        snackbarMessage = "שגיאה ביצירת קישור: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Delete the existing share link for the current chat.
     */
    fun deleteShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val shareId = currentChat.shareId
        val currentUser = deps.appSettings.value.current_user

        if (shareId.isEmpty()) return

        deps.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Delete from server
                    val request = Request.Builder()
                        .url("$SHARE_API_BASE/$shareId")
                        .delete()
                        .build()
                    httpClient.newCall(request).execute()

                    // Clear locally
                    deps.repository.updateChatShareLink(currentUser, currentChat.chat_id, "", "")
                }

                // Refresh current chat
                val updatedHistory = withContext(Dispatchers.IO) {
                    deps.repository.loadChatHistory(currentUser)
                }
                val updatedChat = updatedHistory.chat_history.find { it.chat_id == currentChat.chat_id }

                deps.updateUiState(
                    deps.uiState.value.copy(
                        isShareLinkActive = false,
                        showShareLinkMenu = false,
                        currentChat = updatedChat ?: currentChat,
                        chatHistory = updatedHistory.chat_history,
                        snackbarMessage = "הקישור הוסר בהצלחה"
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("ShareLink", "Failed to delete share link", e)
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = "שגיאה במחיקת קישור: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Update the share link: delete existing, then create new.
     */
    fun updateShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val shareId = currentChat.shareId
        val currentUser = deps.appSettings.value.current_user

        if (shareId.isEmpty()) {
            createShareLink()
            return
        }

        // Show loading state
        deps.updateUiState(deps.uiState.value.copy(
            isShareLinkLoading = true,
            showShareLinkMenu = false
        ))

        deps.scope.launch {
            try {
                // Delete old link from server
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$SHARE_API_BASE/$shareId")
                        .delete()
                        .build()
                    httpClient.newCall(request).execute()
                    deps.repository.updateChatShareLink(currentUser, currentChat.chat_id, "", "")
                }

                // Reset loading state temporarily (createShareLink will set it again)
                deps.updateUiState(deps.uiState.value.copy(
                    isShareLinkActive = false,
                    isShareLinkLoading = false
                ))

                // Create new link
                createShareLink()
            } catch (e: Exception) {
                android.util.Log.e("ShareLink", "Failed to update share link", e)
                deps.updateUiState(
                    deps.uiState.value.copy(
                        isShareLinkLoading = false,
                        snackbarMessage = "שגיאה בעדכון קישור: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Copy the existing share link to clipboard.
     */
    fun copyShareLink() {
        val currentChat = deps.uiState.value.currentChat ?: return
        val link = currentChat.shareLink
        if (link.isNotEmpty()) {
            copyToClipboard(link)
            deps.updateUiState(
                deps.uiState.value.copy(
                    snackbarMessage = "הקישור הועתק ללוח"
                )
            )
        }
    }

    /**
     * Toggle the share link floating menu visibility.
     */
    fun toggleShareLinkMenu() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showShareLinkMenu = !deps.uiState.value.showShareLinkMenu
            )
        )
    }

    /**
     * Dismiss the share link floating menu.
     */
    fun dismissShareLinkMenu() {
        deps.updateUiState(
            deps.uiState.value.copy(
                showShareLinkMenu = false
            )
        )
    }

    /**
     * Enable editing of the chat export content.
     */
    fun enableChatExportEditing() {
        deps.updateUiState(deps.uiState.value.copy(isChatExportEditable = true))
    }

    /**
     * Update the chat export content (when user edits it).
     */
    fun updateChatExportContent(content: String) {
        deps.updateUiState(deps.uiState.value.copy(chatExportJson = content))
    }

    /**
     * Share the chat export content via Android share sheet.
     * Saves to a temporary file and launches share intent.
     */
    fun shareChatExportContent() {
        val content = deps.uiState.value.chatExportJson
        val chatId = deps.uiState.value.currentChat?.chat_id

        if (content.isBlank()) {
            deps.updateUiState(
                deps.uiState.value.copy(
                    snackbarMessage = deps.context.getString(R.string.no_content_to_export)
                )
            )
            return
        }

        if (chatId == null) {
            deps.updateUiState(
                deps.uiState.value.copy(
                    snackbarMessage = deps.context.getString(R.string.error_sending_message)
                )
            )
            return
        }

        deps.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    deps.repository.saveChatJsonToDownloads(chatId, content)
                }

                if (result != null) {
                    // File was saved successfully, now share it
                    withContext(Dispatchers.Main) {
                        try {
                            val file = File(result)
                            val uri = FileProvider.getUriForFile(
                                deps.context,
                                deps.context.applicationContext.packageName + ".fileprovider",
                                file
                            )

                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }

                            val chooserTitle = deps.context.getString(R.string.share_chat_title)
                            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            deps.context.startActivity(chooser)
                        } catch (e: Exception) {
                            deps.updateUiState(
                                deps.uiState.value.copy(
                                    snackbarMessage = deps.context.getString(R.string.error_sending_message)
                                )
                            )
                        }
                    }
                } else {
                    deps.updateUiState(
                        deps.uiState.value.copy(
                            snackbarMessage = deps.context.getString(R.string.export_failed)
                        )
                    )
                }
            } catch (e: Exception) {
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = deps.context.getString(R.string.error_sending_message)
                    )
                )
            }
        }
    }

    /**
     * Save the chat export to the Downloads folder.
     * Shows a notification upon successful save.
     */
    fun saveChatExportToDownloads() {
        val content = deps.uiState.value.chatExportJson
        val chatId = deps.uiState.value.currentChat?.chat_id

        if (content.isBlank()) {
            deps.updateUiState(
                deps.uiState.value.copy(
                    snackbarMessage = deps.context.getString(R.string.no_content_to_export)
                )
            )
            return
        }

        if (chatId == null) {
            deps.updateUiState(
                deps.uiState.value.copy(
                    snackbarMessage = deps.context.getString(R.string.error_sending_message)
                )
            )
            return
        }

        deps.scope.launch {
            val result = withContext(Dispatchers.IO) {
                deps.repository.saveChatJsonToDownloads(chatId, content)
            }

            if (result != null) {
                // Show success notification
                showDownloadNotification(chatId)

                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = deps.context.getString(R.string.export_success)
                    )
                )
            } else {
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = deps.context.getString(R.string.export_failed)
                    )
                )
            }
        }
    }

    /**
     * Show a notification for successful chat download.
     * Includes a tap action to open the downloaded file.
     */
    private fun showDownloadNotification(chatId: String) {
        android.util.Log.d("ChatExport", "Attempting to show notification for chat: $chatId on Android ${android.os.Build.VERSION.SDK_INT}")

        try {
            // Check if POST_NOTIFICATIONS permission is required (Android 13+)
            val needsPermission = android.os.Build.VERSION.SDK_INT >= 33
            android.util.Log.d("ChatExport", "POST_NOTIFICATIONS permission required: $needsPermission")

            if (needsPermission) {
                // Check if we have the permission
                val hasPermission = ContextCompat.checkSelfPermission(
                    deps.context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                android.util.Log.d("ChatExport", "Has POST_NOTIFICATIONS permission: $hasPermission")

                if (!hasPermission) {
                    android.util.Log.w("ChatExport", "POST_NOTIFICATIONS permission not granted, cannot show notification")
                    return
                }
            }

            val notificationManager = NotificationManagerCompat.from(deps.context)

            // Create notification channel for Android 8.0+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "chat_export_channel",
                    "התראות ייצוא שיחה",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "התראות עבור פעולות ייצוא שיחה"
                }
                notificationManager.createNotificationChannel(channel)
            }

            // Create intent to open the downloaded file
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${chatId}.json")
            android.util.Log.d("ChatExport", "File path: ${file.absolutePath}")
            android.util.Log.d("ChatExport", "File exists: ${file.exists()}")

            if (file.exists()) {
                val fileUri = FileProvider.getUriForFile(
                    deps.context,
                    deps.context.applicationContext.packageName + ".fileprovider",
                    file
                )
                android.util.Log.d("ChatExport", "File URI: $fileUri")

                val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "application/json")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                try {
                    @Suppress("WrongConstant") // FLAG_IMMUTABLE is required for Android 12+
                    val pendingIntent = PendingIntentCompat.getActivity(
                        deps.context,
                        "chat_export_${chatId}".hashCode(),
                        openFileIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        false
                    )

                    val notification = NotificationCompat.Builder(deps.context, "chat_export_channel")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("שיחה הורדה בהצלחה")
                        .setContentText("הקובץ ${chatId}.json נשמר בתיקיית ההורדות")
                        .setPriority(NotificationCompat.PRIORITY_HIGH) // Higher priority for downloads
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent) // Open file when clicked
                        .build()

                    // Use a unique ID for each notification to avoid conflicts
                    val notificationId = "chat_export_${chatId}_${System.currentTimeMillis()}".hashCode()
                    notificationManager.notify(notificationId, notification)

                    android.util.Log.d("ChatExport", "Notification sent for chat: $chatId with file URI: $fileUri")
                } catch (e: Exception) {
                    android.util.Log.e("ChatExport", "Failed to create pending intent: ${e.message}")
                    // Create notification without click action if pending intent fails
                    val fallbackNotification = NotificationCompat.Builder(deps.context, "chat_export_channel")
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("שיחה הורדה בהצלחה")
                        .setContentText("הקובץ ${chatId}.json נשמר בתיקיית ההורדות")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build()

                    val notificationId = "chat_export_${chatId}_${System.currentTimeMillis()}".hashCode()
                    notificationManager.notify(notificationId, fallbackNotification)
                }
            } else {
                android.util.Log.w("ChatExport", "File does not exist at path: ${file.absolutePath}")
                // Create notification without click action if file doesn't exist
                val fallbackNotification = NotificationCompat.Builder(deps.context, "chat_export_channel")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("שיחה הורדה בהצלחה")
                    .setContentText("הקובץ ${chatId}.json נשמר בתיקיית ההורדות")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()

                val notificationId = "chat_export_${chatId}_${System.currentTimeMillis()}".hashCode()
                notificationManager.notify(notificationId, fallbackNotification)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatExport", "Error showing notification", e)
        }
    }

    // ==================== Chat Import ====================

    /**
     * Import a pending chat JSON file.
     * Creates a new chat from the JSON content and navigates to it.
     */
    fun importPendingChatJson() {
        val pending = deps.uiState.value.pendingChatImport ?: return

        deps.scope.launch {
            try {
                val currentUser = deps.appSettings.value.current_user
                val importedChatId = deps.repository.importSingleChat(pending.jsonContent, currentUser)

                if (importedChatId != null) {
                    // Reload chat history
                    val chatHistory = deps.repository.loadChatHistory(currentUser)
                    deps.updateUiState(
                        deps.uiState.value.copy(
                            chatHistory = chatHistory.chat_history,
                            groups = chatHistory.groups,
                            pendingChatImport = null
                        )
                    )

                    // Find and select the imported chat
                    val importedChat = chatHistory.chat_history.find { it.chat_id == importedChatId }
                    if (importedChat != null) {
                        selectChat(importedChat)
                        navigateToScreen(Screen.Chat)
                    }

                    Toast.makeText(deps.context, "הצ'אט יובא בהצלחה", Toast.LENGTH_SHORT).show()
                } else {
                    deps.updateUiState(deps.uiState.value.copy(pendingChatImport = null))
                    deps.updateUiState(
                        deps.uiState.value.copy(
                            snackbarMessage = "שגיאה בייבוא הצ'אט"
                        )
                    )
                }
            } catch (e: Exception) {
                deps.updateUiState(deps.uiState.value.copy(pendingChatImport = null))
                deps.updateUiState(
                    deps.uiState.value.copy(
                        snackbarMessage = "שגיאה בייבוא הצ'אט: ${e.message}"
                    )
                )
            }
        }
    }

    /**
     * Attach a pending JSON file as a regular file attachment instead of importing it.
     */
    fun attachPendingJsonAsFile() {
        val pending = deps.uiState.value.pendingChatImport ?: return

        // Clear the pending import
        deps.updateUiState(deps.uiState.value.copy(pendingChatImport = null))

        // Add as regular file attachment
        addFileFromUri(pending.uri, pending.fileName, pending.mimeType)
    }

    /**
     * Dismiss the chat import dialog without taking action.
     */
    fun dismissChatImportDialog() {
        deps.updateUiState(deps.uiState.value.copy(pendingChatImport = null))
    }

    // ==================== Full Chat History Export/Import ====================

    /**
     * Export entire chat history to a file.
     * Shows a toast with the export path on success.
     */
    fun exportChatHistory() {
        deps.scope.launch {
            val currentUser = deps.appSettings.value.current_user
            val exportPath = deps.repository.exportChatHistory(currentUser)

            if (exportPath != null) {
                Toast.makeText(
                    deps.context,
                    "היסטוריית הצ'אט יוצאה בהצלחה ל: $exportPath",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Import chat history from a URI.
     * Replaces current chat history with imported data.
     */
    fun importChatHistoryFromUri(uri: Uri) {
        deps.scope.launch {
            try {
                val inputStream: InputStream? = deps.context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val data = stream.readBytes()
                    val currentUser = deps.appSettings.value.current_user
                    deps.repository.importChatHistoryJson(data, currentUser)
                    // Refresh UI state after import
                    val chatHistory = deps.repository.loadChatHistory(currentUser)
                    val currentChat = chatHistory.chat_history.lastOrNull()
                    deps.updateUiState(
                        deps.uiState.value.copy(
                            chatHistory = chatHistory.chat_history,
                            groups = chatHistory.groups,
                            currentChat = currentChat
                        )
                    )
                    Toast.makeText(deps.context, "היסטוריית הצ'אט יובאה בהצלחה", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(deps.context, "שגיאה בייבוא: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
