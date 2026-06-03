package com.example.ApI.util

class AndroidLogger : Logger {
    override fun d(message: String) = AppLogger.d(message)
    override fun i(message: String) = AppLogger.i(message)
    override fun w(message: String) = AppLogger.w(message)
    override fun e(message: String) = AppLogger.e(message)
    override fun e(message: String, throwable: Throwable) = AppLogger.e(message, throwable)

    override fun logApiRequest(
        conversationName: String, model: String, provider: String,
        baseUrl: String, headers: Map<String, String>, body: String
    ) = AppLogger.logApiRequest(conversationName, model, provider, baseUrl, headers, body)

    override fun logApiResponse(responseCode: Int) = AppLogger.logApiResponse(responseCode)
    override fun logApiError(errorContent: String) = AppLogger.logApiError(errorContent)
}
