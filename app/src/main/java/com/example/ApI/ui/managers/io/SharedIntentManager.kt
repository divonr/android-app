package com.example.ApI.ui.managers.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.model.PendingChatImport
import com.example.ApI.data.model.Screen
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Manages handling of shared intents from other apps.
 * Processes shared files and text, validates JSON files for chat import.
 * Extracted from ChatViewModel to reduce complexity.
 */
class SharedIntentManager(
    private val repository: DataRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val sharedIntent: Intent?,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val currentScreen: MutableStateFlow<Screen>,
    private val addFileFromUri: (Uri, String, String) -> Unit
) {

    /**
     * Handle shared files from other apps.
     * Processes ACTION_SEND and ACTION_SEND_MULTIPLE intents.
     */
    fun handleSharedFiles() {
        sharedIntent?.let { intent ->
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    // Handle shared text
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                        uiState.value = uiState.value.copy(currentMessage = sharedText)
                    }
                    // Handle single file sharing
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                        val fileName = getFileName(context, uri) ?: "shared_file"
                        val mimeType = resolveMimeType(context, uri, intent.type, fileName)
                        checkAndHandleJsonFile(uri, fileName, mimeType)
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    // Multiple files sharing
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { uri ->
                        val fileName = getFileName(context, uri) ?: "shared_file"
                        val mimeType = resolveMimeType(context, uri, intent.type, fileName)
                        checkAndHandleJsonFile(uri, fileName, mimeType)
                    }
                }
            }
        }
    }

    /**
     * Checks IMMEDIATELY if a shared file is a valid chat JSON.
     * If it is, shows the import dialog right away.
     * Otherwise, adds it as a regular file attachment.
     */
    private fun checkAndHandleJsonFile(uri: Uri, fileName: String, mimeType: String) {
        // Check if it's a JSON file by extension or MIME type
        val isJsonFile = fileName.endsWith(".json", ignoreCase = true) ||
                        mimeType == "application/json" ||
                        mimeType == "text/json"

        if (isJsonFile) {
            scope.launch {
                try {
                    // Read the JSON content
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val jsonContent = inputStream?.bufferedReader()?.use { it.readText() }

                    if (jsonContent != null && repository.validateChatJson(jsonContent)) {
                        // Valid chat JSON - show the import choice dialog IMMEDIATELY
                        // Navigate to ChatHistory screen if not already there
                        currentScreen.value = Screen.ChatHistory

                        uiState.value = uiState.value.copy(
                            pendingChatImport = PendingChatImport(
                                uri = uri,
                                fileName = fileName,
                                mimeType = mimeType,
                                jsonContent = jsonContent
                            )
                        )
                    } else {
                        // Invalid chat JSON - treat as regular file attachment
                        addFileFromUri(uri, fileName, mimeType)
                    }
                } catch (e: Exception) {
                    // Error reading file - treat as regular file attachment
                    addFileFromUri(uri, fileName, mimeType)
                }
            }
        } else {
            // Not a JSON file - treat as regular file attachment
            addFileFromUri(uri, fileName, mimeType)
        }
    }

    /**
     * Get the file name from a URI using content resolver.
     */
    private fun getFileName(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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
    private fun resolveMimeType(context: Context, uri: Uri, intentType: String?, fileName: String?): String {
        // Helper to check if MIME type is valid (has "/" and no wildcard)
        fun isValidMimeType(type: String?): Boolean {
            return !type.isNullOrBlank() && type.contains("/") && !type.contains("*")
        }

        // First try contentResolver which usually gives specific type
        val resolvedType = context.contentResolver.getType(uri)

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
