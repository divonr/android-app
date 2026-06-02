package com.example.ApI.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ApI.R
import com.example.ApI.stringResource
import com.example.ApI.ui.theme.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop file selection dialog using java.awt.FileDialog.
 */
@Composable
fun FileSelectionDialog(
    onFileSelected: (Uri, String, String) -> Unit,
    onMultipleFilesSelected: (List<Triple<Uri, String, String>>) -> Unit = { },
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Select File", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }

                Button(onClick = {
                    val fileDialog = FileDialog(Frame(), "Select File", FileDialog.LOAD)
                    fileDialog.isMultipleMode = false
                    fileDialog.isVisible = true
                    val selectedFile = fileDialog.files?.firstOrNull()
                    if (selectedFile != null) {
                        val mimeType = getMimeTypeFromExtension(selectedFile.extension.lowercase())
                        onFileSelected(Uri.fromFile(selectedFile), selectedFile.name, mimeType)
                        onDismiss()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.upload_file))
                }

                OutlinedButton(onClick = {
                    val fileDialog = FileDialog(Frame(), "Select Files", FileDialog.LOAD)
                    fileDialog.isMultipleMode = true
                    fileDialog.isVisible = true
                    val files = fileDialog.files?.toList() ?: emptyList()
                    if (files.isNotEmpty()) {
                        val fileTriples = files.map { f -> Triple(Uri.fromFile(f), f.name, getMimeTypeFromExtension(f.extension.lowercase())) }
                        onMultipleFilesSelected(fileTriples)
                        onDismiss()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Select Multiple Files")
                }
            }
        }
    }
}

/**
 * Desktop dropdown for file selection, used in ChatInputArea.
 */
@Composable
fun FileSelectionDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFileSelected: (Uri, String, String) -> Unit,
    onMultipleFilesSelected: (List<Triple<Uri, String, String>>) -> Unit = { }
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Surface, RoundedCornerShape(12.dp)).wrapContentWidth()
    ) {
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(imageVector = Icons.Default.AttachFile, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.upload_file), color = OnSurface)
                }
            },
            onClick = {
                onDismiss()
                val fileDialog = FileDialog(Frame(), "Select File(s)", FileDialog.LOAD)
                fileDialog.isMultipleMode = true
                fileDialog.isVisible = true
                val files = fileDialog.files?.toList() ?: emptyList()
                if (files.size == 1) {
                    val f = files[0]
                    onFileSelected(Uri.fromFile(f), f.name, getMimeTypeFromExtension(f.extension.lowercase()))
                } else if (files.size > 1) {
                    onMultipleFilesSelected(files.map { f -> Triple(Uri.fromFile(f), f.name, getMimeTypeFromExtension(f.extension.lowercase())) })
                }
            }
        )
    }
}

fun getMimeTypeFromExtension(ext: String) = when (ext) {
    "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "pdf" -> "application/pdf"; "txt" -> "text/plain"; "md" -> "text/markdown"
    "json" -> "application/json"; "csv" -> "text/csv"; "xml" -> "application/xml"
    "zip" -> "application/zip"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"
    else -> "application/octet-stream"
}
