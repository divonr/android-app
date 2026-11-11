package com.example.ApI.data

import java.io.File

interface PlatformStorage {
    val filesDir: File
    val downloadsDir: File?

    /**
     * Per-device cache of attachment files downloaded from the account's attachment store, one
     * file per content hash (`attachments_cache/<sha256>`). Not synced; safe to delete.
     * On the web server [filesDir] is per user, so this is too.
     */
    val attachmentsCacheDir: File get() = File(filesDir, "attachments_cache")
}
