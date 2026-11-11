package com.example.ApI.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.ApI.R
import com.example.ApI.ui.theme.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FileSelectionDialog(
    onDismiss: () -> Unit
) {
    val pickers = LocalAttachmentPickers.current

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
                            onDismiss()
                            pickers.takePhoto()
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
                            onDismiss()
                            pickers.pickFiles()
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
}

@Composable
fun FileSelectionDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    val pickers = LocalAttachmentPickers.current

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Surface, RoundedCornerShape(12.dp))
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
                onDismiss()
                pickers.takePhoto()
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
                onDismiss()
                pickers.pickFiles()
            }
        )
    }
}

/**
 * The camera and the file picker of the message being written. Offered by the app's root
 * ([rememberAttachmentPickers]) and not by the menu that shows them: the app's activity, and
 * often its whole process, is destroyed while the camera is in front, and the result must still
 * find its launcher and the file the photo was written to when the app comes back.
 */
class AttachmentPickers(val takePhoto: () -> Unit, val pickFiles: () -> Unit)

val LocalAttachmentPickers = staticCompositionLocalOf { AttachmentPickers({}, {}) }

/**
 * The pickers, for a composable that is part of every screen. What a launch needs when its
 * result arrives (the photo's file, the chat it was started from) is saved with the activity's
 * state, so it outlives the activity and the process. [onPicked] gets the files (uri, name, mime
 * type) and the id of the chat that was open when the picker was launched.
 */
@Composable
fun rememberAttachmentPickers(
    currentChatId: () -> String?,
    onPicked: (files: List<Triple<Uri, String, String>>, originChatId: String?) -> Unit
): AttachmentPickers {
    val context = LocalContext.current
    val chatId by rememberUpdatedState(currentChatId)

    var pendingPhotoUri by rememberSaveable { mutableStateOf<String?>(null) }
    var originChatId by rememberSaveable { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val photo = pendingPhotoUri
        pendingPhotoUri = null
        if (success && photo != null) {
            onPicked(
                listOf(Triple(Uri.parse(photo), "camera_photo_${System.currentTimeMillis()}.jpg", "image/jpeg")),
                originChatId
            )
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onPicked(
                uris.map { uri ->
                    Triple(uri, getFileName(context, uri), context.contentResolver.getType(uri) ?: "application/octet-stream")
                },
                originChatId
            )
        }
    }

    fun launchCamera() {
        val uri = createImageUri(context)
        pendingPhotoUri = uri.toString()
        cameraLauncher.launch(uri)
    }

    // The manifest declares the camera permission, so the camera app needs it granted
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    return remember(context, cameraLauncher, filePickerLauncher, cameraPermissionLauncher) {
        AttachmentPickers(
            takePhoto = {
                originChatId = chatId()
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) launchCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            pickFiles = {
                originChatId = chatId()
                filePickerLauncher.launch("*/*")
            }
        )
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
