package com.example.ApI.data.repository

import com.example.ApI.data.model.Message

/**
 * Where the responses of one streamed request are saved: the variant they belong to and the
 * message the next response follows (see [MessageBranchingManager.addResponseToCurrentVariant]'s
 * `targetVariantId` / `expectedTailId`).
 *
 * Created when the request starts, from the last message sent (the user message, or the last
 * response of a continued turn: path messages carry their `variantId`), and moved by
 * [MessageBranchingManager.addAnchoredResponse] after every save. This pins the reply to the
 * variant it answers even if the current path moved meanwhile (variant switch, sync merge), and
 * keeps it after this device's own content if a merge forked that content away.
 *
 * It also keeps the conversation the reply follows ([path]: the path messages sent, plus every
 * response saved since), so a reply whose question or chat another device deleted meanwhile is
 * saved with its question restored instead of being lost or attached elsewhere.
 */
class ReplyAnchor(variantId: String?, tailId: String?, requestMessages: List<Message> = emptyList()) {
    private var variantId: String? = variantId
    private var tailId: String? = tailId
    private var path: List<Message> = requestMessages

    /** (variant id, id of the message the next response follows); nulls: unknown. */
    @Synchronized
    fun current(): Pair<String?, String?> = variantId to tailId

    /** The path messages the next response follows (empty: unknown). */
    @Synchronized
    fun path(): List<Message> = path

    /** Move the anchor after [saved] (a response saved in [variantId]). */
    @Synchronized
    fun moveTo(variantId: String?, tailId: String?, saved: Message? = null) {
        this.variantId = variantId
        this.tailId = tailId
        if (saved != null) path = path + saved
    }

    companion object {
        /** The anchor of a request that sends [messages] (the chat's path messages). */
        fun forRequest(messages: List<Message>): ReplyAnchor {
            val last = messages.lastOrNull()
            return ReplyAnchor(last?.variantId, last?.id, messages)
        }
    }
}
