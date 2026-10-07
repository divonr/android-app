package com.example.ApI.data.sync.merge

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.AppSettings
import com.example.ApI.data.model.CustomProviderConfig
import com.example.ApI.data.model.FullCustomProviderConfig
import com.example.ApI.data.model.GitHubConnection
import com.example.ApI.data.model.GoogleWorkspaceConnection
import com.example.ApI.data.model.UserChatHistory
import com.example.ApI.util.AppLogger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Merges one synced file (local vs remote against the last synced base), dispatching by
 * filename to [ChatHistoryMerger] or [JsonMerger] with a per-file policy.
 *
 * Never throws: unparseable local → remote, unparseable remote → local, unparseable base →
 * treated as absent (2-way). Output is encoded with the given [Json] (the app's storage
 * format), so it is exactly what the app itself would write.
 */
object SyncFileMerger {
    private const val TAG = "SyncFileMerger"

    /** Typed file shapes (verified against the models and their storage managers). */
    private sealed class FileKind {
        object ChatHistory : FileKind()
        class Typed<T>(val serializer: KSerializer<T>, val policy: JsonMergePolicy, val fillIds: Boolean = false) : FileKind()
        object Generic : FileKind()
    }

    /** `app_settings.json`: `remoteSync` (credentials) and `current_user` are per device. */
    val APP_SETTINGS_POLICY = JsonMergePolicy(
        deviceLocalKeys = setOf("remoteSync", "current_user"),
        unkeyedArraysAsSets = true  // enabledTools, excludedToolIds, starredModels
    )

    /** A list of objects keyed by `id` (api keys, custom providers). */
    val KEYED_LIST_POLICY = JsonMergePolicy(identityKeys = listOf("id"))

    /** A single connection object whose token/user fields must stay consistent. */
    val ATOMIC_POLICY = JsonMergePolicy(atomicPaths = setOf(""))

    private fun kindOf(filename: String): FileKind = when {
        filename.startsWith("chat_history_") && filename.endsWith(".json") -> FileKind.ChatHistory
        filename == "app_settings.json" -> FileKind.Typed(AppSettings.serializer(), APP_SETTINGS_POLICY)
        filename.startsWith("api_keys_") ->
            FileKind.Typed(ListSerializer(ApiKey.serializer()), KEYED_LIST_POLICY, fillIds = true)
        filename.startsWith("full_custom_providers_") ->
            FileKind.Typed(ListSerializer(FullCustomProviderConfig.serializer()), KEYED_LIST_POLICY, fillIds = true)
        filename.startsWith("custom_providers_") ->
            FileKind.Typed(ListSerializer(CustomProviderConfig.serializer()), KEYED_LIST_POLICY, fillIds = true)
        filename.startsWith("github_auth_") -> FileKind.Typed(GitHubConnection.serializer(), ATOMIC_POLICY)
        filename.startsWith("google_workspace_auth_") -> FileKind.Typed(GoogleWorkspaceConnection.serializer(), ATOMIC_POLICY)
        filename == "skills_enabled.json" ->
            FileKind.Typed(MapSerializer(String.serializer(), Boolean.serializer()), JsonMergePolicy())
        filename == "skills_sources.json" ->
            FileKind.Typed(MapSerializer(String.serializer(), String.serializer()), JsonMergePolicy())
        else -> FileKind.Generic
    }

    fun mergeFile(filename: String, base: String?, local: String, remote: String, json: Json): String {
        return try {
            when (val kind = kindOf(filename)) {
                FileKind.ChatHistory -> mergeChatHistory(base, local, remote, json)
                is FileKind.Typed<*> -> mergeTyped(kind, filename, base, local, remote, json)
                FileKind.Generic -> mergeGeneric(base, local, remote, json)
            }
        } catch (e: Throwable) {
            AppLogger.e("[$TAG] Unexpected failure merging $filename; keeping local", e)
            local
        }
    }

    // ---------------------------------------------------------------------------------

    private fun parseElement(json: Json, text: String?): JsonElement? {
        if (text.isNullOrBlank()) return null
        return try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun mergeChatHistory(base: String?, local: String, remote: String, json: Json): String {
        fun decode(text: String?): UserChatHistory? = try {
            parseElement(json, text)?.let { json.decodeFromJsonElement(UserChatHistory.serializer(), fillChatHistoryIds(it)) }
        } catch (e: Exception) {
            null
        }
        val l = decode(local) ?: return remote
        val r = decode(remote) ?: return local
        val b = decode(base)
        val merged = ChatHistoryMerger.merge(b, l, r)
        return json.encodeToString(UserChatHistory.serializer(), merged)
    }

    private fun <T> mergeTyped(
        kind: FileKind.Typed<T>,
        filename: String,
        base: String?,
        local: String,
        remote: String,
        json: Json
    ): String {
        // Explicit defaults: a value reset to its default is a value, not a missing key
        val withDefaults = Json(json) { encodeDefaults = true }
        fun canonical(text: String?): JsonElement? = try {
            parseElement(json, text)
                ?.let { if (kind.fillIds) fillListIds(filename, it) else it }
                ?.let { json.decodeFromJsonElement(kind.serializer, it) }
                ?.let { withDefaults.encodeToJsonElement(kind.serializer, it) }
        } catch (e: Exception) {
            null
        }
        val l = canonical(local) ?: return remote
        val r = canonical(remote) ?: return local
        val b = canonical(base)
        val merged = JsonMerger.merge(b, l, r, kind.policy)
        return try {
            json.encodeToString(kind.serializer, json.decodeFromJsonElement(kind.serializer, merged))
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Merged $filename does not fit its model; writing raw JSON", e)
            json.encodeToString(JsonElement.serializer(), merged)
        }
    }

    private fun mergeGeneric(base: String?, local: String, remote: String, json: Json): String {
        val l = parseElement(json, local) ?: return remote
        val r = parseElement(json, remote) ?: return local
        val b = parseElement(json, base)
        return json.encodeToString(JsonElement.serializer(), JsonMerger.merge(b, l, r))
    }

    // ---------------------------------------------------------------------------------
    // Deterministic ids for objects stored without one (the models would otherwise invent a
    // random id on every decode, so the same legacy entry would look different on each side)
    // ---------------------------------------------------------------------------------

    private fun JsonObject.with(key: String, value: String): JsonObject =
        JsonObject(LinkedHashMap(this).apply { put(key, JsonPrimitive(value)) })

    private fun JsonObject.missing(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } != true

    private fun fillListIds(filename: String, element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        return JsonArray(element.mapIndexed { i, e ->
            if (e is JsonObject && e.missing("id")) e.with("id", MergeSupport.nameUuid("$filename:$i:$e")) else e
        })
    }

    private fun fillMessageIds(messages: JsonElement?, seed: String): JsonElement? {
        if (messages !is JsonArray) return messages
        return JsonArray(messages.mapIndexed { i, m ->
            if (m is JsonObject && m.missing("id")) m.with("id", MergeSupport.nameUuid("$seed:msg:$i:$m")) else m
        })
    }

    private fun fillChatHistoryIds(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val chats = element["chat_history"] as? JsonArray ?: return element
        val filled = chats.map { chat ->
            if (chat !is JsonObject) return@map chat
            val chatId = (chat["chat_id"] as? JsonPrimitive)?.content ?: return@map chat
            val out = LinkedHashMap(chat)
            fillMessageIds(chat["messages"], chatId)?.let { out["messages"] = it }
            (chat["messageNodes"] as? JsonArray)?.let { nodes ->
                out["messageNodes"] = JsonArray(nodes.mapIndexed { ni, node ->
                    if (node !is JsonObject) return@mapIndexed node
                    var n = node
                    if (n.missing("nodeId")) n = n.with("nodeId", MergeSupport.nameUuid("$chatId:node#$ni"))
                    val nodeId = n.getValue("nodeId").jsonPrimitive.content
                    val variants = n["variants"] as? JsonArray ?: return@mapIndexed n
                    val nodeOut = LinkedHashMap(n)
                    nodeOut["variants"] = JsonArray(variants.mapIndexed { vi, variant ->
                        if (variant !is JsonObject) return@mapIndexed variant
                        var v = variant
                        if (v.missing("variantId")) v = v.with("variantId", MergeSupport.nameUuid("$chatId:$nodeId:variant#$vi"))
                        val variantId = v.getValue("variantId").jsonPrimitive.content
                        val vOut = LinkedHashMap(v)
                        (v["userMessage"] as? JsonObject)?.let { um ->
                            if (um.missing("id")) vOut["userMessage"] = um.with("id", MergeSupport.nameUuid("$chatId:$variantId:user:$um"))
                        }
                        fillMessageIds(v["responses"], "$chatId:$variantId")?.let { vOut["responses"] = it }
                        JsonObject(vOut)
                    })
                    JsonObject(nodeOut)
                })
            }
            JsonObject(out)
        }
        return JsonObject(LinkedHashMap(element).apply { put("chat_history", JsonArray(filled)) })
    }
}
