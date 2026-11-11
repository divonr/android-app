package com.example.ApI.tools.google.calendar

import com.example.ApI.data.network.GoogleCalendarApiService
import com.example.ApI.tools.Tool
import com.example.ApI.tools.ToolExecutionResult
import com.example.ApI.tools.ToolSpecification
import kotlinx.serialization.json.*

/**
 * Tool for getting a specific event from Google Calendar
 */
class CalendarGetEventTool(
    private val apiService: GoogleCalendarApiService,
    private val googleEmail: String
) : Tool {

    override val id: String = "calendar_get_event"
    override val name: String = "Get Calendar Event"
    override val description: String = "Get detailed information about a specific event from your Google Calendar. You are authenticated as '$googleEmail'. Provide the event ID and optionally calendar ID (default 'primary')."

    override suspend fun execute(parameters: JsonObject): ToolExecutionResult {
        return try {
            // Extract parameters
            val eventId = parameters["eventId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolExecutionResult.Error("Parameter 'eventId' is required")
            val calendarId = parameters["calendarId"]?.jsonPrimitive?.contentOrNull ?: "primary"

            // Call Calendar API
            val result = apiService.getEvent(calendarId, eventId)

            result.fold(
                onSuccess = { event ->
                    val resultText = buildString {
                        appendLine("**Calendar Event Details**")
                        appendLine()
                        appendLine("**Summary:** ${event.summary}")
                        event.id?.let { appendLine("**Event ID:** $it") }

                        val startTime = event.start.dateTime ?: event.start.date ?: "Unknown"
                        val endTime = event.end.dateTime ?: event.end.date ?: "Unknown"
                        appendLine("**Start:** $startTime")
                        appendLine("**End:** $endTime")

                        event.location?.let { appendLine("**Location:** $it") }
                        event.description?.let { appendLine("**Description:** $it") }

                        if (event.attendees.isNotEmpty()) {
                            appendLine()
                            appendLine("**Attendees:**")
                            event.attendees.forEach { attendee ->
                                val name = attendee.displayName ?: attendee.email
                                val status = attendee.responseStatus?.let { " ($it)" } ?: ""
                                val role = if (attendee.organizer) " [Organizer]" else if (attendee.optional) " [Optional]" else ""
                                appendLine("  - $name$status$role")
                            }
                        }

                        if (event.recurrence.isNotEmpty()) {
                            appendLine()
                            appendLine("**Recurrence:**")
                            event.recurrence.forEach { rule ->
                                appendLine("  $rule")
                            }
                        }

                        event.status?.let { appendLine("**Status:** $it") }
                        event.htmlLink?.let { appendLine("**Link:** $it") }
                        event.created?.let { appendLine("**Created:** $it") }
                        event.updated?.let { appendLine("**Updated:** $it") }
                    }

                    val details = buildJsonObject {
                        put("eventId", event.id ?: "")
                        put("summary", event.summary)
                        put("start", event.start.dateTime ?: event.start.date ?: "")
                        put("end", event.end.dateTime ?: event.end.date ?: "")
                        put("calendarId", calendarId)
                        put("attendeeCount", event.attendees.size)
                    }

                    ToolExecutionResult.Success(
                        result = resultText,
                        details = details
                    )
                },
                onFailure = { error ->
                    ToolExecutionResult.Error(
                        "Failed to get event: ${error.message}",
                        buildJsonObject {
                            put("eventId", eventId)
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
                    put("eventId", buildJsonObject {
                        put("type", "string")
                        put("description", "The event ID")
                    })
                    put("calendarId", buildJsonObject {
                        put("type", "string")
                        put("description", "Calendar ID (default 'primary' for primary calendar)")
                        put("default", "primary")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("eventId"))))
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
                    put("eventId", buildJsonObject {
                        put("type", "STRING")
                        put("description", "The event ID")
                    })
                    put("calendarId", buildJsonObject {
                        put("type", "STRING")
                        put("description", "Calendar ID (default 'primary' for primary calendar)")
                    })
                })
                put("required", JsonArray(listOf(JsonPrimitive("eventId"))))
            }
        )
    }

    private fun getDefaultSpecification(): ToolSpecification {
        return getOpenAISpecification()
    }
}
