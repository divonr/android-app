package com.example.ApI.util

class DesktopLogger : Logger {
    override fun d(message: String) = println("[DEBUG] $message")
    override fun i(message: String) = println("[INFO] $message")
    override fun w(message: String) = println("[WARN] $message")
    override fun e(message: String) { System.err.println("[ERROR] $message") }
    override fun e(message: String, throwable: Throwable) {
        System.err.println("[ERROR] $message: ${throwable.message}")
        throwable.printStackTrace(System.err)
    }

    override fun logApiRequest(
        conversationName: String, model: String, provider: String,
        baseUrl: String, headers: Map<String, String>, body: String
    ) {
        val maskedHeaders = headers.mapValues { (key, value) ->
            if (key.lowercase().contains("authorization") || key.lowercase().contains("api-key"))
                "***MASKED***" else value
        }
        i("API request from \"$conversationName\", model: $model, provider: $provider")
        i("Request: baseUrl=$baseUrl, headers=$maskedHeaders, body=$body")
    }

    override fun logApiResponse(responseCode: Int) = i("Response code: $responseCode")
    override fun logApiError(errorContent: String) = e("Error content: $errorContent")
}
