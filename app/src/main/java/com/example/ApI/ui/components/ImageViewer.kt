package com.example.ApI.ui.components

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.ApI.R
import com.example.ApI.data.attachments.AttachmentHash
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.ChatGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * An image of a conversation, full screen inside the app: pinch to zoom, drag to pan, double tap
 * to zoom in / back out. A menu behind the three dots at the top shares it with other apps, saves
 * it to the Downloads folder, or forwards it to another chat ([onForward]: the image's file, the chosen
 * chat — null for a new one — and what to call if it could not be attached). The file is fetched from the account's store when this device
 * does not have it.
 */
@Composable
fun ImageViewerDialog(
    attachment: Attachment,
    chats: List<Chat>,
    groups: List<ChatGroup>,
    /** The chat the image was opened from (left out of the chats to forward to), if any. */
    currentChatId: String?,
    onDismiss: () -> Unit,
    onForward: (File, Chat?, onFailed: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = rememberAttachmentFile(attachment, autoDownload = true)
    val file = image.file
    var showMenu by remember { mutableStateOf(false) }
    var showChatPicker by remember { mutableStateOf(false) }
    var forwarding by remember { mutableStateOf(false) }

    fun toast(message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    fun save(from: File) {
        scope.launch {
            val saved = saveImageToDownloads(context, attachment, from)
            toast(if (saved) R.string.image_viewer_saved else R.string.image_viewer_save_failed)
        }
    }

    // Before Android 10 a file is written to the public Downloads folder directly, which needs
    // the storage permission; from Android 10 on it goes through MediaStore and needs none
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val current = image.file
        if (granted && current != null) save(current) else toast(R.string.image_viewer_storage_permission)
    }

    Dialog(
        // Back closes what is on top first: the chat picker, then the menu, then the viewer
        onDismissRequest = {
            when {
                showChatPicker -> showChatPicker = false
                showMenu -> showMenu = false
                else -> onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // A dialog is its own window: the app's (forced) right-to-left layout is set again here
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                when {
                    file != null -> ZoomableImage(file = file, description = attachment.file_name)
                    image.loading || (image.remote && !image.failed) ->
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                    else -> Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clickable(enabled = image.remote) { image.fetch() }
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = stringResource(
                                if (image.remote) R.string.attachment_download_failed_retry else R.string.attachment_unavailable
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // While the menu is open, a tap anywhere else only closes it
                if (showMenu) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showMenu = false }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(12.dp)
                ) {
                    ViewerButton(
                        icon = Icons.Default.Close,
                        label = stringResource(R.string.image_viewer_close),
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopStart)
                    )

                    // The menu, behind the three dots: only once there is a file to act on
                    if (file != null) {
                        Column(
                            modifier = Modifier.align(Alignment.TopEnd),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ViewerButton(
                                icon = Icons.Default.MoreVert,
                                label = stringResource(R.string.image_viewer_menu),
                                onClick = { showMenu = !showMenu }
                            )
                            if (showMenu) {
                                ViewerButton(
                                    icon = Icons.Default.Share,
                                    label = stringResource(R.string.image_viewer_share),
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            if (!shareImageFile(context, attachment, file)) toast(R.string.image_viewer_share_failed)
                                        }
                                    }
                                )
                                ViewerButton(
                                    icon = Icons.Default.Download,
                                    label = stringResource(R.string.image_viewer_save),
                                    onClick = {
                                        showMenu = false
                                        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                            PackageManager.PERMISSION_GRANTED
                                        if (needsPermission) {
                                            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        } else {
                                            save(file)
                                        }
                                    }
                                )
                                ViewerButton(
                                    icon = Icons.AutoMirrored.Filled.Forward,
                                    label = stringResource(R.string.image_viewer_forward),
                                    onClick = {
                                        showMenu = false
                                        showChatPicker = true
                                    }
                                )
                            }
                        }
                    }
                }

                if (showChatPicker && file != null) {
                    ChatPicker(
                        title = stringResource(R.string.image_viewer_forward_title),
                        chats = chats.filter { it.chat_id != currentChatId },
                        groups = groups,
                        busy = forwarding,
                        onDismiss = { showChatPicker = false },
                        onPick = { chat ->
                            if (!forwarding) {
                                forwarding = true
                                onForward(file, chat) { forwarding = false }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ZoomableImage(file: File, description: String) {
    var scale by remember(file) { mutableStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    /** Keep the zoomed image over the screen: it can be dragged only as far as it overflows. */
    fun bounded(target: Offset, atScale: Float): Offset {
        val maxX = size.width * (atScale - 1f) / 2f
        val maxY = size.height * (atScale - 1f) / 2f
        return Offset(target.x.coerceIn(-maxX, maxX), target.y.coerceIn(-maxY, maxY))
    }

    AsyncImage(
        model = file,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(file) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                        offset = Offset.Zero
                    }
                )
            }
            .pointerInput(file) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                    scale = newScale
                    offset = bounded(offset + pan, newScale)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    )
}

// ─── Share / save ─────────────────────────────────────────────────────────────

/** The attachment's own name, safe as a file name and with an extension other apps understand. */
private fun exportName(attachment: Attachment, file: File): String {
    val name = attachment.file_name.replace(Regex("[/\\\\\\u0000]"), "_").ifBlank { file.name }
    if (name.substringAfterLast('.', "").isNotEmpty()) return name
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(attachment.mime_type)
    return if (extension != null) "$name.$extension" else name
}

/**
 * Hand the image to the system's share sheet. Files of the app are named by a random id or by
 * their hash; other apps want the real name, so the image is shared through a copy named like
 * the attachment in the app's cache directory (the system may clear it; it is made again).
 */
suspend fun shareImageFile(context: Context, attachment: Attachment, file: File): Boolean {
    return try {
        val shared = withContext(Dispatchers.IO) {
            val name = exportName(attachment, file)
            val key = attachment.sha256?.takeIf(AttachmentHash::isValid)?.take(16)
                ?: Integer.toHexString(file.absolutePath.hashCode())
            val copy = File(File(File(context.cacheDir, "opened_attachments"), key), name)
            if (!copy.isFile || copy.length() != file.length()) {
                copy.parentFile?.mkdirs()
                file.copyTo(copy, overwrite = true)
            }
            copy
        }
        val uri = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".fileprovider", shared)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = attachment.mime_type.takeIf { it.startsWith("image/") } ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            // The preview of the share sheet reads the image through the clip data
            clipData = android.content.ClipData.newRawUri(shared.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Copy the image into the device's public Downloads folder: through MediaStore from Android 10
 * on (no permission), directly into the folder before it (the caller holds the storage
 * permission).
 */
suspend fun saveImageToDownloads(context: Context, attachment: Attachment, file: File): Boolean =
    withContext(Dispatchers.IO) {
        val name = exportName(attachment, file)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, attachment.mime_type)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                try {
                    val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("no output stream")
                    out.use { target -> file.inputStream().use { it.copyTo(target) } }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloads.mkdirs()
                // Never over a file that is already there: "name (1).png", "name (2).png", …
                val base = name.substringBeforeLast('.', name)
                val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                var target = File(downloads, name)
                var counter = 1
                while (target.exists()) target = File(downloads, "$base (${counter++})$extension")
                file.copyTo(target)
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(attachment.mime_type), null)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
