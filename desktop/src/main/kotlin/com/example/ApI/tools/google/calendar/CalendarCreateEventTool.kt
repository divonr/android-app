package com.example.ApI.tools.google.calendar

import com.example.ApI.data.model.CalendarEvent
import com.example.ApI.data.model.CalendarEventDateTime
import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for creating a new event in Google Calendar
 */
class CalendarCreateEventTool(
    private val apiService: GoogleCalendarApiService,
    private val googleEmail: String
) : Tool {

    override val id: String = "calendar_create_event"
    override val name: String = "Create Calendar Event"
    override val description: String = "Create a new event in your Google Calendar. You are authenticated as '$googleEmail'. Provide event summary (title), start time, end time in RFC3339 format. Optionally provide description, location, and calendar ID (default 'primary')."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract required parameters
            val summary = parameters["summary"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'summary' is required")
            val startDateTime = parameters["startDateTime"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'startDateTime' is required (RFC3339 format)")
            val endDateTime = parameters["endDateTime"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'endDateTime' is required (RFC3339 format)")

            // Optional parameters
            val calendarId = parameters["calendarId"]?.jsonPrimitive?.contentOrNull ?: "primary"
            val description = parameters["description"]?.jsonPrimitive?.contentOrNull
            val location = parameters["location"]?.jsonPrimitive?.contentOrNull

            // Build event
            val event = CalendarEvent(
                id = null,
                summary = summary,
                description = description,
                location = location,
                start = CalendarEventDateTime(dateTime = startDateTime, date = null),
                end = CalendarEventDateTime(dateTime = endDateTime, date = null)
            )

            // Call Calendar API
            val result = apiService.createEvent(calendarId, event)

            result.fold(
                onSuccess = { createdEvent ->
                    val resultText = buildString {
                        appendLine("**Event Created Successfully**")
                        appendLine()
                        appendLine("**Summary:** ${createdEvent.summary}")
                        createdEvent.id?.let { appendLine("**Event ID:** $it") }
                        appendLine("**Start:** ${createdEvent.start.dateTime ?: createdEvent.start.date}")
                        appendLine("**End:** ${createdEvent.end.dateTime ?: createdEvent.end.date}")
                        createdEvent.location?.let { appendLine("**Location:** $it") }
                        createdEvent.description?.let { appendLine("**Description:** $it") }
                        createdEvent.htmlLink?.let { appendLine("**Link:** $it") }
                    }

                    val details = buildJsonObject {
                        put("eventId", createdEvent.id ?: "")
                        put("summary", createdEvent.summary)
                        put("calendarId", calendarId)
                        put("htmlLink", createdEvent.htmlLink ?: "")
                    }

                    ToolExecutionResult.Success(
                        result = resultText,
                        details = details
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to create event: ${error.message}",
                        buildJsonObject {
                            put("summary", summary)
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
                    put("summary", buildJsonObject {
                        put("type", "string")
                        put("description", "Event title/summary")
                    })
                    put("startDateTime", buildJsonObject {
                        put("type", "string")
                        put("description", "Event start time in RFC3339 format (e.g., '2024-12-25T10:00:00Z' or '2024-12-25T10:00:00-08:00')")
                    })
                    put("endDateTime", buildJsonObject {
                        put("type", "string")
                        put("description", "Event end time in RFC3339 format (e.g., '2024-12-25T11:00:00Z' or '2024-12-25T11:00:00-08:00')")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Event description (optional)")
                    })
                    put("location", buildJsonObject {
                        put("type", "string")
                        put("description", "Event location (optional)")
                    })
                    put("calendarId", buildJsonObject {
                        put("type", "string")
                        put("description", "Calendar ID (default 'primary')")
                        put("default", "primary")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("summary"),
                    JsonPrimitive("startDateTime"),
                    JsonPrimitive("endDateTime")
                )))
            }
        )
    }

    private fun getPoeSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }

    private fun getGoogleSpecification(): ToolSpecification {
        return ToolSpecification(
            name = id,
            description = description,
            parameters = buildJsonObject {
                put("type", "OBJECT")
                put("properties", buildJsonObject {
                    put("summary", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Event title/summary")
                    })
                    put("startDateTime", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Event start time in RFC3339 format")
                    })
                    put("endDateTime", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Event end time in RFC3339 format")
                    })
                    put("description", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Event description (optional)")
                    })
                    put("location", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Event location (optional)")
                    })
                    put("calendarId", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Calendar ID (default 'primary')")
                    })
                })
                put("required", JsonArray(listOf(
                    JsonPrimitive("summary"),
                    JsonPrimitive("startDateTime"),
                    JsonPrimitive("endDateTime")
                )))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
