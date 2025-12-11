package com.example.ApI.data.network

import android.util.Log
import com.example.ApI.data.model.DriveFile
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Service for interacting with Google Drive API
 */
class GoogleDriveApiService(
    private val accessToken: String,
    private val userEmail: String
) {
    companion object {
        private const val TAG = "GoogleDriveService"
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
     * Get Drive service instance
     */
    private fun getDriveService(): Drive {
        return Drive.Builder(httpTransport, jsonFactory, createRequestInitializer())
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    /**
     * List files in a folder
     * @param folderId Folder ID (null for root)
     * @param query Additional query filter
     * @param maxResults Maximum number of results
     * @return Result containing list of DriveFiles or error
     */
    suspend fun listFiles(
        folderId: String? = null,
        query: String? = null,
        maxResults: Int = 20
    ): Result<List<DriveFile>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Listing files in folder: ${folderId ?: "root"}")

            val service = getDriveService()

            // Build query
            val queryString = buildString {
                if (folderId != null) {
                    append("'$folderId' in parents")
                }
                if (query != null) {
                    if (isNotEmpty()) append(" and ")
                    append(query)
                }
                if (isEmpty()) {
                    append("trashed = false")
                } else {
                    append(" and trashed = false")
                }
            }

            val request = service.files().list()
                .setQ(queryString)
                .setPageSize(maxResults)
                .setFields("files(id, name, mimeType, size, createdTime, modifiedTime, webViewLink, webContentLink, iconLink, thumbnailLink, parents, description, starred, trashed)")

            val result = request.execute()
            val files = result.files ?: emptyList()

            Log.d(TAG, "Found ${files.size} files")

            val driveFiles = files.map { parseFile(it) }
            Result.success(driveFiles)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files", e)
            Result.failure(e)
        }
    }

    /**
     * Get file metadata by ID
     * @param fileId File ID
     * @return Result containing DriveFile or error
     */
    suspend fun getFile(fileId: String): Result<DriveFile> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching file: $fileId")

            val service = getDriveService()
            val file = service.files().get(fileId)
                .setFields("id, name, mimeType, size, createdTime, modifiedTime, webViewLink, webContentLink, iconLink, thumbnailLink, parents, description, starred, trashed")
                .execute()

            val driveFile = parseFile(file)
            Log.d(TAG, "File fetched successfully: ${driveFile.name}")
            Result.success(driveFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching file", e)
            Result.failure(e)
        }
    }

    /**
     * Read file content as string
     * @param fileId File ID
     * @return Result containing file content as string or error
     */
    suspend fun readFileContent(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Reading file content: $fileId")

            val service = getDriveService()
            val outputStream = ByteArrayOutputStream()
            service.files().get(fileId).executeMediaAndDownloadTo(outputStream)

            val content = outputStream.toString("UTF-8")
            Log.d(TAG, "File content read successfully (${content.length} chars)")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file content", e)
            Result.failure(e)
        }
    }

    /**
     * Upload a new file
     * @param name File name
     * @param mimeType MIME type
     * @param content File content as bytes
     * @param parentId Parent folder ID (null for root)
     * @return Result containing created DriveFile or error
     */
    suspend fun uploadFile(
        name: String,
        mimeType: String,
        content: ByteArray,
        parentId: String? = null
    ): Result<DriveFile> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Uploading file: $name")

            val service = getDriveService()

            val fileMetadata = File()
            fileMetadata.name = name
            if (parentId != null) {
                fileMetadata.parents = listOf(parentId)
            }

            val mediaContent = ByteArrayContent(mimeType, content)

            val file = service.files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType, size, createdTime, modifiedTime, webViewLink, webContentLink, iconLink, parents, description")
                .execute()

            Log.d(TAG, "File uploaded successfully: ${file.id}")
            val driveFile = parseFile(file)
            Result.success(driveFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new folder
     * @param name Folder name
     * @param parentId Parent folder ID (null for root)
     * @return Result containing created DriveFile or error
     */
    suspend fun createFolder(
        name: String,
        parentId: String? = null
    ): Result<DriveFile> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Creating folder: $name")

            val service = getDriveService()

            val fileMetadata = File()
            fileMetadata.name = name
            fileMetadata.mimeType = "application/vnd.google-apps.folder"
            if (parentId != null) {
                fileMetadata.parents = listOf(parentId)
            }

            val file = service.files().create(fileMetadata)
                .setFields("id, name, mimeType, createdTime, modifiedTime, webViewLink, parents")
                .execute()

            Log.d(TAG, "Folder created successfully: ${file.id}")
            val driveFile = parseFile(file)
            Result.success(driveFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating folder", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a file or folder
     * @param fileId File ID
     * @return Result success or error
     */
    suspend fun deleteFile(fileId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Deleting file: $fileId")

            val service = getDriveService()
            service.files().delete(fileId).execute()

            Log.d(TAG, "File deleted successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file", e)
            Result.failure(e)
        }
    }

    /**
     * Search files by name or content
     * @param query Search query
     * @param maxResults Maximum number of results
     * @return Result containing list of DriveFiles or error
     */
    suspend fun searchFiles(
        query: String,
        maxResults: Int = 20
    ): Result<List<DriveFile>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Searching files with query: $query")

            val service = getDriveService()

            // Build search query - search in name and full text
            val searchQuery = "name contains '$query' or fullText contains '$query'"

            val request = service.files().list()
                .setQ("$searchQuery and trashed = false")
                .setPageSize(maxResults)
                .setFields("files(id, name, mimeType, size, createdTime, modifiedTime, webViewLink, webContentLink, iconLink, thumbnailLink, parents, description, starred)")

            val result = request.execute()
            val files = result.files ?: emptyList()

            Log.d(TAG, "Found ${files.size} files matching query")

            val driveFiles = files.map { parseFile(it) }
            Result.success(driveFiles)
        } catch (e: Exception) {
            Log.e(TAG, "Error searching files", e)
            Result.failure(e)
        }
    }

    /**
     * Parse Google Drive API File to DriveFile model
     */
    private fun parseFile(file: File): DriveFile {
        return DriveFile(
            id = file.id,
            name = file.name ?: "",
            mimeType = file.mimeType ?: "",
            size = file.getSize(),
            createdTime = file.createdTime?.toString(),
            modifiedTime = file.modifiedTime?.toString(),
            webViewLink = file.webViewLink,
            webContentLink = file.webContentLink,
            iconLink = file.iconLink,
            thumbnailLink = file.thumbnailLink,
            parents = file.parents ?: emptyList(),
            isFolder = file.mimeType == "application/vnd.google-apps.folder",
            description = file.description,
            starred = file.starred ?: false,
            trashed = file.trashed ?: false
        )
    }
}
