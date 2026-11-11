package com.example.ApI.server.streaming

import com.example.ApI.data.cloud.ThinkingBudgetWire
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.ThinkingBudgetValue
import com.example.ApI.data.model.TitleGenerationSettings
import com.example.ApI.data.model.Chat
import com.example.ApI.data.repository.DataRepository
import com.example.ApI.data.repository.ReplyAnchor
import com.example.ApI.server.TitleGenerator
import com.example.ApI.server.runs.AttachmentFiles
import com.example.ApI.server.runs.RunSpec
import com.example.ApI.server.runs.WEB_ATTACHMENTS_TIMEOUT_MS
import com.example.ApI.server.runs.attachmentsForRun
import com.example.ApI.server.runs.startAndStreamRun
import com.example.ApI.server.userContext
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

// ── Request DTOs ──────────────────────────────────────────────────────────────

/**
 * JSON body for POST /api/chat/send.
 *
 * The client sends the full message history (including the new user message) so the
 * server doesn't need a separate "add message" round-trip before streaming.  The
 * server persists the new user message and streams the assistant response.
 *
 * [messages] — full conversation history INCLUDING the new user message at the end.
 * [projectAttachments] — optional group/project context attachments.
 * [thinkingBudget] — "none" | "low" | "medium" | "high" | "max" | integer string
 *                    (maps to [ThinkingBudgetValue]; see [parseBudget]).
 */
@Serializable
data class SendRequest(
    val chatId: String,
    val provider: String,
    val modelName: String,
    val messages: List<Message>,
    val systemPrompt: String = "",
    val webSearchEnabled: Boolean = false,
    val enabledToolIds: List<String> = emptyList(),
    val thinkingBudget: String = "none",
    val temperature: Float? = null,
    val projectAttachments: List<Attachment> = emptyList(),
    /**
     * When false, the server does NOT persist the trailing user message before streaming.
     * Used by multi-message mode: the client already persisted the buffered user messages
     * via POST /api/chats/{id}/messages and only wants the assistant reply now.
     */
    val persistUserMessage: Boolean = true
)

/**
 * JSON body for POST /api/chats/{chatId}/messages/{messageId}/resend.
 *
 * Like Android's resend, the server creates a new variant (branch) in the node of the
 * message and streams a new assistant response into it (see [resendRoute]).
 */
@Serializable
data class ResendRequest(
    val provider: String,
    val modelName: String,
    val systemPrompt: String = "",
    val webSearchEnabled: Boolean = false,
    val enabledToolIds: List<String> = emptyList(),
    val thinkingBudget: String = "none",
    val temperature: Float? = null
)

// ── Thinking-budget parser ────────────────────────────────────────────────────

/**
 * The legacy forms ("none" | "low" | "medium" | "high" | "max" | integer string) plus the
 * explicit `effort:<level>` / `tokens:<count>` a device sends for values the legacy forms
 * cannot carry (see [ThinkingBudgetWire]).
 */
internal fun parseBudget(raw: String): ThinkingBudgetValue = ThinkingBudgetWire.decode(raw)

// ── Title-generation helper ────────────────────────────────────────────────────

/**
 * Mirrors [TitleGenerationManager.handleTitleGeneration]:
 * Generates a title (with [generator], the user's [TitleGenerator]) when the chat has exactly
 * 1 or 3 assistant messages (depending on [TitleGenerationSettings.updateOnExtension]).
 * Returns whether the chat's name was changed.
 */
internal suspend fun maybeGenerateTitle(
    repo: DataRepository,
    username: String,
    chatId: String,
    settings: TitleGenerationSettings,
    generator: TitleGenerator
): Boolean {
    if (!settings.enabled) return false

    val chat = repo.loadChatHistory(username).chat_history.find { it.chat_id == chatId } ?: return false
    val assistantCount = chat.messages.count { it.role == "assistant" }

    val shouldGenerate = when {
        assistantCount == 1 -> true
        assistantCount == 3 && settings.updateOnExtension -> true
        else -> false
    }
    if (!shouldGenerate) return false

    try {
        val providerToUse = if (settings.provider == "auto") null else settings.provider
        val title = generator.generate(username, chatId, providerToUse)
        // TitleGenerationService answers "שיחה חדשה" when generation failed (Android skips it too):
        // never overwrite the chat's name with it
        if (title.isBlank() || title == "New chat" || title == "שיחה חדשה") return false
        var renamed = false
        repo.updateChatHistory(username) { history ->
            history.copy(
                chat_history = history.chat_history.map { c ->
                    if (c.chat_id == chatId && c.preview_name != title) {
                        renamed = true
                        c.copy(preview_name = title)
                    } else c
                }
            )
        }
        return renamed
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        // Title generation failure is non-fatal — log and continue.
        println("[SendRoute] Title generation failed: ${e.message}")
        return false
    }
}

// ── Routes ─────────────────────────────────────────────────────────────────────

/**
 * Registers POST /api/chat/send inside the authenticated /api route block.
 *
 * Persists the incoming user message, starts a run in the [com.example.ApI.server.runs.RunRegistry]
 * and relays its events as the response's SSE stream ([startAndStreamRun]): a first `run` event
 * with the run id, then the run's events.  The run does not depend on this response: closing it
 * does not stop the run (POST /api/runs/{runId}/cancel does), and its replies are saved at the
 * request's anchor — the variant of the user message it answers, after the last message saved
 * so far, never the chat's current path at save time.
 */
fun Route.sendRoute() {
    post("/chat/send") {
        val body = try {
            call.receive<SendRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }

        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username

        val provider = repo.loadProviders().find { it.provider == body.provider }
        if (provider == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown provider: ${body.provider}"))
            return@post
        }

        val userMessage = body.messages.lastOrNull { it.role == "user" }
        val savedChat: Chat? = if (userMessage != null && body.persistUserMessage) {
            repo.addUserMessageAsNewNode(username, body.chatId, userMessage)
        } else {
            repo.loadChatHistory(username).chat_history.find { it.chat_id == body.chatId }
        }
        if (savedChat == null) {
            // Deleted (e.g. on another device) before the send: nothing to save the reply to —
            // say so instead of streaming a reply that would be dropped
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@post
        }
        // Whoever saves a message commits its files (idempotent, in the background)
        if (userMessage != null && body.persistUserMessage) ctx.attachments.commitInBackground(userMessage.attachments)

        // Pin the replies to the saved user message (multi-message mode: the chat's last message)
        val anchor = anchorAfter(savedChat, userMessage?.takeIf { body.persistUserMessage }?.id)

        // The user's own tools for this chat. As before, an enabled tool the server has no tool
        // for is simply not offered to the model (no 409 on this route)
        val tools = ctx.tools.forRun(
            body.chatId, body.enabledToolIds, messages = body.messages, projectAttachments = body.projectAttachments
        )

        // This server's files of the request's attachments (the user's own files, the cache, the
        // store). Unlike POST /api/runs there is no device to fall back to: a file that cannot
        // be had is — as before — not sent
        val files = ctx.attachmentsForRun(
            body.provider, body.chatId, savedChat.group, body.messages, body.projectAttachments, WEB_ATTACHMENTS_TIMEOUT_MS
        )?.files ?: AttachmentFiles.NONE

        call.startAndStreamRun(
            ctx,
            RunSpec(
                runId = UUID.randomUUID().toString(),
                chatId = body.chatId,
                provider = provider,
                modelName = body.modelName,
                messages = body.messages,
                systemPrompt = body.systemPrompt,
                webSearchEnabled = body.webSearchEnabled,
                enabledToolIds = body.enabledToolIds,
                tools = tools,
                thinkingBudget = parseBudget(body.thinkingBudget),
                temperature = body.temperature,
                projectAttachments = body.projectAttachments,
                anchor = anchor,
                attachmentFiles = files,
                groupId = savedChat.group
            )
        )
    }
}

/**
 * The anchor of a request whose last saved message is [messageId] in [chat] (null: the chat's
 * last path message): the chat's path up to and including that message.
 */
internal fun anchorAfter(chat: Chat?, messageId: String?): ReplyAnchor {
    val path = chat?.messages ?: return ReplyAnchor(null, null)
    val end = messageId?.let { id -> path.indexOfLast { it.id == id } } ?: -1
    return ReplyAnchor.forRequest(if (end >= 0) path.take(end + 1) else path)
}

/**
 * Registers POST /api/chats/{chatId}/messages/{messageId}/resend inside the
 * authenticated /api route block.
 *
 * Mirrors Android's resend (MessageEditingManager.resendFromMessage): nothing is deleted —
 * a new variant is created in the node of the message (a branch next to the original, so
 * both answers stay reachable with the branch arrows and every device merges it by id) and
 * the reply is streamed into it.  [messageId] may be:
 *  - a user message id → a new variant with a copy of that user message (resend);
 *  - a response id (assistant / tool message) → a new variant with a copy of the user message
 *    of the variant holding it (regenerate);
 *  - a variant id → that variant, when it has no reply yet (web edit: POST …/branch created it
 *    with the edited message), else a new variant with a copy of its user message.
 */
fun Route.resendRoute() {
    post("/chats/{chatId}/messages/{messageId}/resend") {
        val chatId = call.parameters["chatId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing chatId")
        )
        val messageId = call.parameters["messageId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Missing messageId")
        )
        val body = try {
            call.receive<ResendRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }

        val ctx = call.userContext()
        val repo = ctx.repository
        val username = ctx.username

        // Verify the chat exists (a legacy chat is migrated, deterministically, to the tree)
        if (repo.loadChatHistory(username).chat_history.none { it.chat_id == chatId }) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@post
        }
        val chat = repo.ensureBranchingStructure(username, chatId)
        if (chat == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Chat not found"))
            return@post
        }

        // Find the message (or variant) to resend
        val target = findResendTarget(chat, messageId)
        if (target == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Message not found"))
            return@post
        }

        // Resolve provider
        val provider = repo.loadProviders().find { it.provider == body.provider }
        if (provider == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown provider: ${body.provider}"))
            return@post
        }

        // The variant the reply goes to, made the chat's current path
        val prepared: Pair<Chat, String>? = when (target) {
            is ResendTarget.Existing -> {
                val switched = if (target.variantId in chat.currentVariantPath) chat
                else repo.switchVariant(username, chatId, target.nodeId, target.variantIndex)
                switched?.let { it to target.variantId }
            }
            is ResendTarget.NewBranch -> repo.createBranch(
                username, chatId, target.nodeId,
                // Same message (id included, like Android's resend), new time
                target.userMessage.copy(datetime = Instant.now().toString())
            )
        }
        if (prepared == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Message not found"))
            return@post
        }
        val (updatedChat, variantId) = prepared

        // The request's messages: the path up to (and including) the variant's user message
        val end = updatedChat.messages.indexOfFirst { it.variantId == variantId }
        if (end < 0) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "Branch is not on the current path"))
            return@post
        }
        val requestMessages = updatedChat.messages.take(end + 1)
        // The branch's user message is saved (again): its files are in use
        requestMessages.lastOrNull()?.let { ctx.attachments.commitInBackground(it.attachments) }
        val files = ctx.attachmentsForRun(
            body.provider, chatId, updatedChat.group, requestMessages, emptyList(), WEB_ATTACHMENTS_TIMEOUT_MS
        )?.files ?: AttachmentFiles.NONE

        call.startAndStreamRun(
            ctx,
            RunSpec(
                runId = UUID.randomUUID().toString(),
                chatId = chatId,
                provider = provider,
                modelName = body.modelName,
                messages = requestMessages,
                systemPrompt = body.systemPrompt,
                webSearchEnabled = body.webSearchEnabled,
                enabledToolIds = body.enabledToolIds,
                // The user's own tools for this chat (unavailable ones are not offered, as before)
                tools = ctx.tools.forRun(chatId, body.enabledToolIds, messages = requestMessages),
                thinkingBudget = parseBudget(body.thinkingBudget),
                temperature = body.temperature,
                projectAttachments = emptyList(),
                anchor = ReplyAnchor.forRequest(requestMessages),
                attachmentFiles = files,
                groupId = updatedChat.group
            )
        )
    }
}

/** Where a resend's reply goes (see [resendRoute]). */
internal sealed class ResendTarget {
    /** An existing reply-less variant (created by a web edit). */
    data class Existing(val nodeId: String, val variantId: String, val variantIndex: Int) : ResendTarget()

    /** A new variant in [nodeId] with a copy of [userMessage]. */
    data class NewBranch(val nodeId: String, val userMessage: Message) : ResendTarget()
}

/**
 * Resolve [id] (variant id, user message id or response id) in [chat]'s tree.  Variants on the
 * current path are preferred: sibling variants share their user message id by design.
 */
internal fun findResendTarget(chat: Chat, id: String): ResendTarget? {
    for (node in chat.messageNodes) {
        val index = node.variants.indexOfFirst { it.variantId == id }
        if (index < 0) continue
        val variant = node.variants[index]
        val childExists = variant.childNodeId != null && chat.messageNodes.any { it.nodeId == variant.childNodeId }
        return if (variant.responses.isEmpty() && !childExists) {
            ResendTarget.Existing(node.nodeId, variant.variantId, index)
        } else {
            ResendTarget.NewBranch(node.nodeId, variant.userMessage)
        }
    }
    val onPath = chat.currentVariantPath.toSet()
    val candidates = chat.messageNodes.flatMap { node -> node.variants.map { node to it } }
        .sortedByDescending { (_, v) -> v.variantId in onPath }
    for ((node, variant) in candidates) {
        if (variant.userMessage.id == id || variant.responses.any { it.id == id }) {
            return ResendTarget.NewBranch(node.nodeId, variant.userMessage)
        }
    }
    return null
}
