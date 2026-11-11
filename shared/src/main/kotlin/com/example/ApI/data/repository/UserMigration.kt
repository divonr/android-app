package com.example.ApI.data.repository

import com.example.ApI.data.model.AppSettings
import com.example.ApI.util.AppLogger
import com.example.ApI.util.AtomicFiles
import com.example.ApI.util.FileLocks
import com.example.ApI.util.SyncHolds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

// ───────────────────────── Result type ───────────────────────────────────────

/**
 * Outcome of a [UserMigration.migrateToAccount] call.
 */
sealed class MigrationResult {
    /** `old == newUsername` at call time (or settings unreadable) — nothing was done. */
    object NoOp : MigrationResult()

    /**
     * Only [AppSettings.current_user] was updated: the device was on another account
     * (account switch — that account's data stays local under its name and is never moved
     * into [newUsername]).
     *
     * @param previousUsername The account the device was on.
     */
    data class Switched(val newUsername: String, val previousUsername: String = "") : MigrationResult()

    /**
     * The local `default` user's files were moved to [newUsername] and map entries were
     * re-keyed.
     *
     * @param renamedFiles List of filenames that were successfully moved.
     * @param skippedFiles Files left under [oldUsername] because [newUsername] already had one.
     */
    data class Migrated(
        val oldUsername: String,
        val newUsername: String,
        val renamedFiles: List<String>,
        val skippedFiles: List<String> = emptyList()
    ) : MigrationResult()
}

// ───────────────────────── Migration logic ───────────────────────────────────

/**
 * Moves local data into the account after a Google sign-in.
 *
 * Only the local, never-signed-in user [DEFAULT_USER] is migrated: its per-user files are renamed
 * to the canonical [newUsername] (the next sync merges them with the account's server copy).
 * A device on another account only switches `current_user` (that account's data stays local
 * under its own name; nothing leaks into the new account).  A file is never renamed onto an
 * existing one.
 *
 * Idempotent: calling it a second time with the same [newUsername] returns
 * [MigrationResult.NoOp].
 *
 * Per-user file prefixes handled:
 * `chat_history_`, `api_keys_`, `custom_providers_`, `full_custom_providers_`,
 * `github_auth_`, `google_workspace_auth_`
 *
 * Map entries re-keyed in [AppSettings]: `githubConnections`,
 * `googleWorkspaceConnections`.
 */
object UserMigration {

    private const val TAG = "UserMigration"

    /** The local user of a device that never signed in. */
    const val DEFAULT_USER = "default"

    private val FILE_PREFIXES = listOf(
        "chat_history_",
        "api_keys_",
        "custom_providers_",
        "full_custom_providers_",
        "github_auth_",
        "google_workspace_auth_"
    )

    /**
     * Migrate local data from the current user to [newUsername].
     *
     * @param internalDir  The `llm_data` directory (where `app_settings.json` lives).
     * @param json         JSON instance with `coerceInputValues = true`.
     * @param newUsername  Canonical username returned by the sync server.
     * @return             A [MigrationResult] describing what happened.
     */
    fun migrateToAccount(
        internalDir: File,
        json: Json,
        newUsername: String
    ): MigrationResult {
        // ── Load current settings ─────────────────────────────────────────────
        val settingsFile = File(internalDir, "app_settings.json")
        return FileLocks.withLock(settingsFile) {
            val settings: AppSettings = if (settingsFile.exists()) {
                try {
                    json.decodeFromString<AppSettings>(settingsFile.readText())
                } catch (e: Exception) {
                    AppLogger.e("[$TAG] Could not parse app_settings.json; aborting migration", e)
                    return@withLock MigrationResult.NoOp
                }
            } else {
                AppLogger.w("[$TAG] app_settings.json not found; aborting migration")
                return@withLock MigrationResult.NoOp
            }
            migrate(internalDir, json, settingsFile, settings, newUsername)
        }
    }

    private fun migrate(
        internalDir: File,
        json: Json,
        settingsFile: File,
        settings: AppSettings,
        newUsername: String
    ): MigrationResult {
        val old = settings.current_user

        // ── Idempotency guard ─────────────────────────────────────────────────
        if (old == newUsername) {
            AppLogger.d("[$TAG] migrateToAccount: already on $newUsername — no-op")
            return MigrationResult.NoOp
        }

        // ── Another account's data is never moved into this one ───────────────
        if (old != DEFAULT_USER) {
            saveSettings(settingsFile, json, settings.copy(current_user = newUsername))
            AppLogger.i("[$TAG] Switch-only: $old → $newUsername ($old's data stays local)")
            return MigrationResult.Switched(newUsername, old)
        }

        // ── Move the default user's files (never onto an existing file) ───────
        val renamedFiles = mutableListOf<String>()
        val skippedFiles = mutableListOf<String>()
        for (prefix in FILE_PREFIXES) {
            val src = File(internalDir, "$prefix$old.json")
            val dst = File(internalDir, "$prefix$newUsername.json")
            val moved = FileLocks.withLock(src) {
                FileLocks.withLock(dst) {
                    when {
                        !src.exists() -> null
                        dst.exists() -> {
                            AppLogger.w("[$TAG] ${dst.name} already exists; leaving ${src.name} in place")
                            false
                        }
                        else -> moveFile(src, dst, json, if (prefix == "chat_history_") newUsername else null)
                    }
                }
            } ?: continue
            if (moved) {
                renamedFiles.add(src.name)
                AppLogger.d("[$TAG] Moved ${src.name} → ${dst.name}")
            } else {
                skippedFiles.add(src.name)
            }
        }

        // ── Re-key map entries in AppSettings ────────────────────────────────
        val updatedGithub = rekeyMap(settings.githubConnections, old, newUsername)
        val updatedWorkspace = rekeyMap(settings.googleWorkspaceConnections, old, newUsername)

        val updated = settings.copy(
            current_user = newUsername,
            githubConnections = updatedGithub,
            googleWorkspaceConnections = updatedWorkspace
        )
        saveSettings(settingsFile, json, updated)

        AppLogger.i("[$TAG] Migrated $old → $newUsername (moved ${renamedFiles.size} files, skipped ${skippedFiles.size})")
        return MigrationResult.Migrated(
            oldUsername = old,
            newUsername = newUsername,
            renamedFiles = renamedFiles,
            skippedFiles = skippedFiles
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Move [src] to the (absent) [dst]; for a chat history, rewrite its `user_name` to
     * [chatUserName].  Returns true if [dst] now holds the content and [src] is gone.
     */
    private fun moveFile(src: File, dst: File, json: Json, chatUserName: String?): Boolean {
        return try {
            val rewritten = chatUserName?.let { rewriteUserName(src.readText(), json, it) }
            if (rewritten != null) {
                AtomicFiles.write(dst, rewritten)
                if (!src.delete()) AppLogger.w("[$TAG] Could not delete ${src.name} after copying it")
            } else if (!src.renameTo(dst)) {
                // e.g. a file system that refuses rename: copy, then remove the source
                AtomicFiles.writeBytes(dst, src.readBytes())
                if (!src.delete()) AppLogger.w("[$TAG] Could not delete ${src.name} after copying it")
            }
            SyncHolds.moveHold(src, dst)
            true
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Failed to move ${src.name}", e)
            false
        }
    }

    /** The chat history [text] with `user_name` = [username] (null if it doesn't parse). */
    private fun rewriteUserName(text: String, json: Json, username: String): String? = try {
        val obj = json.parseToJsonElement(text) as? JsonObject
        obj?.let {
            json.encodeToString(JsonObject.serializer(), JsonObject(LinkedHashMap(it).apply { put("user_name", JsonPrimitive(username)) }))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Re-key a map: if [oldKey] is present, move its value to [newKey].
     * Entries for other keys are preserved unchanged.
     */
    private fun <V> rekeyMap(map: Map<String, V>, oldKey: String, newKey: String): Map<String, V> {
        val entry = map[oldKey] ?: return map
        if (newKey in map) return map  // its file was not moved either
        return map - oldKey + (newKey to entry)
    }

    private fun saveSettings(file: File, json: Json, settings: AppSettings) {
        try {
            AtomicFiles.write(file, json.encodeToString(settings))
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Failed to save app_settings.json after migration", e)
        }
    }
}
