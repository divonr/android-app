package com.example.ApI.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.ApI.R
import com.example.ApI.data.DeviceAttachmentsProvider
import com.example.ApI.data.attachments.AttachmentHash
import com.example.ApI.data.attachments.AttachmentSource
import com.example.ApI.data.attachments.AttachmentTransferState
import com.example.ApI.data.model.Attachment
import com.example.ApI.ui.theme.AccentRed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How much of a file's picture shows through while it is uploading / after its upload failed. */
const val TRANSFER_CONTENT_ALPHA = 0.35f

/**
 * Drawn over the thumbnail / icon of a file that is on its way to the account's store: a dim
 * layer with a spinning circle while it uploads; a red error mark after a failure, tapped to
 * try again. Nothing for a file without a transfer state.
 */
@Composable
fun BoxScope.TransferOverlay(
    transfer: AttachmentTransferState?,
    onRetry: () -> Unit,
    shape: Shape,
    indicatorSize: Dp = 24.dp
) {
    if (transfer == null) return
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.35f), shape)
            .then(if (transfer is AttachmentTransferState.Failed) Modifier.clickable(onClick = onRetry) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when (transfer) {
            is AttachmentTransferState.Uploading -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(indicatorSize)
            )
            is AttachmentTransferState.Failed -> Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.attachment_upload_failed_retry),
                tint = AccentRed,
                modifier = Modifier.size(indicatorSize)
            )
        }
    }
}

/**
 * The file of an attachment on this device, for the UI: found at once when the device has it
 * (its own path or the cache of store files), otherwise fetched from the account's store on
 * demand ([fetch] / [open]) — never eagerly.
 */
@Stable
class AttachmentFileHandle internal constructor(
    private val context: Context,
    private val attachment: Attachment,
    private val scope: CoroutineScope,
    initial: File?,
    /** The device does not have the file but the store may (the attachment has a hash). */
    val remote: Boolean
) {
    var file: File? by mutableStateOf(initial)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    /** Download the file (once; it stays in the device's cache). */
    fun fetch(orElse: () -> Unit = {}, then: (File) -> Unit = {}) {
        file?.let { return then(it) }
        if (!remote || loading) return
        loading = true
        failed = false
        // The download itself runs in the process scope: it finishes even if this view is gone
        val download = DeviceAttachmentsProvider.fetch(context, attachment)
        scope.launch {
            val result = try {
                download.await()
            } finally {
                loading = false
            }
            if (result != null) {
                file = result
                then(result)
            } else {
                failed = true
                orElse()
            }
        }
    }

    /** Open the file with another app, downloading it first when needed. */
    fun open() {
        fetch(
            orElse = { Toast.makeText(context, R.string.attachment_unavailable, Toast.LENGTH_SHORT).show() },
            then = { found -> scope.launch { openAttachmentFile(context, attachment, found) } }
        )
    }
}

/**
 * @param autoDownload fetch a file this device lacks as soon as the view is shown (images in a
 *                     message); otherwise only when asked ([AttachmentFileHandle.open]).
 */
@Composable
fun rememberAttachmentFile(attachment: Attachment, autoDownload: Boolean): AttachmentFileHandle {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handle = remember(attachment.sha256, attachment.local_file_path) {
        val hasHash = attachment.sha256?.let(AttachmentHash::isValid) == true
        val local = if (hasHash) {
            DeviceAttachmentsProvider.get(context).resolver.resolveLocal(attachment)?.file
        } else {
            // No identity: only ever this device's own path, used as it always was
            attachment.local_file_path?.let { File(it) }
        }
        AttachmentFileHandle(context, attachment, scope, local, remote = local == null && hasHash)
    }
    if (autoDownload && handle.remote) {
        LaunchedEffect(handle) { handle.fetch() }
    }
    return handle
}

/**
 * In place of an image this device does not have yet: a spinning circle while it is fetched from
 * the account's store, a "tap to retry" mark when that failed.
 */
@Composable
fun RemoteImagePlaceholder(fileName: String, loading: Boolean, tint: Color, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Color.Black.copy(alpha = 0.12f))
            .then(if (loading) Modifier else Modifier.clickable(onClick = onRetry)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (loading) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        } else {
            Icon(Icons.Default.BrokenImage, contentDescription = fileName, tint = tint, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.attachment_download_failed_retry),
                color = tint,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * At the end of a file chip: a spinning circle while the file is fetched from the store, a
 * download mark for a file that is not on this device yet, an error mark after a failed fetch.
 */
@Composable
fun AttachmentFetchIndicator(handle: AttachmentFileHandle, tint: Color) {
    when {
        handle.loading -> CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        handle.failed -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = stringResource(R.string.attachment_unavailable),
            tint = AccentRed,
            modifier = Modifier.size(16.dp)
        )
        handle.file == null && handle.remote -> Icon(
            Icons.Default.CloudDownload,
            contentDescription = stringResource(R.string.attachment_downloading),
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Open [file] (the bytes of [attachment]) in another app. A file from the store's cache is named
 * by its hash; apps want the real name, so it is exposed through a copy named like the
 * attachment in the app's cache directory (the system may clear it; it is made again on demand).
 */
suspend fun openAttachmentFile(context: Context, attachment: Attachment, file: File) {
    try {
        val shown = withContext(Dispatchers.IO) { namedCopy(context, attachment, file) }
        val uri = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".fileprovider", shown)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, attachment.mime_type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // No app can show this type, or the file provider refused the path
    }
}

private fun namedCopy(context: Context, attachment: Attachment, file: File): File {
    val sha = attachment.sha256?.takeIf(AttachmentHash::isValid) ?: return file
    val resolver = DeviceAttachmentsProvider.get(context).resolver
    if (resolver.resolveLocal(attachment)?.source == AttachmentSource.LOCAL || file.name != sha) return file
    val name = attachment.file_name.replace(Regex("[/\\\\\\u0000]"), "_").ifBlank { sha }
    val copy = File(File(File(context.cacheDir, "opened_attachments"), sha.take(16)), name)
    if (!copy.isFile || copy.length() != file.length()) {
        copy.parentFile?.mkdirs()
        file.copyTo(copy, overwrite = true)
    }
    return copy
}
