package com.example.ApI.ui.managers.io

import com.example.ApI.data.model.SelectedFile
import com.example.ApI.ui.managers.ManagerDependencies
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages file selection, upload preparation, and file attachments.
 * Desktop adaptation: uses java.io.File instead of Android Uri.
 */
class AttachmentManager(
    private val deps: ManagerDependencies
) {

    /** Add a file from a local File path. */
    fun addFileFromPath(file: File, mimeType: String) {
        deps.scope.launch {
            try {
                val fileData = file.readBytes()
                val localPath = deps.repository.saveFileLocally(file.name, fileData)

                if (localPath != null) {
                    val selectedFile = SelectedFile(
                        uri = null,
                        name = file.name,
                        mimeType = mimeType,
                        localPath = localPath
                    )
                    deps.updateUiState(deps.uiState.value.copy(
                        selectedFiles = deps.uiState.value.selectedFiles + selectedFile
                    ))
                }
            } catch (e: Exception) {
                println("Error adding file: ${e.message}")
            }
        }
    }

    fun addMultipleFiles(filesList: List<Pair<File, String>>) {
        deps.scope.launch {
            val newSelectedFiles = mutableListOf<SelectedFile>()
            for ((file, mimeType) in filesList) {
                try {
                    val fileData = file.readBytes()
                    val localPath = deps.repository.saveFileLocally(file.name, fileData)
                    if (localPath != null) {
                        newSelectedFiles.add(SelectedFile(uri = null, name = file.name, mimeType = mimeType, localPath = localPath))
                    }
                } catch (e: Exception) {
                    println("Error adding file ${file.name}: ${e.message}")
                }
            }
            if (newSelectedFiles.isNotEmpty()) {
                deps.updateUiState(deps.uiState.value.copy(selectedFiles = deps.uiState.value.selectedFiles + newSelectedFiles))
            }
        }
    }

    fun removeSelectedFile(file: SelectedFile) {
        deps.updateUiState(deps.uiState.value.copy(selectedFiles = deps.uiState.value.selectedFiles.filter { it != file }))
        file.localPath?.let { path -> deps.repository.deleteFile(path) }
    }

    fun showFileSelection() {
        deps.updateUiState(deps.uiState.value.copy(showFileSelection = true))
    }

    fun hideFileSelection() {
        deps.updateUiState(deps.uiState.value.copy(showFileSelection = false))
    }
}
