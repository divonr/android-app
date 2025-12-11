package com.example.ApI.tools.google.calendar

import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for listing events from Google Calendar
 */
class CalendarListEventsTool(
    private val apiService: GoogleCalendarApiService,
    private val googleEmail: String
) : Tool {

    override val id: String = "calendar_list_events"
    override val name: String = "List Calendar Events"
    override val description: String = "List events from your Google Calendar. You are authenticated as '$googleEmail'. Optionally specify calendar ID (default 'primary'), time range (timeMin/timeMax in RFC3339 format), and maximum results (default 10, max 100)."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val calendarId = parameters["calendarId"]?.jsonPrimitive?.contentOrNull ?: "primary"
            val timeMin = parameters["timeMin"]?.jsonPrimitive?.contentOrNull
            val timeMax = parameters["timeMax"]?.jsonPrimitive?.contentOrNull
            val maxResults = parameters["maxResults"]?.jsonPrimitive?.intOrNull ?: 10

            // Validate maxResults
            val validMaxResults = maxResults.coerceIn(1, 100)

            // Call Calendar API
            val result = apiService.listEvents(calendarId, timeMin, timeMax, validMaxResults)

            result.fold(
                onSuccess = { events ->
                    if (events.isEmpty()) {
                        ToolExecutionResult.Success(
                            result = "No events found in the specified time range.",
                            details = buildJsonObject {
                                put("calendarId", calendarId)
                                put("count", 0)
                            }
                        )
                    } else {
                        val resultText = buildString {
                            appendLine("**Calendar Events**")
                            appendLine()
                            appendLine("Found ${events.size} event(s) in calendar '$calendarId'")
                            appendLine()

                            events.forEachIndexed { index, event ->
                                appendLine("**${index + 1}. ${event.summary}**")
                                event.id?.let { appendLine("   **Event ID:** $it") }

                                // Format start/end times
                                val startTime = event.start.dateTime ?: event.start.date ?: "Unknown"
                                val endTime = event.end.dateTime ?: event.end.date ?: "Unknown"
                                appendLine("   **Start:** $startTime")
                                appendLine("   **End:** $endTime")

                                event.location?.let { appendLine("   **Location:** $it") }
                                event.description?.let {
                                    val desc = it.take(100) + if (it.length > 100) "..." else ""
                                    appendLine("   **Description:** $desc")
                                }

                                if (event.attendees.isNotEmpty()) {
                                    val attendeeNames = event.attendees.take(3).joinToString(", ") {
                                        it.displayName ?: it.email
                                    }
                                    val moreCount = (event.attendees.size - 3).coerceAtLeast(0)
                                    val moreText = if (moreCount > 0) " and $moreCount more" else ""
                                    appendLine("   **Attendees:** $attendeeNames$moreText")
                                }

                                event.status?.let { appendLine("   **Status:** $it") }
                                event.htmlLink?.let { appendLine("   **Link:** $it") }
                                appendLine()
                            }
                        }

                        val details = buildJsonObject {
                            put("calendarId", calendarId)
                            put("count", events.size)
                            put("events", JsonArray(events.map { event ->
                                buildJsonObject {
                                    put("id", event.id ?: "")
                                    put("summary", event.summary)
                                    put("start", event.start.dateTime ?: event.start.date ?: "")
                                    put("end", event.end.dateTime ?: event.end.date ?: "")
                                }
                            }))
                        }

                        ToolExecutionResult.Success(
                            result = resultText,
                            details = details
                        )
                    }
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to list events: ${error.message}",
                        buildJsonObject {
                            put("calendarId", calendarId)
                        }
                    )
                }
            )
        } catch (e: Exception) {
            ToolExecutionResult.Error("Error executing tool: ${e.message}")
        }
    }

    override fun getSpecification(provider: String): ToolSpecification {
        return when (provider) {
            "openai" -> getOpenAISpecification()
            "poe" -> getPoeSpecification()
            "google" -> getGoogleSpecification()
            else -> getDefaultSpecification()
        }
    }

    private fun getOpenAISpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("calendarId", buildJsonObject {
                        put("type", "string")
                        put("description", "Calendar ID (default 'primary' for primary calendar)")
                        put("default", "primary")
                    })
                    put("timeMin", buildJsonObject {
                        put("type", "string")
                        put("description", "Lower bound for event start time (RFC3339 format, e.g., '2024-01-01T00:00:00Z')")
                    })
                    put("timeMax", buildJsonObject {
                        put("type", "string")
                        put("description", "Upper bound for event end time (RFC3339 format, e.g., '2024-12-31T23:59:59Z')")
                    })
                    put("maxResults", buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of events to return (default 10, max 100)")
                        put("default", 10)
                    })
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }

    private fun getPoeSpecification(): ToolSpecification {
        return getOpenAISpecification() // Poe uses similar format
    }

    private fun getGoogleSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("calendarId", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Calendar ID (default 'primary' for primary calendar)")
                        put("default", "primary")
                    })
                    put("timeMin", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Lower bound for event start time (RFC3339 format, e.g., '2024-01-01T00:00:00Z')")
                    })
                    put("timeMax", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Upper bound for event end time (RFC3339 format, e.g., '2024-12-31T23:59:59Z')")
                    })
                    put("maxResults", buildJsonObject {
                        put("type", "INTEGER")
                        put("description", "Maximum number of events to return (default 10, max 100)")
                        put("default", 10)
                    })
                })
                put("required", JsonArray(emptyList()))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
