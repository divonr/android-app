package com.example.ApI.server

import com.example.ApI.data.model.*
import com.example.ApI.data.repository.DeleteMessageResult
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

// ── P4 Request DTOs ──────────────────────────────────────────────────────────

@Serializable
data class CreateChatRequest(
    val previewName: String,
    val systemPrompt: String = "",
    val groupId: String? = null
)

@Serializable
data class PatchChatRequest(
    val previewName: String? = null,
    val systemPrompt: String? = null
)

@Serializable
data class ImportChatRequest(
    val content: String
)

@Serializable
data class CreateGroupRequest(
    val groupName: String
)

@Serializable
data class PatchGroupRequest(
    val groupName: String? = null,
    val systemPrompt: String? = null,
    val isProject: Boolean? = null
)

@Serializable
data class AddApiKeyRequest(
    val id: String? = null,
    val provider: String,
    val key: String,
    val isActive: Boolean = true,
    val customName: String? = null
)

@Serializable
data class PatchApiKeyRequest(
    val key: String? = null,
    val isActive: Boolean? = null,
    val customName: String? = null
)

@Serializable
data class ReorderKeysRequest(
    val fromIndex: Int,
    val toIndex: Int
)

@Serializable
data class CreateBranchRequest(
    val nodeId: String,
    val newUserMessage: Message
)

@Serializable
data class SwitchVariantRequest(
    val variantIndex: Int
)

@Serializable
data class BranchInfoDto(
    val nodeId: String,
    val currentVariantIndex: Int,
    val totalVariants: Int,
    val currentVariantId: String
)

@Serializable
data class CreateBranchResponse(
    val chat: Chat,
    val newVariantId: String
)

@Serializable
data class CreateSkillRequest(
    val name: String? = null,
    val description: String? = null,
    val body: String? = null,
    val importText: String? = null
)

@Serializable
data class PatchSkillRequest(
    val enabled: Boolean
)

@Serializable
data class AddMessageRequest(
    val role: String,
    val text: String,
    val attachments: List<Attachment> = emptyList()
)

@Serializable
data class GenerateTitleRequest(
    val provider: String = "auto"
)

@Serializable
data class TitleResponse(
    val title: String
)

// ── P4 Mutation Routes ───────────────────────────────────────────────────────

fun Route.mutationRoutes() {

    // ========== Providers ==========

    // POST /api/providers/refresh — force refresh cached models
    post("/providers/refresh") {
        val repo = call.application.appModule.repository
        val (changed, _) = repo.forceRefreshModels()
        call.respond(HttpStatusCode.OK, mapOf("ok" to true, "changed" to changed))
    }

    // ========== Chats CRUD ==========

    // POST /api/chats — create a new chat (optionally in a group)
    post("/chats") {
        val body = call.receive<CreateChatRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val chat = if (body.groupId != null) {
            repo.createNewChatInGroup(username, body.previewName, body.groupId, body.systemPrompt)
        } else {
            repo.createNewChat(username, body.previewName, body.systemPrompt)
        }
        call.respond(HttpStatusCode.Created, chat)
    }

    // PATCH /api/chats/{chatId} — rename or update system prompt
    patch("/chats/{chatId}") {
        val chatId = call.parameters["chatId"] ?: return@patch call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val body = call.receive<PatchChatRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()

        // Load current chat
        var chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId }
        if (chat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@patch
        }

        // Apply patches
        if (body.systemPrompt != null) {
            chat = repo.updateChatSystemPrompt(username, chatId, body.systemPrompt) ?: chat
        }
        if (body.previewName != null) {
            // Rename: reload, update, save
            val history = repo.loadChatHistory(username)
            val updatedHistory = history.copy(
                chat_history = history.chat_history.map {
                    if (it.chat_id == chatId) it.copy(preview_name = body.previewName) else it
                }
            )
            repo.saveChatHistory(updatedHistory)
            chat = updatedHistory.chat_history.find { it.chat_id == chatId } ?: chat
        }
        call.respond(HttpStatusCode.OK, chat!!)
    }

    // DELETE /api/chats/{chatId} — delete a chat
    delete("/chats/{chatId}") {
        val chatId = call.parameters["chatId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val history = repo.loadChatHistory(username)
        val exists = history.chat_history.any { it.chat_id == chatId }
        if (!exists) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@delete
        }
        val updated = history.copy(chat_history = history.chat_history.filter { it.chat_id != chatId })
        repo.saveChatHistory(updated)
        call.respond(HttpStatusCode.NoContent)
    }

    // GET /api/chats/{chatId}/export — export chat as JSON
    get("/chats/{chatId}/export") {
        val chatId = call.parameters["chatId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val json = repo.getChatJson(username, chatId)
        if (json == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
        } else {
            call.respondText(json, ContentType.Application.Json)
        }
    }

    // POST /api/chats/import — import a chat from JSON
    post("/chats/import") {
        val body = call.receive<ImportChatRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        if (!repo.validateChatJson(body.content)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid chat JSON"))
            return@post
        }
        val newChatId = repo.importSingleChat(body.content, username)
        if (newChatId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Failed to import chat"))
        } else {
            call.respond(HttpStatusCode.Created, mapOf("chatId" to newChatId))
        }
    }

    // ========== Messages ==========

    // POST /api/chats/{chatId}/messages — add a message to a chat
    post("/chats/{chatId}/messages") {
        val chatId = call.parameters["chatId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val body = call.receive<AddMessageRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val message = Message(
            id = UUID.randomUUID().toString(),
            role = body.role,
            text = body.text,
            attachments = body.attachments,
            datetime = java.time.Instant.now().toString()
        )
        val updatedChat = repo.addMessageToChat(username, chatId, message)
        if (updatedChat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
        } else {
            call.respond(HttpStatusCode.Created, message)
        }
    }

    // DELETE /api/chats/{chatId}/messages/{messageId} — delete from that message onward
    delete("/chats/{chatId}/messages/{messageId}") {
        val chatId = call.parameters["chatId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val messageId = call.parameters["messageId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing messageId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId }
            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))

        val message = chat.messages.find { it.id == messageId }
            ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "Message not found"))

        val updated = repo.deleteMessagesFromPoint(username, chatId, message)
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
        } else {
            call.respond(HttpStatusCode.OK, updated)
        }
    }

    // POST /api/chats/{chatId}/generate-title — regenerate title regardless of message count
    post("/chats/{chatId}/generate-title") {
        val chatId = call.parameters["chatId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val body = try {
            call.receive<GenerateTitleRequest>()
        } catch (e: Exception) {
            GenerateTitleRequest()
        }
        val repo = call.application.appModule.repository
        val username = call.currentUsername()

        // Verify chat exists
        val chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId }
        if (chat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@post
        }

        val providerArg = body.provider.takeIf { it.isNotBlank() && it != "auto" }
        val titleGenerator = call.application.appModule.titleGenerator

        try {
            val title = titleGenerator.generate(username, chatId, providerArg)

            // Persist the generated title
            if (title.isNotBlank()) {
                val history = repo.loadChatHistory(username)
                val updated = history.copy(
                    chat_history = history.chat_history.map { c ->
                        if (c.chat_id == chatId) c.copy(preview_name = title) else c
                    }
                )
                repo.saveChatHistory(updated)
            }

            call.respond(HttpStatusCode.OK, TitleResponse(title = title))
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Title generation failed: ${e.message}")
            )
        }
    }

    // ========== Branching ==========

    // POST /api/chats/{chatId}/branch — create a new branch at a node
    post("/chats/{chatId}/branch") {
        val chatId = call.parameters["chatId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val body = call.receive<CreateBranchRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()

        // Ensure branching structure exists
        repo.ensureBranchingStructure(username, chatId)

        val result = repo.createBranch(username, chatId, body.nodeId, body.newUserMessage)
        if (result == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat or node not found"))
        } else {
            val (updatedChat, newVariantId) = result
            call.respond(HttpStatusCode.Created, CreateBranchResponse(updatedChat, newVariantId))
        }
    }

    // POST /api/chats/{chatId}/nodes/{nodeId}/switch — switch variant at a node
    post("/chats/{chatId}/nodes/{nodeId}/switch") {
        val chatId = call.parameters["chatId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val nodeId = call.parameters["nodeId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing nodeId")
        )
        val body = call.receive<SwitchVariantRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()

        val updatedChat = repo.switchVariant(username, chatId, nodeId, body.variantIndex)
        if (updatedChat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat or node not found"))
        } else {
            call.respond(HttpStatusCode.OK, updatedChat)
        }
    }

    // GET /api/chats/{chatId}/nodes/{nodeId}/branch-info — get branch info for a node
    get("/chats/{chatId}/nodes/{nodeId}/branch-info") {
        val chatId = call.parameters["chatId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val nodeId = call.parameters["nodeId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing nodeId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId }
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))

        val branchInfo = repo.getBranchInfo(chat, nodeId)
        if (branchInfo == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Node not found"))
        } else {
            call.respond(HttpStatusCode.OK, BranchInfoDto(
                nodeId = branchInfo.nodeId,
                currentVariantIndex = branchInfo.currentVariantIndex,
                totalVariants = branchInfo.totalVariants,
                currentVariantId = branchInfo.currentVariantId
            ))
        }
    }

    // DELETE /api/chats/{chatId}/messages/{messageId} handled above via deleteMessagesFromPoint.
    // For branch-aware deletion (deleteMessageFromBranch), expose a separate endpoint:

    // DELETE /api/chats/{chatId}/nodes/messages/{messageId} — delete from branching structure
    delete("/chats/{chatId}/branch/messages/{messageId}") {
        val chatId = call.parameters["chatId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val messageId = call.parameters["messageId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing messageId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()

        when (val result = repo.deleteMessageFromBranch(username, chatId, messageId)) {
            is DeleteMessageResult.Success -> call.respond(HttpStatusCode.OK, result.updatedChat)
            is DeleteMessageResult.CannotDeleteBranchPoint ->
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
            is DeleteMessageResult.Error ->
                call.respond(HttpStatusCode.NotFound, mapOf("error" to result.message))
        }
    }

    // ========== Groups CRUD ==========

    // POST /api/groups — create a new group
    post("/groups") {
        val body = call.receive<CreateGroupRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val group = repo.createNewGroup(username, body.groupName)
        call.respond(HttpStatusCode.Created, group)
    }

    // PATCH /api/groups/{groupId} — rename group, update system prompt, toggle project
    patch("/groups/{groupId}") {
        val groupId = call.parameters["groupId"] ?: return@patch call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing groupId")
        )
        val body = call.receive<PatchGroupRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()

        // Verify group exists
        val history = repo.loadChatHistory(username)
        val group = history.groups.find { it.group_id == groupId }
        if (group == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
            return@patch
        }

        var success = true
        if (body.groupName != null) {
            success = success && repo.renameGroup(username, groupId, body.groupName)
        }
        if (body.isProject != null) {
            success = success && repo.updateGroupProjectStatus(username, groupId, body.isProject)
        }
        if (body.systemPrompt != null) {
            // system_prompt is part of ChatGroup — update it via save
            val updatedHistory = repo.loadChatHistory(username)
            val updatedGroups = updatedHistory.groups.map {
                if (it.group_id == groupId) it.copy(system_prompt = body.systemPrompt) else it
            }
            repo.saveChatHistory(updatedHistory.copy(groups = updatedGroups))
        }

        val updatedGroup = repo.loadChatHistory(username).groups.find { it.group_id == groupId }
        if (updatedGroup == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found after update"))
        } else {
            call.respond(HttpStatusCode.OK, updatedGroup)
        }
    }

    // DELETE /api/groups/{groupId} — delete a group
    delete("/groups/{groupId}") {
        val groupId = call.parameters["groupId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing groupId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val history = repo.loadChatHistory(username)
        if (history.groups.none { it.group_id == groupId }) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
            return@delete
        }
        repo.deleteGroup(username, groupId)
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /api/groups/{groupId}/chats/{chatId} — add chat to group
    post("/groups/{groupId}/chats/{chatId}") {
        val groupId = call.parameters["groupId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing groupId")
        )
        val chatId = call.parameters["chatId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val ok = repo.addChatToGroup(username, chatId, groupId)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat or group not found"))
        } else {
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }

    // DELETE /api/groups/{groupId}/chats/{chatId} — remove chat from group
    delete("/groups/{groupId}/chats/{chatId}") {
        val groupId = call.parameters["groupId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing groupId")
        )
        val chatId = call.parameters["chatId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        // removeChatFromGroup removes from any group, groupId is validated for correctness
        val history = repo.loadChatHistory(username)
        val chat = history.chat_history.find { it.chat_id == chatId }
        if (chat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@delete
        }
        if (chat.group != groupId) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Chat is not in this group"))
            return@delete
        }
        repo.removeChatFromGroup(username, chatId)
        call.respond(HttpStatusCode.OK, mapOf("ok" to true))
    }

    // POST /api/groups/{groupId}/attachments — add attachment to group
    post("/groups/{groupId}/attachments") {
        val groupId = call.parameters["groupId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing groupId")
        )
        val attachment = call.receive<Attachment>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val ok = repo.addAttachmentToGroup(username, groupId, attachment)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
        } else {
            val updatedGroup = repo.loadChatHistory(username).groups.find { it.group_id == groupId }
            call.respond(HttpStatusCode.Created, updatedGroup!!)
        }
    }

    // DELETE /api/groups/{groupId}/attachments/{attachmentIndex} — remove attachment from group
    delete("/groups/{groupId}/attachments/{attachmentIndex}") {
        val groupId = call.parameters["groupId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing groupId")
        )
        val indexStr = call.parameters["attachmentIndex"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing attachmentIndex")
        )
        val index = indexStr.toIntOrNull() ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Invalid attachmentIndex")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val ok = repo.removeAttachmentFromGroup(username, groupId, index)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group or attachment not found"))
        } else {
            val updatedGroup = repo.loadChatHistory(username).groups.find { it.group_id == groupId }
            if (updatedGroup == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
            } else {
                call.respond(HttpStatusCode.OK, updatedGroup)
            }
        }
    }

    // ========== API Keys CRUD ==========

    // POST /api/keys — add a new API key (full key value accepted)
    post("/keys") {
        val body = call.receive<AddApiKeyRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val key = ApiKey(
            id = body.id ?: UUID.randomUUID().toString(),
            provider = body.provider,
            key = body.key,
            isActive = body.isActive,
            customName = body.customName
        )
        repo.addApiKey(username, key)
        call.respond(HttpStatusCode.Created, key.masked())
    }

    // PATCH /api/keys/{keyId} — update key value, active flag, or custom name
    patch("/keys/{keyId}") {
        val keyId = call.parameters["keyId"] ?: return@patch call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing keyId")
        )
        val body = call.receive<PatchApiKeyRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val keys = repo.loadApiKeys(username)
        val existing = keys.find { it.id == keyId }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Key not found"))
            return@patch
        }
        val updated = existing.copy(
            key = body.key ?: existing.key,
            isActive = body.isActive ?: existing.isActive,
            customName = if (body.customName != null) body.customName else existing.customName
        )
        val updatedKeys = keys.map { if (it.id == keyId) updated else it }
        repo.saveApiKeys(username, updatedKeys)
        call.respond(HttpStatusCode.OK, updated.masked())
    }

    // DELETE /api/keys/{keyId} — delete an API key
    delete("/keys/{keyId}") {
        val keyId = call.parameters["keyId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing keyId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val keys = repo.loadApiKeys(username)
        if (keys.none { it.id == keyId }) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Key not found"))
            return@delete
        }
        repo.deleteApiKey(username, keyId)
        call.respond(HttpStatusCode.NoContent)
    }

    // POST /api/keys/{keyId}/toggle — toggle isActive
    post("/keys/{keyId}/toggle") {
        val keyId = call.parameters["keyId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing keyId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val keys = repo.loadApiKeys(username)
        if (keys.none { it.id == keyId }) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Key not found"))
            return@post
        }
        repo.toggleApiKeyStatus(username, keyId)
        val updated = repo.loadApiKeys(username).find { it.id == keyId }!!
        call.respond(HttpStatusCode.OK, updated.masked())
    }

    // POST /api/keys/reorder — reorder keys by fromIndex/toIndex
    post("/keys/reorder") {
        val body = call.receive<ReorderKeysRequest>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val keys = repo.loadApiKeys(username)
        if (body.fromIndex < 0 || body.fromIndex >= keys.size ||
            body.toIndex < 0 || body.toIndex >= keys.size) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Index out of bounds"))
            return@post
        }
        repo.reorderApiKeys(username, body.fromIndex, body.toIndex)
        val updated = repo.loadApiKeys(username).map { it.masked() }
        call.respond(HttpStatusCode.OK, updated)
    }

    // ========== Settings ==========

    // PATCH /api/settings — partial settings update (GET-then-PATCH semantics)
    patch("/settings") {
        val repo = call.application.appModule.repository
        val current = repo.loadAppSettings()
        // Receive as raw JSON so we can merge only supplied fields
        val bodyJson = call.receiveText()
        val patch = lenientJson.parseToJsonElement(bodyJson).jsonObject

        // Build merged AppSettings by overlaying patch fields onto current
        val merged = current.copy(
            selected_provider = patch["selected_provider"]?.jsonPrimitive?.contentOrNull ?: current.selected_provider,
            selected_model = patch["selected_model"]?.jsonPrimitive?.contentOrNull ?: current.selected_model,
            temperature = patch["temperature"]?.jsonPrimitive?.doubleOrNull ?: current.temperature,
            multiMessageMode = patch["multiMessageMode"]?.jsonPrimitive?.booleanOrNull ?: current.multiMessageMode,
            skipWelcomeScreen = patch["skipWelcomeScreen"]?.jsonPrimitive?.booleanOrNull ?: current.skipWelcomeScreen,
            titleGenerationSettings = if (patch.containsKey("titleGenerationSettings")) {
                val tgsJson = patch["titleGenerationSettings"]!!.jsonObject
                current.titleGenerationSettings.copy(
                    enabled = tgsJson["enabled"]?.jsonPrimitive?.booleanOrNull ?: current.titleGenerationSettings.enabled,
                    provider = tgsJson["provider"]?.jsonPrimitive?.contentOrNull ?: current.titleGenerationSettings.provider,
                    updateOnExtension = tgsJson["updateOnExtension"]?.jsonPrimitive?.booleanOrNull ?: current.titleGenerationSettings.updateOnExtension
                )
            } else current.titleGenerationSettings
        )
        repo.saveAppSettings(merged)
        call.respond(HttpStatusCode.OK, merged)
    }

    // ========== Custom Providers (OpenAI-compatible) ==========

    // GET /api/custom-providers
    get("/custom-providers") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        call.respond(HttpStatusCode.OK, repo.loadCustomProviders(username))
    }

    // POST /api/custom-providers
    post("/custom-providers") {
        val body = call.receive<CustomProviderConfig>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        repo.addCustomProvider(username, body)
        call.respond(HttpStatusCode.Created, body)
    }

    // PUT /api/custom-providers/{providerId}
    put("/custom-providers/{providerId}") {
        val providerId = call.parameters["providerId"] ?: return@put call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing providerId")
        )
        val body = call.receive<CustomProviderConfig>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val existing = repo.loadCustomProviders(username).find { it.id == providerId }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Provider not found"))
            return@put
        }
        repo.updateCustomProvider(username, providerId, body)
        val updated = repo.loadCustomProviders(username).find { it.id == providerId } ?: body
        call.respond(HttpStatusCode.OK, updated)
    }

    // DELETE /api/custom-providers/{providerId}
    delete("/custom-providers/{providerId}") {
        val providerId = call.parameters["providerId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing providerId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val existing = repo.loadCustomProviders(username).find { it.id == providerId }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Provider not found"))
            return@delete
        }
        repo.deleteCustomProvider(username, providerId)
        call.respond(HttpStatusCode.NoContent)
    }

    // ========== Full Custom Providers ==========

    // GET /api/full-custom-providers
    get("/full-custom-providers") {
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        call.respond(HttpStatusCode.OK, repo.loadFullCustomProviders(username))
    }

    // POST /api/full-custom-providers
    post("/full-custom-providers") {
        val body = call.receive<FullCustomProviderConfig>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        repo.addFullCustomProvider(username, body)
        call.respond(HttpStatusCode.Created, body)
    }

    // PUT /api/full-custom-providers/{providerId}
    put("/full-custom-providers/{providerId}") {
        val providerId = call.parameters["providerId"] ?: return@put call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing providerId")
        )
        val body = call.receive<FullCustomProviderConfig>()
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val existing = repo.loadFullCustomProviders(username).find { it.id == providerId }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Provider not found"))
            return@put
        }
        repo.updateFullCustomProvider(username, providerId, body)
        val updated = repo.loadFullCustomProviders(username).find { it.id == providerId } ?: body
        call.respond(HttpStatusCode.OK, updated)
    }

    // DELETE /api/full-custom-providers/{providerId}
    delete("/full-custom-providers/{providerId}") {
        val providerId = call.parameters["providerId"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing providerId")
        )
        val repo = call.application.appModule.repository
        val username = call.currentUsername()
        val existing = repo.loadFullCustomProviders(username).find { it.id == providerId }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Provider not found"))
            return@delete
        }
        repo.deleteFullCustomProvider(username, providerId)
        call.respond(HttpStatusCode.NoContent)
    }

    // ========== Skills ==========

    // GET /api/skills
    get("/skills") {
        val repo = call.application.appModule.repository
        val skills = repo.getInstalledSkills()
        call.respond(HttpStatusCode.OK, skills)
    }

    // GET /api/skills/{skillName} — raw SKILL.md content
    get("/skills/{skillName}") {
        val skillName = call.parameters["skillName"] ?: return@get call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing skillName")
        )
        val repo = call.application.appModule.repository
        val content = repo.getSkillMdContent(skillName)
        if (content == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Skill not found"))
        } else {
            call.respondText(content, ContentType.Text.Plain)
        }
    }

    // POST /api/skills — create or import a skill
    post("/skills") {
        val body = call.receive<CreateSkillRequest>()
        val repo = call.application.appModule.repository
        val skill = if (body.importText != null) {
            // Import from full markdown text
            repo.importSkillFromText(body.importText)
        } else if (body.name != null && body.description != null) {
            repo.createSkill(body.name, body.description, body.body ?: "")
        } else {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Provide either 'importText' or 'name'+'description'"))
            return@post
        }
        if (skill == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Failed to create/import skill"))
        } else {
            call.respond(HttpStatusCode.Created, skill)
        }
    }

    // PATCH /api/skills/{skillName} — toggle enabled state
    patch("/skills/{skillName}") {
        val skillName = call.parameters["skillName"] ?: return@patch call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing skillName")
        )
        val body = call.receive<PatchSkillRequest>()
        val repo = call.application.appModule.repository
        val skills = repo.getInstalledSkills()
        if (skills.none { it.directoryName == skillName }) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Skill not found"))
            return@patch
        }
        repo.setSkillEnabled(skillName, body.enabled)
        val updated = repo.getInstalledSkills().find { it.directoryName == skillName }
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Skill not found after update"))
        } else {
            call.respond(HttpStatusCode.OK, updated)
        }
    }

    // DELETE /api/skills/{skillName}
    delete("/skills/{skillName}") {
        val skillName = call.parameters["skillName"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing skillName")
        )
        val repo = call.application.appModule.repository
        val skills = repo.getInstalledSkills()
        if (skills.none { it.directoryName == skillName }) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Skill not found"))
            return@delete
        }
        repo.deleteSkill(skillName)
        call.respond(HttpStatusCode.NoContent)
    }
}
