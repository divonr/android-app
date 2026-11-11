package com.example.ApI.server.runs

import com.example.ApI.data.attachments.AttachmentSource
import com.example.ApI.data.attachments.ResolvedAttachment
import com.example.ApI.data.attachments.StoreError
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.server.attachments.UserAttachments
import java.io.File

// ── What a provider reads ─────────────────────────────────────────────────────

/**
 * Whether sending [attachment] to [provider] reads the attachment's file.  Mirrors what
 * `FileUploadManager.ensureFilesUploadedForProvider` and the provider classes do:
 *  - openai: images and PDFs are sent by `file_OPENAI_id` (uploaded first when missing); every
 *    other type is read and inlined as text;
 *  - poe: sent by `file_POE_url` (uploaded first when missing);
 *  - google (AI Studio): sent by `file_GOOGLE_uri` (uploaded first when missing);
 *  - every other provider (anthropic, vertex, cohere, openrouter, custom…) inlines the bytes.
 */
internal fun attachmentNeedsFile(provider: String, attachment: Attachment): Boolean = when (provider) {
    "openai" -> {
        val sentById = attachment.mime_type.startsWith("image/") || attachment.mime_type == "application/pdf"
        !(sentById && !attachment.file_OPENAI_id.isNullOrBlank())
    }
    "poe" -> attachment.file_POE_url.isNullOrBlank()
    "google" -> attachment.file_GOOGLE_uri.isNullOrBlank() || attachment.file_GOOGLE_uri == "{file_URI}"
    else -> true
}

/**
 * Whether [provider] wants [attachment]'s file uploaded to it before the request (the rule of
 * `FileUploadManager`: the provider's id is missing).
 */
internal fun needsProviderUpload(provider: String, attachment: Attachment): Boolean = when (provider) {
    "openai" -> attachment.file_OPENAI_id.isNullOrBlank()
    "poe" -> attachment.file_POE_url.isNullOrBlank()
    "google" -> attachment.file_GOOGLE_uri.isNullOrBlank() || attachment.file_GOOGLE_uri == "{file_URI}"
    else -> false
}

/** [this] with the provider ids of [uploaded] (only the ones that are set). */
internal fun Attachment.withProviderIdsOf(uploaded: Attachment): Attachment = copy(
    file_OPENAI_id = uploaded.file_OPENAI_id?.takeIf { it.isNotBlank() } ?: file_OPENAI_id,
    file_POE_url = uploaded.file_POE_url?.takeIf { it.isNotBlank() } ?: file_POE_url,
    file_GOOGLE_uri = uploaded.file_GOOGLE_uri?.takeIf { it.isNotBlank() } ?: file_GOOGLE_uri
)

// ── The files of one request ──────────────────────────────────────────────────

/**
 * Where the attachment files of ONE request are on this server, found by the route that starts
 * the run ([resolveRequestAttachments]).
 *
 * The provider code reads an attachment's file from its `local_file_path`.  The path stored in
 * the chat history belongs to the device that attached the file, so the run gives the provider
 * an **in-flight copy** of the request ([inFlight]) whose paths are this server's — and only
 * that copy: it is never saved.  What the run does save (provider ids of files it uploaded) is
 * written onto the stored attachment by hash, without any path.
 */
class AttachmentFiles(private val files: Map<Attachment, File>) {

    /** The file of [attachment] (as the request sent it) on this server, if the run has it. */
    fun fileOf(attachment: Attachment): File? = files[attachment]

    /**
     * [attachment] for the provider: with this server's path of its file, or with no path at
     * all (the stored path is another machine's and must not be opened here).
     */
    fun inFlight(attachment: Attachment): Attachment = attachment.copy(local_file_path = fileOf(attachment)?.absolutePath)

    val isEmpty: Boolean get() = files.isEmpty()

    companion object {
        val NONE = AttachmentFiles(emptyMap())
    }
}

/** An attachment the request needs whose file this server cannot have. */
class UnavailableAttachment(val attachment: Attachment, val reason: String)

/** An attachment found in this user's own files that has no hash yet (to be backfilled). */
class UnhashedAttachment(val messageId: String?, val attachment: Attachment, val file: File)

class ResolvedRequestAttachments(
    val files: AttachmentFiles,
    val unavailable: List<UnavailableAttachment>,
    val unhashed: List<UnhashedAttachment>
)

/**
 * Find the file of every attachment [provider] needs for this request (CLOUD_RUNS_PLAN.md,
 * "Server"): the attachments of the user messages and the project attachments that the provider
 * reads ([attachmentNeedsFile]).  Each is resolved through the user's [UserAttachments.resolver]:
 * a `local_file_path` inside the user's own attachment directories, else the user's cache, else a
 * download from the user's store by hash.  A path the client sent is never read otherwise.
 */
suspend fun UserAttachments.resolveRequestAttachments(
    provider: String,
    messages: List<Message>,
    projectAttachments: List<Attachment>
): ResolvedRequestAttachments {
    val files = LinkedHashMap<Attachment, File>()
    val unavailable = ArrayList<UnavailableAttachment>()
    val unhashed = ArrayList<UnhashedAttachment>()

    suspend fun resolveOne(messageId: String?, attachment: Attachment) {
        if (!attachmentNeedsFile(provider, attachment)) return
        if (attachment in files) return
        when (val resolved = resolver.resolve(attachment)) {
            is ResolvedAttachment.Available -> {
                files[attachment] = resolved.file
                if (resolved.source == AttachmentSource.LOCAL && attachment.sha256 == null) {
                    unhashed += UnhashedAttachment(messageId, attachment, resolved.file)
                }
            }
            is ResolvedAttachment.Unavailable -> {
                if (unavailable.none { it.attachment == attachment }) {
                    val reason = when {
                        attachment.sha256 == null -> "it has no hash and is not on the server"
                        resolved.error == StoreError.NOT_FOUND -> "the attachment store does not have it"
                        else -> "the attachment store could not be reached (${resolved.error})"
                    }
                    unavailable += UnavailableAttachment(attachment, reason)
                }
            }
        }
    }

    for (message in messages) {
        if (message.role != "user") continue
        for (attachment in message.attachments) resolveOne(message.id, attachment)
    }
    for (attachment in projectAttachments) resolveOne(null, attachment)
    return ResolvedRequestAttachments(AttachmentFiles(files), unavailable, unhashed)
}

// ── Uploading to the provider ─────────────────────────────────────────────────

/**
 * Uploads a file to a provider that takes files by reference (OpenAI, Poe, Google AI Studio)
 * and answers with an attachment carrying the provider's id, or null when the upload failed or
 * the provider takes no uploads.  A seam: tests replace it so that nothing reaches a provider.
 */
fun interface ProviderFileUploader {
    suspend fun upload(provider: Provider, file: File, fileName: String, mimeType: String, username: String): Attachment?
}

/** The real uploads (the same code a device uses). */
class RepositoryProviderFileUploader(private val repository: DataRepository) : ProviderFileUploader {
    override suspend fun upload(provider: Provider, file: File, fileName: String, mimeType: String, username: String): Attachment? =
        repository.uploadFile(provider, file.absolutePath, fileName, mimeType, username)
}
