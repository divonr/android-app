package com.example.ApI.data.repository

import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.model.GitHubConnectionInfo
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.util.JsonConfig
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UserMigrationTest {

    @TempDir
    lateinit var dir: File

    private val json = JsonConfig.prettyPrint

    private fun settings(user: String, github: Map<String, GitHubConnectionInfo> = emptyMap()) =
        File(dir, "app_settings.json").writeText(
            json.encodeToString(AppSettings(current_user = user, selected_provider = "openai", selected_model = "m", githubConnections = github))
        )

    private fun readSettings() = json.decodeFromString(AppSettings.serializer(), File(dir, "app_settings.json").readText())

    private fun history(user: String, vararg chats: String) =
        File(dir, "chat_history_$user.json").writeText(
            json.encodeToString(UserChatHistory(user, chats.map { Chat(chat_id = it, preview_name = it, messages = emptyList()) }))
        )

    @Test
    fun `the default user's files move into the account with user_name rewritten`() {
        settings("default", mapOf("default" to GitHubConnectionInfo("default", "gh", 1L)))
        history("default", "c1")
        File(dir, "api_keys_default.json").writeText("[]")

        val result = UserMigration.migrateToAccount(dir, json, "acct")

        assertIs<MigrationResult.Migrated>(result)
        assertEquals(setOf("chat_history_default.json", "api_keys_default.json"), result.renamedFiles.toSet())
        assertFalse(File(dir, "chat_history_default.json").exists())
        val moved = json.decodeFromString(UserChatHistory.serializer(), File(dir, "chat_history_acct.json").readText())
        assertEquals("acct", moved.user_name, "user_name inside the file must follow the account")
        assertEquals(listOf("c1"), moved.chat_history.map { it.chat_id })
        assertEquals("[]", File(dir, "api_keys_acct.json").readText())
        assertEquals("acct", readSettings().current_user)
        assertEquals(setOf("acct"), readSettings().githubConnections.keys)
        // Saves now go to the tracked file
        ChatHistoryManager(dir, json).createNewChat("acct", "c2")
        assertEquals(2, ChatHistoryManager(dir, json).loadChatHistory("acct").chat_history.size)
        assertFalse(File(dir, "chat_history_default.json").exists())
    }

    @Test
    fun `another account's data is never moved - switch only`() {
        settings("alice")
        history("alice", "a1")

        val result = UserMigration.migrateToAccount(dir, json, "bob")

        assertEquals(MigrationResult.Switched("bob", "alice"), result)
        assertTrue(File(dir, "chat_history_alice.json").exists(), "alice's data stays local under alice")
        assertFalse(File(dir, "chat_history_bob.json").exists(), "nothing of alice's leaks into bob")
        assertEquals("bob", readSettings().current_user)
    }

    @Test
    fun `a file is never renamed onto an existing one`() {
        settings("default")
        history("default", "local")
        history("acct", "existing")
        File(dir, "api_keys_default.json").writeText("[1]")

        val result = UserMigration.migrateToAccount(dir, json, "acct")

        assertIs<MigrationResult.Migrated>(result)
        assertEquals(listOf("chat_history_default.json"), result.skippedFiles)
        assertEquals(listOf("existing"), ChatHistoryManager(dir, json).loadChatHistory("acct").chat_history.map { it.chat_id })
        assertEquals(listOf("local"), ChatHistoryManager(dir, json).loadChatHistory("default").chat_history.map { it.chat_id }, "kept, not destroyed")
        assertEquals("[1]", File(dir, "api_keys_acct.json").readText())
    }

    @Test
    fun `migrating twice is a no-op`() {
        settings("default")
        history("default", "c")
        UserMigration.migrateToAccount(dir, json, "acct")
        assertEquals(MigrationResult.NoOp, UserMigration.migrateToAccount(dir, json, "acct"))
    }
}
