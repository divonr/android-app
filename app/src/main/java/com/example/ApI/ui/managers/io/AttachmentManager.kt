package com.example.ApI.ui.managers.io

import android.net.Uri
import com.example.ApI.data.model.SelectedFile
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * Manages file selection, upload preparation, and file attachments.
 * Handles copying files to internal storage for lazy upload to providers.
 * Extracted from ChatViewModel to reduce complexity.
 */
class AttachmentManager(
    private val deps: ManagerDependencies
) {

    /**
     * Add a file from URI to the selected files list.
     * Copies the file to internal storage for lazy upload.
     */
    fun addFileFromUri(uri: Uri, fileName: String, mimeType: String) {
        deps.scope.launch {
            try {
                // Copy file to internal storage for lazy upload
                val inputStream: InputStream? = deps.context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val fileData = stream.readBytes()
                    val localPath = deps.repository.saveFileLocally(fileName, fileData)

                    if (localPath != null) {
                        val selectedFile = SelectedFile(
                            uri = uri,
                            name = fileName,
                            mimeType = mimeType,
                            localPath = localPath
                        )

                        deps.updateUiState(
                            deps.uiState.value.copy(
                                selectedFiles = deps.uiState.value.selectedFiles + selectedFile
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
        deps.scope.launch {
            val newSelectedFiles = mutableListOf<SelectedFile>()

            for ((uri, fileName, mimeType) in filesList) {
                try {
                    val inputStream: InputStream? = deps.context.contentResolver.openInputStream(uri)
                    inputStream?.use { stream ->
                        val fileData = stream.readBytes()
                        val localPath = deps.repository.saveFileLocally(fileName, fileData)

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
                deps.updateUiState(
                    deps.uiState.value.copy(
                        selectedFiles = deps.uiState.value.selectedFiles + newSelectedFiles
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
        deps.updateUiState(
            deps.uiState.value.copy(
                selectedFiles = deps.uiState.value.selectedFiles.filter { it != file }
            )
        )

        // Delete the local file if it exists
        file.localPath?.let { path ->
            deps.repository.deleteFile(path)
        }
    }

    /**
     * Show the file selection dialog.
     */
    fun showFileSelection() {
        deps.updateUiState(deps.uiState.value.copy(showFileSelection = true))
    }

    /**
     * Hide the file selection dialog.
     */
    fun hideFileSelection() {
        deps.updateUiState(deps.uiState.value.copy(showFileSelection = false))
    }
}
