package com.example.ApI.data.repository

import com.example.ApI.data.model.AppSettings
import com.example.ApI.util.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// ───────────────────────── Result type ───────────────────────────────────────

/**
 * Outcome of a [UserMigration.migrateToAccount] call.
 */
sealed class MigrationResult {
    /** `old == newUsername` at call time — nothing was done. */
    object NoOp : MigrationResult()

    /**
     * The target username already had local data; only [AppSettings.current_user]
     * was updated (returning-account / account-switch case).
     */
    data class Switched(val newUsername: String) : MigrationResult()

    /**
     * Per-user files were renamed from [oldUsername] to [newUsername] and map
     * entries were re-keyed.
     *
     * @param renamedFiles List of filenames that were successfully renamed.
     */
    data class Migrated(
        val oldUsername: String,
        val newUsername: String,
        val renamedFiles: List<String>
    ) : MigrationResult()
}

// ───────────────────────── Migration logic ───────────────────────────────────

/**
 * One-time migration that renames all per-user local files from the current
 * [AppSettings.current_user] to the canonical [newUsername] returned by the
 * sync server after a Google sign-in.
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
        val settings: AppSettings = if (settingsFile.exists()) {
            try {
                json.decodeFromString<AppSettings>(settingsFile.readText())
            } catch (e: Exception) {
                AppLogger.e("[$TAG] Could not parse app_settings.json; aborting migration", e)
                return MigrationResult.NoOp
            }
        } else {
            AppLogger.w("[$TAG] app_settings.json not found; aborting migration")
            return MigrationResult.NoOp
        }

        val old = settings.current_user

        // ── Idempotency guard ─────────────────────────────────────────────────
        if (old == newUsername) {
            AppLogger.d("[$TAG] migrateToAccount: already on $newUsername — no-op")
            return MigrationResult.NoOp
        }

        // ── Check if the target already has local data (returning-account case) ─
        val targetChatHistory = File(internalDir, "chat_history_$newUsername.json")
        if (targetChatHistory.exists()) {
            // Just switch current_user; leave old files untouched
            val updated = settings.copy(current_user = newUsername)
            saveSettings(settingsFile, json, updated)
            AppLogger.i("[$TAG] Switch-only: $old → $newUsername (target data already exists)")
            return MigrationResult.Switched(newUsername)
        }

        // ── Rename per-user files ─────────────────────────────────────────────
        val renamedFiles = mutableListOf<String>()
        for (prefix in FILE_PREFIXES) {
            val src = File(internalDir, "$prefix$old.json")
            if (!src.exists()) continue
            val dst = File(internalDir, "$prefix$newUsername.json")
            try {
                src.renameTo(dst)
                renamedFiles.add(src.name)
                AppLogger.d("[$TAG] Renamed ${src.name} → ${dst.name}")
            } catch (e: Exception) {
                AppLogger.e("[$TAG] Failed to rename ${src.name}", e)
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

        AppLogger.i("[$TAG] Migrated $old → $newUsername (renamed ${renamedFiles.size} files)")
        return MigrationResult.Migrated(
            oldUsername = old,
            newUsername = newUsername,
            renamedFiles = renamedFiles
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Re-key a map: if [oldKey] is present, move its value to [newKey].
     * Entries for other keys are preserved unchanged.
     */
    private fun <V> rekeyMap(map: Map<String, V>, oldKey: String, newKey: String): Map<String, V> {
        val entry = map[oldKey] ?: return map
        return map - oldKey + (newKey to entry)
    }

    private fun saveSettings(file: File, json: Json, settings: AppSettings) {
        try {
            file.writeText(json.encodeToString(settings))
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Failed to save app_settings.json after migration", e)
        }
    }
}
