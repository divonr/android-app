@file:OptIn(ExperimentalEncodingApi::class)
package com.example.ApI.data.network

import com.example.ApI.data.model.GmailMessage
import com.example.ApI.util.AppLogger
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.Message
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Service for interacting with Gmail API
 */
class GmailApiService(
    private val accessToken: String,
    private val userEmail: String
) {
    companion object {
        private const val TAG = "GmailApiService"
        private const val APPLICATION_NAME = "ApI"
    }

    private val httpTransport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    /**
     * Create HTTP request initializer with OAuth2 access token
     */
    private fun createRequestInitializer(): HttpRequestInitializer {
        return HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $accessToken"
        }
    }

    /**
     * Get Gmail service instance
     */
    private fun getGmailService(): Gmail {
        return Gmail.Builder(httpTransport, jsonFactory, createRequestInitializer())
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    /**
     * Get an email by message ID
     */
    suspend fun getEmail(messageId: String): Result<GmailMessage> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d("[$TAG] Fetching email: $messageId")

            val service = getGmailService()
            val message = service.users().messages().get("me", messageId)
                .setFormat("full")
                .execute()

            val gmailMessage = parseMessage(message)
            AppLogger.d("[$TAG] Email fetched successfully: ${gmailMessage.subject}")
            Result.success(gmailMessage)
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Error fetching email", e)
            Result.failure(e)
        }
    }

    /**
     * Send an email
     */
    suspend fun sendEmail(to: String, subject: String, body: String): Result<GmailMessage> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d("[$TAG] Sending email to: $to")

            val rawEmail = buildString {
                append("To: $to\r\n")
                append("From: $userEmail\r\n")
                append("Subject: $subject\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("\r\n")
                append(body)
            }

            // Base64 URL-safe encode the message
            val encodedEmail = Base64.UrlSafe.encode(rawEmail.toByteArray(Charsets.UTF_8))

            val message = Message()
            message.raw = encodedEmail

            val service = getGmailService()
            val sentMessage = service.users().messages().send("me", message).execute()

            AppLogger.d("[$TAG] Email sent successfully: ${sentMessage.id}")

            val fullMessage = try {
                getEmail(sentMessage.id).getOrNull()
            } catch (e: Exception) {
                null
            }

            if (fullMessage != null) {
                Result.success(fullMessage)
            } else {
                Result.success(GmailMessage(
                    id = sentMessage.id,
                    threadId = sentMessage.threadId,
                    subject = subject,
                    from = userEmail,
                    to = listOf(to),
                    date = null,
                    snippet = body.take(100),
                    body = body,
                    isRead = true,
                    labels = listOf("SENT")
                ))
            }
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Error sending email", e)
            Result.failure(e)
        }
    }

    /**
     * Search emails with a query
     */
    suspend fun searchEmails(query: String, maxResults: Int = 20): Result<List<GmailMessage>> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d("[$TAG] Searching emails with query: $query")

            val service = getGmailService()
            val response = service.users().messages().list("me")
                .setQ(query)
                .setMaxResults(maxResults.toLong())
                .execute()

            val messages = response.messages ?: emptyList()
            AppLogger.d("[$TAG] Found ${messages.size} messages")

            val gmailMessages = messages.mapNotNull { messageRef ->
                try {
                    val fullMessage = service.users().messages().get("me", messageRef.id)
                        .setFormat("full")
                        .execute()
                    parseMessage(fullMessage)
                } catch (e: Exception) {
                    AppLogger.e("[$TAG] Error fetching message ${messageRef.id}", e)
                    null
                }
            }

            Result.success(gmailMessages)
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Error searching emails", e)
            Result.failure(e)
        }
    }

    /**
     * List emails with optional label filter
     */
    suspend fun listEmails(labelIds: List<String> = listOf("INBOX"), maxResults: Int = 20): Result<List<GmailMessage>> = withContext(Dispatchers.IO) {
        try {
            AppLogger.d("[$TAG] Listing emails with labels: $labelIds")

            val service = getGmailService()
            val response = service.users().messages().list("me")
                .setLabelIds(labelIds)
                .setMaxResults(maxResults.toLong())
                .execute()

            val messages = response.messages ?: emptyList()
            AppLogger.d("[$TAG] Found ${messages.size} messages")

            val gmailMessages = messages.mapNotNull { messageRef ->
                try {
                    val fullMessage = service.users().messages().get("me", messageRef.id)
                        .setFormat("full")
                        .execute()
                    parseMessage(fullMessage)
                } catch (e: Exception) {
                    AppLogger.e("[$TAG] Error fetching message ${messageRef.id}", e)
                    null
                }
            }

            Result.success(gmailMessages)
        } catch (e: Exception) {
            AppLogger.e("[$TAG] Error listing emails", e)
            Result.failure(e)
        }
    }

    /**
     * Parse Gmail API Message to GmailMessage model
     */
    private fun parseMessage(message: Message): GmailMessage {
        val headers = message.payload?.headers ?: emptyList()

        val subject = headers.find { it.name.equals("Subject", ignoreCase = true) }?.value
        val from = headers.find { it.name.equals("From", ignoreCase = true) }?.value
        val toHeader = headers.find { it.name.equals("To", ignoreCase = true) }?.value
        val date = headers.find { it.name.equals("Date", ignoreCase = true) }?.value

        val toList = toHeader?.split(",")?.map { it.trim() } ?: emptyList()
        val body = extractBody(message)
        val isRead = message.labelIds?.contains("UNREAD")?.not() ?: true

        return GmailMessage(
            id = message.id,
            threadId = message.threadId,
            subject = subject,
            from = from,
            to = toList,
            date = date,
            snippet = message.snippet,
            body = body,
            isRead = isRead,
            labels = message.labelIds ?: emptyList()
        )
    }

    /**
     * Extract body from Gmail message
     */
    private fun extractBody(message: Message): String? {
        val payload = message.payload ?: return null

        payload.body?.data?.let { data ->
            return String(Base64.UrlSafe.decode(data))
        }

        payload.parts?.forEach { part ->
            if (part.mimeType == "text/plain" || part.mimeType == "text/html") {
                part.body?.data?.let { data ->
                    return String(Base64.UrlSafe.decode(data))
                }
            }

            part.parts?.forEach { nestedPart ->
                if (nestedPart.mimeType == "text/plain" || nestedPart.mimeType == "text/html") {
                    nestedPart.body?.data?.let { data ->
                        return String(Base64.UrlSafe.decode(data))
                    }
                }
            }
        }

        return null
    }
}
