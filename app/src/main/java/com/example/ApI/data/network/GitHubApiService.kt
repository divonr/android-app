package com.example.ApI.data.network

import android.util.Log
import com.example.ApI.data.model.*
import com.example.ApI.util.JsonConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Service for making GitHub API calls
 * Uses HttpURLConnection to be consistent with existing ApiService
 */
class GitHubApiService {

    companion object {
        private const val TAG = "GitHubApiService"
        private const val BASE_URL = "https://api.github.com"
        private const val GITHUB_API_VERSION = "2022-11-28"
    }

    /**
     * Get authenticated user information
     */
    suspend fun getAuthenticatedUser(accessToken: String): Result<GitHubUser> = withContext(Dispatchers.IO) {
        return@withContext makeGetRequest(
            endpoint = "/user",
            accessToken = accessToken,
            responseClass = GitHubUser::class
        )
    }

    /**
     * List repositories accessible to the authenticated user
     */
    suspend fun listRepositories(
        accessToken: String,
        visibility: String = "all", // "all", "public", "private"
        sort: String = "updated", // "created", "updated", "pushed", "full_name"
        perPage: Int = 30,
        page: Int = 1
    ): Result<List<GitHubRepository>> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "visibility" to visibility,
            "sort" to sort,
            "per_page" to perPage.toString(),
            "page" to page.toString()
        )
        return@withContext makeGetRequestList<GitHubRepository>(
            endpoint = "/user/repos",
            accessToken = accessToken,
            queryParams = params
        )
    }

    /**
     * Get repository information
     */
    suspend fun getRepository(
        accessToken: String,
        owner: String,
        repo: String
    ): Result<GitHubRepository> = withContext(Dispatchers.IO) {
        return@withContext makeGetRequest(
            endpoint = "/repos/$owner/$repo",
            accessToken = accessToken,
            responseClass = GitHubRepository::class
        )
    }

    /**
     * Get file or directory contents from a repository
     */
    suspend fun getContents(
        accessToken: String,
        owner: String,
        repo: String,
        path: String,
        ref: String? = null // Branch, tag, or commit SHA
    ): Result<Any> = withContext(Dispatchers.IO) {
        val params = ref?.let { mapOf("ref" to it) } ?: emptyMap()

        try {
            val url = buildUrl("/repos/$owner/$repo/contents/$path", params)
            val connection = createConnection(url, "GET", accessToken)

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "getContents response: $responseCode")

            if (responseCode in 200..299) {
                // Response can be a single file or array of files
                return@withContext try {
                    // Try parsing as single file first
                    val content = JsonConfig.standard.decodeFromString<GitHubContent>(responseBody)
                    Result.success(content)
                } catch (e: Exception) {
                    // Try parsing as array of files (directory)
                    try {
                        val contents = JsonConfig.standard.decodeFromString<List<GitHubContent>>(responseBody)
                        Result.success(contents)
                    } catch (e2: Exception) {
                        Result.failure(Exception("Failed to parse response: ${e2.message}"))
                    }
                }
            } else {
                val error = try {
                    JsonConfig.standard.decodeFromString<GitHubError>(responseBody)
                } catch (e: Exception) {
                    GitHubError(message = "HTTP $responseCode: $responseBody")
                }
                Result.failure(Exception(error.message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getContents error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Create or update a file in a repository
     */
    suspend fun createOrUpdateFile(
        accessToken: String,
        owner: String,
        repo: String,
        path: String,
        request: GitHubCreateUpdateFileRequest
    ): Result<GitHubCreateUpdateFileResponse> = withContext(Dispatchers.IO) {
        return@withContext makePutRequest(
            endpoint = "/repos/$owner/$repo/contents/$path",
            accessToken = accessToken,
            body = request,
            responseClass = GitHubCreateUpdateFileResponse::class
        )
    }

    /**
     * Delete a file from a repository
     */
    suspend fun deleteFile(
        accessToken: String,
        owner: String,
        repo: String,
        path: String,
        message: String,
        sha: String,
        branch: String? = null
    ): Result<GitHubCreateUpdateFileResponse> = withContext(Dispatchers.IO) {
        val requestBody = buildString {
            append("{")
            append("\"message\":\"${escapeJson(message)}\",")
            append("\"sha\":\"$sha\"")
            if (branch != null) {
                append(",\"branch\":\"$branch\"")
            }
            append("}")
        }

        return@withContext makeDeleteRequest(
            endpoint = "/repos/$owner/$repo/contents/$path",
            accessToken = accessToken,
            body = requestBody,
            responseClass = GitHubCreateUpdateFileResponse::class
        )
    }

    /**
     * List branches in a repository
     */
    suspend fun listBranches(
        accessToken: String,
        owner: String,
        repo: String
    ): Result<List<GitHubBranch>> = withContext(Dispatchers.IO) {
        return@withContext makeGetRequestList<GitHubBranch>(
            endpoint = "/repos/$owner/$repo/branches",
            accessToken = accessToken
        )
    }

    /**
     * Get a specific branch
     */
    suspend fun getBranch(
        accessToken: String,
        owner: String,
        repo: String,
        branch: String
    ): Result<GitHubBranch> = withContext(Dispatchers.IO) {
        return@withContext makeGetRequest(
            endpoint = "/repos/$owner/$repo/branches/$branch",
            accessToken = accessToken,
            responseClass = GitHubBranch::class
        )
    }

    /**
     * Create a new branch (reference)
     */
    suspend fun createBranch(
        accessToken: String,
        owner: String,
        repo: String,
        branchName: String,
        fromSha: String
    ): Result<GitHubReference> = withContext(Dispatchers.IO) {
        val request = GitHubCreateReferenceRequest(
            ref = "refs/heads/$branchName",
            sha = fromSha
        )
        return@withContext makePostRequest(
            endpoint = "/repos/$owner/$repo/git/refs",
            accessToken = accessToken,
            body = request,
            responseClass = GitHubReference::class
        )
    }

    /**
     * List commits in a repository
     */
    suspend fun listCommits(
        accessToken: String,
        owner: String,
        repo: String,
        sha: String? = null, // Branch or commit SHA
        path: String? = null,
        perPage: Int = 30,
        page: Int = 1
    ): Result<List<GitHubCommit>> = withContext(Dispatchers.IO) {
        val params = mutableMapOf(
            "per_page" to perPage.toString(),
            "page" to page.toString()
        )
        sha?.let { params["sha"] = it }
        path?.let { params["path"] = it }

        return@withContext makeGetRequestList<GitHubCommit>(
            endpoint = "/repos/$owner/$repo/commits",
            accessToken = accessToken,
            queryParams = params
        )
    }

    /**
     * Get a specific commit
     */
    suspend fun getCommit(
        accessToken: String,
        owner: String,
        repo: String,
        ref: String
    ): Result<GitHubCommit> = withContext(Dispatchers.IO) {
        return@withContext makeGetRequest(
            endpoint = "/repos/$owner/$repo/commits/$ref",
            accessToken = accessToken,
            responseClass = GitHubCommit::class
        )
    }

    /**
     * Create a pull request
     */
    suspend fun createPullRequest(
        accessToken: String,
        owner: String,
        repo: String,
        request: GitHubCreatePullRequestRequest
    ): Result<GitHubPullRequest> = withContext(Dispatchers.IO) {
        return@withContext makePostRequest(
            endpoint = "/repos/$owner/$repo/pulls",
            accessToken = accessToken,
            body = request,
            responseClass = GitHubPullRequest::class
        )
    }

    /**
     * List pull requests
     */
    suspend fun listPullRequests(
        accessToken: String,
        owner: String,
        repo: String,
        state: String = "open", // "open", "closed", "all"
        perPage: Int = 30,
        page: Int = 1
    ): Result<List<GitHubPullRequest>> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "state" to state,
            "per_page" to perPage.toString(),
            "page" to page.toString()
        )
        return@withContext makeGetRequestList<GitHubPullRequest>(
            endpoint = "/repos/$owner/$repo/pulls",
            accessToken = accessToken,
            queryParams = params
        )
    }

    /**
     * Get a specific pull request
     */
    suspend fun getPullRequest(
        accessToken: String,
        owner: String,
        repo: String,
        pullNumber: Int
    ): Result<GitHubPullRequest> = withContext(Dispatchers.IO) {
        return@withContext makeGetRequest(
            endpoint = "/repos/$owner/$repo/pulls/$pullNumber",
            accessToken = accessToken,
            responseClass = GitHubPullRequest::class
        )
    }

    /**
     * Create an issue
     */
    suspend fun createIssue(
        accessToken: String,
        owner: String,
        repo: String,
        request: GitHubCreateIssueRequest
    ): Result<GitHubIssue> = withContext(Dispatchers.IO) {
        return@withContext makePostRequest(
            endpoint = "/repos/$owner/$repo/issues",
            accessToken = accessToken,
            body = request,
            responseClass = GitHubIssue::class
        )
    }

    /**
     * List issues
     */
    suspend fun listIssues(
        accessToken: String,
        owner: String,
        repo: String,
        state: String = "open",
        perPage: Int = 30,
        page: Int = 1
    ): Result<List<GitHubIssue>> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "state" to state,
            "per_page" to perPage.toString(),
            "page" to page.toString()
        )
        return@withContext makeGetRequestList<GitHubIssue>(
            endpoint = "/repos/$owner/$repo/issues",
            accessToken = accessToken,
            queryParams = params
        )
    }

    /**
     * Search code in repositories
     */
    suspend fun searchCode(
        accessToken: String,
        query: String, // e.g., "addClass in:file language:js repo:jquery/jquery"
        perPage: Int = 30,
        page: Int = 1
    ): Result<GitHubCodeSearchResult> = withContext(Dispatchers.IO) {
        val params = mapOf(
            "q" to query,
            "per_page" to perPage.toString(),
            "page" to page.toString()
        )
        return@withContext makeGetRequest(
            endpoint = "/search/code",
            accessToken = accessToken,
            queryParams = params,
            responseClass = GitHubCodeSearchResult::class
        )
    }

    // Helper methods

    private fun buildUrl(endpoint: String, queryParams: Map<String, String> = emptyMap()): URL {
        val url = StringBuilder("$BASE_URL$endpoint")
        if (queryParams.isNotEmpty()) {
            url.append("?")
            url.append(queryParams.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            })
        }
        return URL(url.toString())
    }

    private fun createConnection(url: URL, method: String, accessToken: String): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
        connection.setRequestProperty("User-Agent", "ChatAPI-Android")
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        return connection
    }

    private inline fun <reified T : Any> makeGetRequest(
        endpoint: String,
        accessToken: String,
        queryParams: Map<String, String> = emptyMap(),
        responseClass: kotlin.reflect.KClass<*>,
        isList: Boolean = false
    ): Result<T> {
        return try {
            val url = buildUrl(endpoint, queryParams)
            val connection = createConnection(url, "GET", accessToken)

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "GET $endpoint: $responseCode")

            if (responseCode in 200..299) {
                val result = JsonConfig.standard.decodeFromString<T>(responseBody)
                Result.success(result)
            } else {
                val error = try {
                    JsonConfig.standard.decodeFromString<GitHubError>(responseBody)
                } catch (e: Exception) {
                    GitHubError(message = "HTTP $responseCode: $responseBody")
                }
                Result.failure(Exception(error.message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $endpoint error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private inline fun <reified T : Any> makeGetRequestList(
        endpoint: String,
        accessToken: String,
        queryParams: Map<String, String> = emptyMap()
    ): Result<List<T>> {
        return try {
            val url = buildUrl(endpoint, queryParams)
            val connection = createConnection(url, "GET", accessToken)

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "GET $endpoint: $responseCode")

            if (responseCode in 200..299) {
                val result = JsonConfig.standard.decodeFromString<List<T>>(responseBody)
                Result.success(result)
            } else {
                val error = try {
                    JsonConfig.standard.decodeFromString<GitHubError>(responseBody)
                } catch (e: Exception) {
                    GitHubError(message = "HTTP $responseCode: $responseBody")
                }
                Result.failure(Exception(error.message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $endpoint error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private inline fun <reified T : Any, reified R : Any> makePostRequest(
        endpoint: String,
        accessToken: String,
        body: T,
        responseClass: kotlin.reflect.KClass<*>
    ): Result<R> {
        return try {
            val url = buildUrl(endpoint)
            val connection = createConnection(url, "POST", accessToken)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val requestBody = JsonConfig.standard.encodeToString(body)
            Log.d(TAG, "POST $endpoint: $requestBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "POST $endpoint response: $responseCode")

            if (responseCode in 200..299) {
                val result = JsonConfig.standard.decodeFromString<R>(responseBody)
                Result.success(result)
            } else {
                val error = try {
                    JsonConfig.standard.decodeFromString<GitHubError>(responseBody)
                } catch (e: Exception) {
                    GitHubError(message = "HTTP $responseCode: $responseBody")
                }
                Result.failure(Exception(error.message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $endpoint error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private inline fun <reified T : Any, reified R : Any> makePutRequest(
        endpoint: String,
        accessToken: String,
        body: T,
        responseClass: kotlin.reflect.KClass<*>
    ): Result<R> {
        return try {
            val url = buildUrl(endpoint)
            val connection = createConnection(url, "PUT", accessToken)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val requestBody = JsonConfig.standard.encodeToString(body)
            Log.d(TAG, "PUT $endpoint: $requestBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "PUT $endpoint response: $responseCode")

            if (responseCode in 200..299) {
                val result = JsonConfig.standard.decodeFromString<R>(responseBody)
                Result.success(result)
            } else {
                val error = try {
                    JsonConfig.standard.decodeFromString<GitHubError>(responseBody)
                } catch (e: Exception) {
                    GitHubError(message = "HTTP $responseCode: $responseBody")
                }
                Result.failure(Exception(error.message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "PUT $endpoint error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private inline fun <reified R : Any> makeDeleteRequest(
        endpoint: String,
        accessToken: String,
        body: String,
        responseClass: kotlin.reflect.KClass<*>
    ): Result<R> {
        return try {
            val url = buildUrl(endpoint)
            val connection = createConnection(url, "DELETE", accessToken)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }

            Log.d(TAG, "DELETE $endpoint response: $responseCode")

            if (responseCode in 200..299) {
                val result = JsonConfig.standard.decodeFromString<R>(responseBody)
                Result.success(result)
            } else {
                val error = try {
                    JsonConfig.standard.decodeFromString<GitHubError>(responseBody)
                } catch (e: Exception) {
                    GitHubError(message = "HTTP $responseCode: $responseBody")
                }
                Result.failure(Exception(error.message))
            }
        } catch (e: Exception) {
            Log.e(TAG, "DELETE $endpoint error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
