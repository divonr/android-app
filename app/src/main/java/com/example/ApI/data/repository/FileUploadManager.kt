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

        val updatedAttachments = mutableListOf<Attachment>()

        for (attachment in projectAttachments) {
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
                    updatedAttachments.add(mergedAttachment)
                } else {
                    // Upload failed, keep original attachment
                    updatedAttachments.add(attachment)
                }
            } else {
                // File already uploaded for this provider or no local path
                updatedAttachments.add(attachment)
            }
        }

        return updatedAttachments
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
        val updatedMessages = mutableListOf<Message>()
        var hasUpdates = false

        for (message in messages) {
            if (message.attachments.isEmpty()) {
                // No attachments, keep message as is
                updatedMessages.add(message)
                continue
            }

            val updatedAttachments = mutableListOf<Attachment>()

            for (attachment in message.attachments) {
                val needsReupload = when (provider.provider) {
                    "openai" -> attachment.file_OPENAI_id == null
                    "poe" -> attachment.file_POE_url == null
                    "google" -> attachment.file_GOOGLE_uri == null
                    else -> false
                }

                if (needsReupload && attachment.local_file_path != null) {
                    // Need to re-upload file for current provider
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
                        updatedAttachments.add(mergedAttachment)
                        hasUpdates = true
                    } else {
                        // Upload failed, keep original attachment
                        updatedAttachments.add(attachment)
                    }
                } else {
                    // Already has correct ID for current provider, or no local file
                    updatedAttachments.add(attachment)
                }
            }

            updatedMessages.add(message.copy(attachments = updatedAttachments))
        }

        return Pair(
            if (hasUpdates) updatedMessages else messages,
            hasUpdates
        )
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
