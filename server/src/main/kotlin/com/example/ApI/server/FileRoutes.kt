package com.example.ApI.server

import com.example.ApI.data.attachments.AttachmentHash
import com.example.ApI.data.attachments.FileDigest
import com.example.ApI.data.attachments.ResolvedAttachment
import com.example.ApI.data.attachments.StoreError
import com.example.ApI.data.attachments.StoreResult
import com.example.ApI.data.model.Attachment
import com.example.ApI.server.attachments.FileTooLargeException
import com.example.ApI.server.attachments.UnusedFileDelete
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder

// ── DTOs ─────────────────────────────────────────────────────────────────────

/** Error body of the file routes: [code] is stable, [error] is for people. */
@Serializable
data class FileError(val error: String, val code: String, val maxBytes: Long? = null)

/** Body of the legacy `DELETE /api/files`: a pending upload by hash, or a pre-store upload by path. */
@Serializable
data class DeleteFileRequest(val sha256: String? = null, val filePath: String? = null)

/** Answer of the delete routes. */
@Serializable
data class DeleteFileResponse(val deleted: Boolean, val state: String? = null)

/** Answer of `POST /api/files/{sha256}/commit`. */
@Serializable
data class CommitFileResponse(val sha256: String, val size: Long, val state: String)

object FileErrorCode {
    const val INVALID_HASH = "invalid_hash"
    const val NO_FILE = "no_file"
    const val FILE_TOO_LARGE = "file_too_large"
    /** Only internal: every 404 of the server is answered `{"error":"Not found"}` (StatusPages). */
    const val NOT_FOUND = "not_found"
    const val STORE_UNAVAILABLE = "store_unavailable"
    const val RANGE_NOT_SATISFIABLE = "range_not_satisfiable"
    /** A message / project attachment was not saved: a file it references exists nowhere (409). */
    const val ATTACHMENT_MISSING = "attachment_missing"
}

/**
 * Answer 409 `attachment_missing` when [save] found files that are gone (deleted before the save
 * reached the server).  Returns whether it answered — the caller then does not save.
 */
internal suspend fun ApplicationCall.refuseMissingFiles(save: com.example.ApI.server.attachments.FileSave): Boolean {
    if (save.missing.isEmpty()) return false
    respond(
        HttpStatusCode.Conflict,
        FileError(error = "An attached file is no longer available. Attach it again.", code = FileErrorCode.ATTACHMENT_MISSING)
    )
    return true
}

// ── How a file is presented to a browser ──────────────────────────────────────

/**
 * Files are served from the app's own origin, and both the bytes and the declared mime type come
 * from users' devices.  So nothing that a browser could run as a document of this origin is
 * ever served as such:
 *  - [INLINE_IMAGE_TYPES] are shown in place (`Content-Disposition: inline`) with their type;
 *  - [PASSIVE_TYPES] keep their type (so a player / viewer can use the bytes) but are downloads;
 *  - everything else (HTML, SVG, XML, scripts, unknown…) is `application/octet-stream`, a download.
 * Every answer carries `X-Content-Type-Options: nosniff` and a sandboxing CSP.
 */
internal val INLINE_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp")

internal val PASSIVE_TYPES = setOf(
    "application/pdf", "text/plain",
    "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/ogg", "audio/webm", "audio/aac", "audio/flac",
    "video/mp4", "video/webm", "video/ogg", "video/quicktime"
)

internal class FilePresentation(val contentType: ContentType, val inline: Boolean)

/** [declaredMime] is the `mime` query parameter (the attachment's `mime_type`); [download] forces a download. */
internal fun filePresentation(declaredMime: String?, download: Boolean): FilePresentation {
    val mime = declaredMime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        mime in INLINE_IMAGE_TYPES -> FilePresentation(ContentType.parse(mime), inline = !download)
        mime == "text/plain" -> FilePresentation(ContentType.Text.Plain.withCharset(Charsets.UTF_8), inline = false)
        mime in PASSIVE_TYPES -> FilePresentation(ContentType.parse(mime), inline = false)
        else -> FilePresentation(ContentType.Application.OctetStream, inline = false)
    }
}

/** `Content-Disposition` with a file name that cannot break out of the header. */
internal fun contentDisposition(inline: Boolean, fileName: String?): String {
    val type = if (inline) "inline" else "attachment"
    val clean = fileName
        ?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.filter { it >= ' ' && it != '\u007f' }
        ?.take(200)?.trim()?.ifBlank { null }
        ?: return type
    val ascii = clean.map { c -> if (c in ' '..'~' && c != '"' && c != '\\' && c != '%' && c != ';') c else '_' }.joinToString("")
    val encoded = URLEncoder.encode(clean, Charsets.UTF_8).replace("+", "%20")
    return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}

/** A requested byte range of a file of [length] bytes. */
internal sealed class ByteRange {
    /** No (usable) Range header: the whole file. */
    object Whole : ByteRange()
    object Unsatisfiable : ByteRange()
    class Part(val first: Long, val last: Long) : ByteRange()
}

/** Single ranges only (`bytes=a-b`, `bytes=a-`, `bytes=-n`); anything else is answered with the whole file. */
internal fun parseRange(header: String?, length: Long): ByteRange {
    val spec = header?.trim()?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.trim() ?: return ByteRange.Whole
    if (',' in spec) return ByteRange.Whole
    val dash = spec.indexOf('-')
    if (dash < 0) return ByteRange.Whole
    val from = spec.substring(0, dash).trim()
    val to = spec.substring(dash + 1).trim()
    return if (from.isEmpty()) {
        val suffix = to.toLongOrNull() ?: return ByteRange.Whole
        if (suffix <= 0 || length == 0L) ByteRange.Unsatisfiable
        else ByteRange.Part(maxOf(0, length - suffix), length - 1)
    } else {
        val first = from.toLongOrNull() ?: return ByteRange.Whole
        val last = if (to.isEmpty()) length - 1 else (to.toLongOrNull() ?: return ByteRange.Whole)
        if (first >= length || last < first) ByteRange.Unsatisfiable
        else ByteRange.Part(first, minOf(last, length - 1))
    }
}

// ── Routes (inside the authenticated /api block: session cookie or sync bearer) ───────────────

/**
 * The web's access to the user's attachment store (CLOUD_RUNS_PLAN.md, "Web file routes"): the
 * browser cannot talk to the sync server, so llm-web uploads, serves, commits and deletes files
 * on the user's behalf, with the user's own sync token.  Every route works on the files of the
 * authenticated user only.
 */
fun Route.fileRoutes() {

    suspend fun ApplicationCall.fileError(status: HttpStatusCode, code: String, error: String, maxBytes: Long? = null) =
        respond(status, FileError(error = error, code = code, maxBytes = maxBytes))

    suspend fun ApplicationCall.tooLarge(maxBytes: Long) = fileError(
        HttpStatusCode.PayloadTooLarge, FileErrorCode.FILE_TOO_LARGE,
        "File exceeds the maximum allowed size (${maxBytes / (1024 * 1024)} MB)", maxBytes
    )

    suspend fun ApplicationCall.storeFailure(failure: StoreResult.Failed) = when (failure.error) {
        StoreError.TOO_LARGE -> tooLarge(userContext().attachments.maxFileBytes)
        StoreError.NOT_FOUND -> fileError(HttpStatusCode.NotFound, FileErrorCode.NOT_FOUND, "File not found")
        else -> fileError(
            HttpStatusCode.BadGateway, FileErrorCode.STORE_UNAVAILABLE,
            "The attachment store could not be reached (${failure.error})"
        )
    }

    /** The `{sha256}` path parameter, or null after answering 400. */
    suspend fun ApplicationCall.hashParameter(): String? {
        val sha = parameters["sha256"].orEmpty()
        if (AttachmentHash.isValid(sha)) return sha
        fileError(HttpStatusCode.BadRequest, FileErrorCode.INVALID_HASH, "sha256 must be 64 lowercase hex characters")
        return null
    }

    /**
     * Delete a file that was uploaded but never used; a committed file, and one a save in
     * progress or a saved message / project references, is left alone.
     */
    suspend fun ApplicationCall.deletePending(sha: String) {
        when (val outcome = userContext().attachments.deleteUnused(sha)) {
            is UnusedFileDelete.Deleted -> respond(HttpStatusCode.OK, DeleteFileResponse(deleted = true))
            is UnusedFileDelete.Kept -> respond(HttpStatusCode.OK, DeleteFileResponse(deleted = false, state = outcome.state))
            is UnusedFileDelete.Failed -> storeFailure(outcome.failure)
        }
    }

    // POST /api/files/upload — multipart/form-data (field `file`): into the user's store, pending
    post("/files/upload") {
        val attachments = call.userContext().attachments
        val maxBytes = attachments.maxFileBytes
        // The multipart envelope adds a little; anything far over the limit is refused unread
        val announced = call.request.contentLength()
        if (announced != null && announced > maxBytes + MULTIPART_SLACK_BYTES) {
            return@post call.tooLarge(maxBytes)
        }

        var fileName: String? = null
        var mimeType: String? = null
        var received: Pair<File, FileDigest>? = null
        var tooLarge = false

        call.receiveMultipart().forEachPart { part ->
            try {
                if (part is PartData.FileItem && received == null && !tooLarge) {
                    fileName = (part.originalFileName?.ifBlank { null } ?: part.name?.ifBlank { null } ?: "upload")
                        .substringAfterLast('/').substringAfterLast('\\').ifBlank { "upload" }
                    mimeType = part.contentType?.withoutParameters()?.toString() ?: "application/octet-stream"
                    try {
                        received = part.streamProvider().use { input -> attachments.receive(input) }
                    } catch (_: FileTooLargeException) {
                        tooLarge = true
                    }
                }
            } finally {
                part.dispose()
            }
        }

        if (tooLarge) return@post call.tooLarge(maxBytes)
        val (file, digest) = received?.takeIf { it.second.size > 0 } ?: run {
            // An empty file: nothing to attach (and nothing kept)
            received?.first?.delete()
            return@post call.fileError(HttpStatusCode.BadRequest, FileErrorCode.NO_FILE, "No file content received")
        }

        when (val stored = attachments.store.upload(file, digest)) {
            is StoreResult.Ok -> Unit
            is StoreResult.Failed -> if (stored.error != StoreError.NOT_CONFIGURED) {
                // Nothing references it and no other device could ever fetch it
                file.delete()
                return@post call.storeFailure(stored)
            }
            // NOT_CONFIGURED (sync is off for this user): the file stays in this server's cache only
        }
        // No local_file_path: a server path means nothing to the user's devices (the hash is the identity)
        call.respond(
            HttpStatusCode.Created,
            Attachment(file_name = fileName ?: "upload", mime_type = mimeType ?: "application/octet-stream", sha256 = digest.sha256, size = digest.size)
        )
    }

    // GET /api/files/{sha256}?name=&mime=&download=1 — the file, from the user's cache or store
    get("/files/{sha256}") {
        val sha = call.hashParameter() ?: return@get
        val attachments = call.userContext().attachments
        val file = when (val resolved = attachments.resolver.resolveHash(sha)) {
            is ResolvedAttachment.Available -> resolved.file
            is ResolvedAttachment.Unavailable ->
                return@get call.storeFailure(StoreResult.Failed(
                    // Without a store there is nowhere else the file could be
                    if (resolved.error == StoreError.NOT_CONFIGURED) StoreError.NOT_FOUND else resolved.error,
                    resolved.message
                ))
        }

        val query = call.request.queryParameters
        val download = query["download"]?.lowercase() in setOf("1", "true")
        val presentation = filePresentation(query["mime"], download)
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("Content-Security-Policy", "default-src 'none'; sandbox")
        call.response.headers.append(HttpHeaders.ContentDisposition, contentDisposition(presentation.inline, query["name"]))
        // The URL names the content: it never changes
        call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
        call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")

        val length = file.length()
        val (status, first, count) = when (val range = parseRange(call.request.headers[HttpHeaders.Range], length)) {
            ByteRange.Whole -> Triple(HttpStatusCode.OK, 0L, length)
            ByteRange.Unsatisfiable -> {
                call.response.headers.append(HttpHeaders.ContentRange, "bytes */$length")
                return@get call.fileError(
                    HttpStatusCode.RequestedRangeNotSatisfiable, FileErrorCode.RANGE_NOT_SATISFIABLE, "Range not satisfiable"
                )
            }
            is ByteRange.Part -> {
                call.response.headers.append(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$length")
                Triple(HttpStatusCode.PartialContent, range.first, range.last - range.first + 1)
            }
        }
        call.respondBytesWriter(contentType = presentation.contentType, status = status, contentLength = count) {
            val buffer = ByteArray(64 * 1024)
            RandomAccessFile(file, "r").use { input ->
                withContext(Dispatchers.IO) { input.seek(first) }
                var left = count
                while (left > 0) {
                    val n = withContext(Dispatchers.IO) { input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()) }
                    if (n < 0) break
                    writeFully(buffer, 0, n)
                    left -= n
                }
            }
        }
    }

    // POST /api/files/{sha256}/commit — a saved message / project references the file (idempotent)
    post("/files/{sha256}/commit") {
        val sha = call.hashParameter() ?: return@post
        val attachments = call.userContext().attachments
        when (val result = attachments.commit(sha)) {
            is StoreResult.Ok -> call.respond(
                HttpStatusCode.OK,
                CommitFileResponse(sha, result.value.size, "committed")
            )
            is StoreResult.Failed -> {
                val cached = attachments.cached(sha)
                // No store (sync is off): the file is kept here, which is all there is to commit
                if (result.error == StoreError.NOT_CONFIGURED && cached != null) {
                    call.respond(HttpStatusCode.OK, CommitFileResponse(sha, cached.length(), "local"))
                } else {
                    call.storeFailure(if (result.error == StoreError.NOT_CONFIGURED) StoreResult.Failed(StoreError.NOT_FOUND, "") else result)
                }
            }
        }
    }

    // DELETE /api/files/{sha256} — remove an upload that was never used (a committed file stays)
    delete("/files/{sha256}") {
        val sha = call.hashParameter() ?: return@delete
        call.deletePending(sha)
    }

    // DELETE /api/files — legacy form of the above (body: {"sha256"} or, for files the web saved
    // before the store existed, {"filePath"}).  A path is only ever a file of this user's own
    // pre-store uploads that no saved message uses; nothing else can be deleted through it.
    delete("/files") {
        val body = try {
            call.receive<DeleteFileRequest>()
        } catch (e: Exception) {
            return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
        }
        val sha = body.sha256?.takeIf { it.isNotBlank() }
        if (sha != null) {
            if (!AttachmentHash.isValid(sha)) {
                return@delete call.fileError(HttpStatusCode.BadRequest, FileErrorCode.INVALID_HASH, "sha256 must be 64 lowercase hex characters")
            }
            return@delete call.deletePending(sha)
        }
        val path = body.filePath?.takeIf { it.isNotBlank() }
            ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "sha256 or filePath is required"))
        val attachments = call.userContext().attachments
        val file = File(path)
        // Anything that is not this user's own upload does not exist as far as this route goes
        if (!attachments.isLegacyUpload(file) || !file.isFile) {
            return@delete call.fileError(HttpStatusCode.NotFound, FileErrorCode.NOT_FOUND, "File not found")
        }
        if (attachments.isReferenced { it.local_file_path == path || it.local_file_path == file.canonicalPath }) {
            return@delete call.respond(HttpStatusCode.Conflict, mapOf("error" to "The file is used by a saved message"))
        }
        file.delete()
        call.respond(HttpStatusCode.NoContent)
    }
}

private const val MULTIPART_SLACK_BYTES = 1024L * 1024
