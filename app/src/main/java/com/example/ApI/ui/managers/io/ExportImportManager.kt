package com.example.ApI.ui.managers.io

import android.app.PendingIntent
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
import java.io.File
import java.io.InputStream

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

            deps.updateUiState(
                deps.uiState.value.copy(
                    showChatExportDialog = true,
                    chatExportJson = chatJson,
                    isChatExportEditable = false
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
                isChatExportEditable = false
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
