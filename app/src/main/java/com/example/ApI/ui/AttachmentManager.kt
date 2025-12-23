package com.example.ApI.ui

import android.content.Context
import android.net.Uri
import com.example.ApI.data.model.ChatUiState
import com.example.ApI.data.model.SelectedFile
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Manages file selection, upload preparation, and file attachments.
 * Handles copying files to internal storage for lazy upload to providers.
 * Extracted from ChatViewModel to reduce complexity.
 */
class AttachmentManager(
    private val repository: DataRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val uiState: StateFlow<ChatUiState>,
    private val updateUiState: (ChatUiState) -> Unit
) {

    /**
     * Add a file from URI to the selected files list.
     * Copies the file to internal storage for lazy upload.
     */
    fun addFileFromUri(uri: Uri, fileName: String, mimeType: String) {
        scope.launch {
            try {
                // Copy file to internal storage for lazy upload
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val fileData = stream.readBytes()
                    val localPath = repository.saveFileLocally(fileName, fileData)

                    if (localPath != null) {
                        val selectedFile = SelectedFile(
                            uri = uri,
                            name = fileName,
                            mimeType = mimeType,
                            localPath = localPath
                        )

                        updateUiState(
                            uiState.value.copy(
                                selectedFiles = uiState.value.selectedFiles + selectedFile
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Handle file error
                println("Error adding file: ${e.message}")
            }
        }
    }

    /**
     * Add multiple files from URIs at once.
     * Useful for batch file selection.
     */
    fun addMultipleFilesFromUris(filesList: List<Triple<Uri, String, String>>) {
        scope.launch {
            val newSelectedFiles = mutableListOf<SelectedFile>()

            for ((uri, fileName, mimeType) in filesList) {
                try {
                    val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                    inputStream?.use { stream ->
                        val fileData = stream.readBytes()
                        val localPath = repository.saveFileLocally(fileName, fileData)

                        if (localPath != null) {
                            val selectedFile = SelectedFile(
                                uri = uri,
                                name = fileName,
                                mimeType = mimeType,
                                localPath = localPath
                            )
                            newSelectedFiles.add(selectedFile)
                        }
                    }
                } catch (e: Exception) {
                    println("Error adding file $fileName: ${e.message}")
                }
            }

            if (newSelectedFiles.isNotEmpty()) {
                updateUiState(
                    uiState.value.copy(
                        selectedFiles = uiState.value.selectedFiles + newSelectedFiles
                    )
                )
            }
        }
    }

    /**
     * Remove a file from the selected files list.
     * Also deletes the file from local storage.
     */
    fun removeSelectedFile(file: SelectedFile) {
        updateUiState(
            uiState.value.copy(
                selectedFiles = uiState.value.selectedFiles.filter { it != file }
            )
        )

        // Delete the local file if it exists
        file.localPath?.let { path ->
            repository.deleteFile(path)
        }
    }

    /**
     * Show the file selection dialog.
     */
    fun showFileSelection() {
        updateUiState(uiState.value.copy(showFileSelection = true))
    }

    /**
     * Hide the file selection dialog.
     */
    fun hideFileSelection() {
        updateUiState(uiState.value.copy(showFileSelection = false))
    }
}
