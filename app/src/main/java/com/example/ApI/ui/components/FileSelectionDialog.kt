package com.example.ApI.ui.components

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.example.ApI.R
import com.example.ApI.ui.theme.*
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FileSelectionDialog(
    onFileSelected: (Uri, String, String) -> Unit,
    onMultipleFilesSelected: (List<Triple<Uri, String, String>>) -> Unit = { },
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    // File to store camera photo
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    
    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            onFileSelected(photoUri!!, "camera_photo_${System.currentTimeMillis()}.jpg", "image/jpeg")
            onDismiss()
        }
    }
    
    // File picker launcher (supports both single and multiple files)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size == 1) {
                // Single file selected
                val uri = uris[0]
                val fileName = getFileName(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                onFileSelected(uri, fileName, mimeType)
            } else {
                // Multiple files selected
                val filesList = uris.map { uri ->
                    val fileName = getFileName(context, uri)
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    Triple(uri, fileName, mimeType)
                }
                onMultipleFilesSelected(filesList)
            }
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.select_file_option),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    // Take Photo Option
                    Button(
                        onClick = {
                            if (cameraPermissionState.status.isGranted) {
                                photoUri = createImageUri(context)
                                photoUri?.let { uri ->
                                    cameraLauncher.launch(uri)
                                }
                            } else {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Primary,
                            contentColor = OnPrimary
                        )
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.take_photo),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Upload Files Option (supports both single and multiple)
                    Button(
                        onClick = {
                            filePickerLauncher.launch("*/*")
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Secondary,
                            contentColor = OnSecondary
                        )
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.upload_file),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Cancel button
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
    
    // Handle camera permission result
    LaunchedEffect(cameraPermissionState.status) {
        if (cameraPermissionState.status.isGranted && photoUri != null) {
            // Permission was just granted, launch camera
            cameraLauncher.launch(photoUri!!)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun FileSelectionDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onFileSelected: (Uri, String, String) -> Unit,
    onMultipleFilesSelected: (List<Triple<Uri, String, String>>) -> Unit = { }
) {
    val context = LocalContext.current

    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // File to store camera photo
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            onFileSelected(photoUri!!, "camera_photo_${System.currentTimeMillis()}.jpg", "image/jpeg")
            onDismiss()
        }
    }

    // File picker launcher (supports both single and multiple files)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size == 1) {
                // Single file selected
                val uri = uris[0]
                val fileName = getFileName(context, uri)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                onFileSelected(uri, fileName, mimeType)
            } else {
                // Multiple files selected
                val filesList = uris.map { uri ->
                    val fileName = getFileName(context, uri)
                    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    Triple(uri, fileName, mimeType)
                }
                onMultipleFilesSelected(filesList)
            }
            onDismiss()
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Surface, RoundedCornerShape(16.dp))
            .widthIn(min = 160.dp)
            .wrapContentWidth()
    ) {
        // Take photo row (table-like)
        DropdownMenuItem(
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.take_photo),
                        color = OnSurface
                    )
                }
            },
            onClick = {
                if (cameraPermissionState.status.isGranted) {
                    photoUri = createImageUri(context)
                    photoUri?.let { uri ->
                        cameraLauncher.launch(uri)
                    }
                } else {
                    cameraPermissionState.launchPermissionRequest()
                }
            }
        )

        // Upload files row (supports both single and multiple)
        DropdownMenuItem(
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.upload_file),
                        color = OnSurface
                    )
                }
            },
            onClick = {
                filePickerLauncher.launch("*/*")
            }
        )
    }

    // Handle camera permission result
    LaunchedEffect(cameraPermissionState.status) {
        if (cameraPermissionState.status.isGranted && photoUri != null) {
            cameraLauncher.launch(photoUri!!)
        }
    }
}

private fun createImageUri(context: Context): Uri {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    // Use external files directory instead of cache for better persistence
    val imagesDir = File(context.getExternalFilesDir(null), "images")
    if (!imagesDir.exists()) {
        imagesDir.mkdirs()
    }
    val imageFile = File(imagesDir, "JPEG_${timeStamp}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        imageFile
    )
}

private fun getFileName(context: Context, uri: Uri): String {
    var fileName = "unknown_file"
    
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
            fileName = cursor.getString(nameIndex) ?: fileName
        }
    }
    
    return fileName
}
