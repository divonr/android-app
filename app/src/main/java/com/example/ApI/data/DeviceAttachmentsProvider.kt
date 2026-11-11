package com.example.ApI.data

import android.content.Context
import com.example.ApI.data.attachments.AttachmentResolver
import com.example.ApI.data.attachments.AttachmentStoreClient
import com.example.ApI.data.attachments.AttachmentStoreConfig
import com.example.ApI.data.attachments.DeviceAttachments
import com.example.ApI.data.attachments.KnownStoredFiles
import com.example.ApI.data.attachments.attachmentStoreConfig
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.repository.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File

/**
 * The process-wide [DeviceAttachments] of this device (CLOUD_RUNS_PLAN.md, "Attachment store"):
 * one for the UI and the streaming service, living as long as the process, so uploads and
 * downloads survive configuration changes, the screen that started them and the activity.
 * They do not survive the process: a draft upload that dies with it is swept by the store, and
 * a file of a saved message is stored by the next request that includes the message.
 */
object DeviceAttachmentsProvider {
    /** Owns every upload / download. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var instance: DeviceAttachments? = null

    fun get(context: Context): DeviceAttachments = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): DeviceAttachments {
        val storage = AndroidPlatformStorage(context)
        // Same data dir as the UI's repository, hence the same file locks and sync engine
        val repository = DataRepository(storage)
        val config = CachedConfig { repository.loadAppSettings().remoteSync.attachmentStoreConfig() }
        val store = AttachmentStoreClient(config = { config.get() })
        return DeviceAttachments(
            store = store,
            resolver = AttachmentResolver(storage.attachmentsCacheDir, store),
            scope = scope,
            recordIdentity = { username, owner, attachment, digest ->
                repository.recordAttachmentIdentity(username, owner, attachment, digest)
            },
            known = KnownStoredFiles(File(context.filesDir, "attachments_state")) {
                KnownStoredFiles.accountKey(config.get())
            }
        )
    }

    /**
     * The file of [attachment], downloaded into the cache when this device does not have it.
     * Runs in [scope]: a download goes on when the composable that asked for it is gone.
     */
    fun fetch(context: Context, attachment: Attachment): Deferred<File?> {
        val resolver = get(context).resolver
        return scope.async { resolver.resolve(attachment).fileOrNull }
    }

    /** The settings file is read at most once a second (the store asks for every request). */
    private class CachedConfig(private val load: () -> AttachmentStoreConfig?) {
        private var value: AttachmentStoreConfig? = null
        private var loadedAt = 0L

        @Synchronized
        fun get(): AttachmentStoreConfig? {
            val now = System.nanoTime()
            if (loadedAt == 0L || now - loadedAt > 1_000_000_000L) {
                value = try {
                    load()
                } catch (_: Exception) {
                    null
                }
                loadedAt = now
            }
            return value
        }
    }
}
