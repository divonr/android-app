package com.example.ApI.data.repository

import com.example.ApI.data.model.ApiKey
import com.example.ApI.data.model.Attachment
import com.example.ApI.data.model.Message
import com.example.ApI.data.model.Provider
import com.example.ApI.data.model.UploadRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages file uploads to different LLM providers.
 * Handles provider-specific upload logic for OpenAI, Poe, and Google.
 */
class FileUploadManager(
    private val json: Json,
    private val loadApiKeys: (String) -> List<ApiKey>
) {
    /**
     * Upload a file to the specified provider.
     */
    suspend fun uploadFile(
        provider: Provider,
        filePath: String,
        fileName: String,
        mimeType: String,
        username: String
    ): Attachment? {
        val apiKeys = loadApiKeys(username)
            .filter { it.isActive }
            .associate { it.provider to it.key }
        val apiKey = apiKeys[provider.provider] ?: return null
        val uploadRequest = provider.upload_files_request ?: return null

        return try {
            when (provider.provider) {
                "openai" -> uploadFileToOpenAI(uploadRequest, filePath, fileName, mimeType, apiKey)
                "poe" -> uploadFileToPoe(uploadRequest, filePath, fileName, mimeType, apiKey)
                "google" -> uploadFileToGoogle(uploadRequest, filePath, fileName, mimeType, apiKey)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check and upload project files for current provider.
     */
    suspend fun ensureProjectFilesUploadedForProvider(
        provider: Provider,
        projectAttachments: List<Attachment>,
        username: String
    ): List<Attachment> {
        if (projectAttachments.isEmpty()) return emptyList()
        return projectAttachments.map { attachment ->
            ensureAttachmentUploadedForProvider(provider, attachment, username).first
        }
    }

    /**
     * Check and re-upload files if provider has changed.
     * Returns a Pair of (updated messages, hasUpdates).
     */
    suspend fun ensureFilesUploadedForProvider(
        provider: Provider,
        messages: List<Message>,
        username: String
    ): Pair<List<Message>, Boolean> {
        var hasUpdates = false
        val updatedMessages = messages.map { message ->
            if (message.attachments.isEmpty()) {
                message
            } else {
                val updatedAttachments = message.attachments.map { attachment ->
                    val (updatedAttachment, wasUpdated) = ensureAttachmentUploadedForProvider(provider, attachment, username)
                    if (wasUpdated) hasUpdates = true
                    updatedAttachment
                }
                message.copy(attachments = updatedAttachments)
            }
        }
        return Pair(updatedMessages, hasUpdates)
    }

    /**
     * Check if an attachment needs upload for the given provider and upload if necessary.
     * Returns the updated attachment (with provider-specific IDs) or the original if no upload needed/failed,
     * along with a boolean indicating if the attachment was updated.
     */
    private suspend fun ensureAttachmentUploadedForProvider(
        provider: Provider,
        attachment: Attachment,
        username: String
    ): Pair<Attachment, Boolean> {
        val needsUpload = when (provider.provider) {
            "openai" -> attachment.file_OPENAI_id == null
            "poe" -> attachment.file_POE_url == null
            "google" -> attachment.file_GOOGLE_uri == null
            else -> false
        }

        if (needsUpload && attachment.local_file_path != null) {
            // Need to upload file for current provider
            val uploadedAttachment = uploadFile(
                provider = provider,
                filePath = attachment.local_file_path,
                fileName = attachment.file_name,
                mimeType = attachment.mime_type,
                username = username
            )

            if (uploadedAttachment != null) {
                // Merge the new ID with existing attachment data
                val mergedAttachment = when (provider.provider) {
                    "openai" -> attachment.copy(file_OPENAI_id = uploadedAttachment.file_OPENAI_id)
                    "poe" -> attachment.copy(file_POE_url = uploadedAttachment.file_POE_url)
                    "google" -> attachment.copy(file_GOOGLE_uri = uploadedAttachment.file_GOOGLE_uri)
                    else -> attachment
                }
                return Pair(mergedAttachment, true)
            } else {
                // Upload failed, keep original attachment
                return Pair(attachment, false)
            }
        } else {
            // File already uploaded for this provider or no local path
            return Pair(attachment, false)
        }
    }

    // ============ Provider-Specific Upload Implementations ============

    private suspend fun uploadFileToOpenAI(
        uploadRequest: UploadRequest,
        filePath: String,
        fileName: String,
        mimeType: String,
        apiKey: String
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null

            val url = URL(uploadRequest.base_url)
            val connection = url.openConnection() as HttpURLConnection

            val boundary = "----FormBoundary${System.currentTimeMillis()}"

            // Set up the connection
            connection.requestMethod = uploadRequest.request_type
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true

            val outputStream = connection.outputStream

            // Write multipart form data
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n".toByteArray())
            outputStream.write("assistants\r\n".toByteArray())

            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
            outputStream.write("Content-Type: $mimeType\r\n\r\n".toByteArray())

            // Write file content
            file.inputStream().use { input ->
                input.copyTo(outputStream)
            }

            outputStream.write("\r\n--$boundary--\r\n".toByteArray())
            outputStream.close()

            // Read response
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }

            val response = reader.readText()
            reader.close()
            connection.disconnect()

            if (responseCode >= 400) {
                return@withContext null
            }

            // Parse response to get file ID
            val responseJson = json.parseToJsonElement(response).jsonObject
            val fileId = responseJson["id"]?.jsonPrimitive?.content

            if (fileId != null) {
                Attachment(
                    local_file_path = filePath,
                    file_name = fileName,
                    mime_type = mimeType,
                    file_OPENAI_id = fileId
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun uploadFileToPoe(
        uploadRequest: UploadRequest,
        filePath: String,
        fileName: String,
        mimeType: String,
        apiKey: String
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null

            val url = URL(uploadRequest.base_url)
            val connection = url.openConnection() as HttpURLConnection

            val boundary = "----FormBoundary${System.currentTimeMillis()}"

            // Set up the connection
            connection.requestMethod = uploadRequest.request_type
            connection.setRequestProperty("Authorization", apiKey)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true

            val outputStream = connection.outputStream

            // Write multipart form data
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
            outputStream.write("Content-Type: $mimeType\r\n\r\n".toByteArray())

            // Write file content
            file.inputStream().use { input ->
                input.copyTo(outputStream)
            }

            outputStream.write("\r\n--$boundary--\r\n".toByteArray())
            outputStream.close()

            // Read response
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }

            val response = reader.readText()
            reader.close()
            connection.disconnect()

            if (responseCode >= 400) {
                return@withContext null
            }

            // Parse response to get file ID and URL
            val responseJson = json.parseToJsonElement(response).jsonObject
            val fileId = responseJson["file_id"]?.jsonPrimitive?.content
            val attachmentUrl = responseJson["attachment_url"]?.jsonPrimitive?.content

            if (fileId != null && attachmentUrl != null) {
                Attachment(
                    local_file_path = filePath,
                    file_name = fileName,
                    mime_type = mimeType,
                    file_POE_url = attachmentUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun uploadFileToGoogle(
        uploadRequest: UploadRequest,
        filePath: String,
        fileName: String,
        mimeType: String,
        apiKey: String
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (!file.exists()) return@withContext null

            val baseUrl = uploadRequest.base_url
            val url = URL("$baseUrl?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection

            // Set up the connection
            connection.requestMethod = uploadRequest.request_type
            connection.setRequestProperty("Content-Type", mimeType)
            connection.doOutput = true

            // Write file content directly (binary upload)
            val outputStream = connection.outputStream
            file.inputStream().use { input ->
                input.copyTo(outputStream)
            }
            outputStream.close()

            // Read response
            val responseCode = connection.responseCode
            val reader = if (responseCode >= 400) {
                BufferedReader(InputStreamReader(connection.errorStream))
            } else {
                BufferedReader(InputStreamReader(connection.inputStream))
            }

            val response = reader.readText()
            reader.close()
            connection.disconnect()

            if (responseCode >= 400) {
                return@withContext null
            }

            // Parse response to get file URI
            val responseJson = json.parseToJsonElement(response).jsonObject
            val fileObj = responseJson["file"]?.jsonObject
            val fileUri = fileObj?.get("uri")?.jsonPrimitive?.content

            if (fileUri != null) {
                Attachment(
                    local_file_path = filePath,
                    file_name = fileName,
                    mime_type = mimeType,
                    file_GOOGLE_uri = fileUri
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
