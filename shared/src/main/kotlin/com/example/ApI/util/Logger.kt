package com.example.ApI.util

interface Logger {
    fun d(message: String)
    fun i(message: String)
    fun w(message: String)
    fun e(message: String)
    fun e(message: String, throwable: Throwable)
    fun logApiRequest(
        conversationName: String,
        model: String,
        provider: String,
        baseUrl: String,
        headers: Map<String, String>,
        body: String
    )
    fun logApiResponse(responseCode: Int)
    fun logApiError(errorContent: String)
}
