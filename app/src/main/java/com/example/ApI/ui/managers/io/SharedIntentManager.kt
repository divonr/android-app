package com.example.ApI.ui.managers.io

import android.content.Intent
import android.net.Uri
import com.example.ApI.data.model.PendingChatImport
import com.example.ApI.data.model.PendingShare
import com.example.ApI.data.model.Screen
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Content shared into the app from other apps (the system's "Share"): text and files.
 * A file that is a chat exported from the app goes to the import dialog; everything else waits
 * as [PendingShare] for the user to choose the chat it goes to.
 * Extracted from ChatViewModel to reduce complexity.
 */
class SharedIntentManager(
    private val deps: ManagerDependencies,
    private val currentScreen: MutableStateFlow<Screen>
) {

    /**
     * Take in an intent the activity was started with or received while running. Anything but
     * ACTION_SEND / ACTION_SEND_MULTIPLE is ignored. Nothing is copied or attached yet: that
     * happens once a chat is chosen, so a cancelled share leaves nothing behind.
     */
    fun handleIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> return
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        val intentType = intent.type

        deps.scope.launch {
            val files = mutableListOf<Triple<Uri, String, String>>()
            var chatImport: PendingChatImport? = null
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val fileName = try { getFileName(uri) } catch (e: Exception) { null } ?: "shared_file"
                    val mimeType = resolveMimeType(uri, intentType, fileName)
                    val exportedChat = readExportedChat(uri, fileName, mimeType)
                    if (exportedChat != null) {
                        chatImport = PendingChatImport(uri, fileName, mimeType, exportedChat)
                    } else {
                        files.add(Triple(uri, fileName, mimeType))
                    }
                }
            }

            chatImport?.let { pending ->
                // The import dialog is shown by the chat list and the chat screen
                if (currentScreen.value !is Screen.Chat) currentScreen.value = Screen.ChatHistory
                deps.updateUiState(deps.uiState.value.copy(pendingChatImport = pending))
            }
            if (text != null || files.isNotEmpty()) {
                // A share that arrives while another still waits for its chat takes its place
                deps.updateUiState(deps.uiState.value.copy(pendingShare = PendingShare(text, files)))
            }
        }
    }

    /** Drop the share that waits for a chat: nothing was attached, nothing is left behind. */
    fun cancelPendingShare() {
        deps.updateUiState(deps.uiState.value.copy(pendingShare = null))
    }

    /** The content of a shared JSON file when it is a chat exported from the app, else null. */
    private fun readExportedChat(uri: Uri, fileName: String, mimeType: String): String? {
        val isJsonFile = fileName.endsWith(".json", ignoreCase = true) ||
            mimeType == "application/json" ||
            mimeType == "text/json"
        if (!isJsonFile) return null
        return try {
            deps.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?.takeIf { deps.repository.validateChatJson(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get the file name from a URI using content resolver.
     */
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        deps.context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    /**
     * Resolves a specific MIME type from potentially wildcarded or incomplete intent type.
     * Falls back to contentResolver and then file extension inference.
     */
    private fun resolveMimeType(uri: Uri, intentType: String?, fileName: String?): String {
        // Helper to check if MIME type is valid (has "/" and no wildcard)
        fun isValidMimeType(type: String?): Boolean {
            return !type.isNullOrBlank() && type.contains("/") && !type.contains("*")
        }

        // First try contentResolver which usually gives specific type
        val resolvedType = deps.context.contentResolver.getType(uri)

        // If contentResolver gives a valid specific type, use it
        if (isValidMimeType(resolvedType)) {
            return resolvedType!!
        }

        // If intentType is specific (not wildcard), use it
        if (isValidMimeType(intentType)) {
            return intentType!!
        }

        // Infer from file extension
        val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        val inferredType = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> null
        }

        if (inferredType != null) {
            return inferredType
        }

        // Last resort: use intentType if available (even if wildcard), otherwise default
        return intentType ?: "application/octet-stream"
    }
}
