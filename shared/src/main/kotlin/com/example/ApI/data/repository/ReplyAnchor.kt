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
 */
class ReplyAnchor(variantId: String?, tailId: String?) {
    private var variantId: String? = variantId
    private var tailId: String? = tailId

    /** (variant id, id of the message the next response follows); nulls: unknown. */
    @Synchronized
    fun current(): Pair<String?, String?> = variantId to tailId

    @Synchronized
    fun moveTo(variantId: String?, tailId: String?) {
        this.variantId = variantId
        this.tailId = tailId
    }

    companion object {
        /** The anchor of a request that sends [messages] (the chat's path messages). */
        fun forRequest(messages: List<Message>): ReplyAnchor {
            val last = messages.lastOrNull()
            return ReplyAnchor(last?.variantId, last?.id)
        }
    }
}
