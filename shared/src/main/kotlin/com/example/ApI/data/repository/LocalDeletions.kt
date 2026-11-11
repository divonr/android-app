package com.example.ApI.data.repository

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Chats and message variants deleted on this device by its user, per chat history file
 * (process lifetime, shared by every repository over the same data dir — on Android the UI's and
 * the StreamingService's).
 *
 * A streamed reply whose chat or question vanished is restored with its question
 * ([MessageBranchingManager.addAnchoredResponse]) when another device deleted them (a sync merge):
 * the answer is a change made after that deletion and must not be lost. A deletion this device's
 * user made while the reply was streaming is respected instead: the reply is dropped.
 */
internal object LocalDeletions {
    private val chats = ConcurrentHashMap<String, MutableSet<String>>()
    private val variants = ConcurrentHashMap<String, MutableSet<String>>()

    fun recordChat(chatHistoryFile: File, chatId: String) {
        chats.computeIfAbsent(key(chatHistoryFile)) { ConcurrentHashMap.newKeySet() } += chatId
    }

    fun recordVariant(chatHistoryFile: File, variantId: String) {
        variants.computeIfAbsent(key(chatHistoryFile)) { ConcurrentHashMap.newKeySet() } += variantId
    }

    fun isChatDeleted(chatHistoryFile: File, chatId: String): Boolean =
        chats[key(chatHistoryFile)]?.contains(chatId) == true

    fun isVariantDeleted(chatHistoryFile: File, variantId: String): Boolean =
        variants[key(chatHistoryFile)]?.contains(variantId) == true

    private fun key(file: File): String = try {
        file.canonicalPath
    } catch (e: IOException) {
        file.absoluteFile.normalize().path
    }
}
