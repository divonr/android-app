package com.example.ApI.data.network

import android.util.Log
import com.example.ApI.data.model.Calendar
import com.example.ApI.data.model.CalendarAttendee
import com.example.ApI.data.model.CalendarEvent
import com.example.ApI.data.model.CalendarEventDateTime
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventAttendee
import com.google.api.services.calendar.model.EventDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for interacting with Google Calendar API
 */
class GoogleCalendarApiService(
    private val accessToken: String,
    private val userEmail: String
) {
    companion object {
        private const val TAG = "GoogleCalendarService"
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
     * Get Calendar service instance
     */
    private fun getCalendarService(): com.google.api.services.calendar.Calendar {
        return com.google.api.services.calendar.Calendar.Builder(httpTransport, jsonFactory, createRequestInitializer())
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    /**
     * List events from a calendar
     * @param calendarId Calendar ID (use "primary" for primary calendar)
     * @param timeMin Start time for event search (RFC3339 format)
     * @param timeMax End time for event search (RFC3339 format)
     * @param maxResults Maximum number of events to return
     * @return Result containing list of CalendarEvents or error
     */
    suspend fun listEvents(
        calendarId: String = "primary",
        timeMin: String? = null,
        timeMax: String? = null,
        maxResults: Int = 10
    ): Result<List<CalendarEvent>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Listing events from calendar: $calendarId")

            val service = getCalendarService()
            val request = service.events().list(calendarId)
                .setMaxResults(maxResults)
                .setOrderBy("startTime")
                .setSingleEvents(true)

            timeMin?.let { request.timeMin = DateTime(it) }
            timeMax?.let { request.timeMax = DateTime(it) }

            val events = request.execute()
            val items = events.items ?: emptyList()

            Log.d(TAG, "Found ${items.size} events")

            val calendarEvents = items.map { parseEvent(it) }
            Result.success(calendarEvents)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing events", e)
            Result.failure(e)
        }
    }

    /**
     * Get a specific event by ID
     * @param calendarId Calendar ID
     * @param eventId Event ID
     * @return Result containing CalendarEvent or error
     */
    suspend fun getEvent(
        calendarId: String = "primary",
        eventId: String
    ): Result<CalendarEvent> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching event: $eventId")

            val service = getCalendarService()
            val event = service.events().get(calendarId, eventId).execute()

            val calendarEvent = parseEvent(event)
            Log.d(TAG, "Event fetched successfully: ${calendarEvent.summary}")
            Result.success(calendarEvent)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching event", e)
            Result.failure(e)
        }
    }

    /**
     * Create a new event
     * @param calendarId Calendar ID (use "primary" for primary calendar)
     * @param event CalendarEvent to create
     * @return Result containing created CalendarEvent or error
     */
    suspend fun createEvent(
        calendarId: String = "primary",
        event: CalendarEvent
    ): Result<CalendarEvent> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Creating event: ${event.summary}")

            val service = getCalendarService()
            val apiEvent = toApiEvent(event)

            val createdEvent = service.events().insert(calendarId, apiEvent).execute()

            Log.d(TAG, "Event created successfully: ${createdEvent.id}")
            val calendarEvent = parseEvent(createdEvent)
            Result.success(calendarEvent)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating event", e)
            Result.failure(e)
        }
    }

    /**
     * List all calendars for the user
     * @return Result containing list of Calendars or error
     */
    suspend fun listCalendars(): Result<List<Calendar>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Listing calendars")

            val service = getCalendarService()
            val calendarList = service.calendarList().list().execute()
            val items = calendarList.items ?: emptyList()

            Log.d(TAG, "Found ${items.size} calendars")

            val calendars = items.map { cal ->
                Calendar(
                    id = cal.id,
                    summary = cal.summary ?: "",
                    description = cal.description,
                    timeZone = cal.timeZone,
                    primary = cal.primary ?: false,
                    accessRole = cal.accessRole
                )
            }

            Result.success(calendars)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing calendars", e)
            Result.failure(e)
        }
    }

    /**
     * Parse Google Calendar API Event to CalendarEvent model
     */
    private fun parseEvent(event: Event): CalendarEvent {
        return CalendarEvent(
            id = event.id,
            summary = event.summary ?: "",
            description = event.description,
            location = event.location,
            start = parseEventDateTime(event.start),
            end = parseEventDateTime(event.end),
            attendees = event.attendees?.map { parseAttendee(it) } ?: emptyList(),
            recurrence = event.recurrence ?: emptyList(),
            htmlLink = event.htmlLink,
            status = event.status,
            created = event.created?.toString(),
            updated = event.updated?.toString()
        )
    }

    /**
     * Parse Google Calendar API EventDateTime to CalendarEventDateTime model
     */
    private fun parseEventDateTime(eventDateTime: EventDateTime?): CalendarEventDateTime {
        return CalendarEventDateTime(
            dateTime = eventDateTime?.dateTime?.toString(),
            date = eventDateTime?.date?.toString(),
            timeZone = eventDateTime?.timeZone
        )
    }

    /**
     * Parse Google Calendar API EventAttendee to CalendarAttendee model
     */
    private fun parseAttendee(attendee: EventAttendee): CalendarAttendee {
        return CalendarAttendee(
            email = attendee.email,
            displayName = attendee.displayName,
            optional = attendee.optional ?: false,
            responseStatus = attendee.responseStatus,
            organizer = attendee.organizer ?: false
        )
    }

    /**
     * Convert CalendarEvent model to Google Calendar API Event
     */
    private fun toApiEvent(calendarEvent: CalendarEvent): Event {
        val event = Event()
        event.summary = calendarEvent.summary
        event.description = calendarEvent.description
        event.location = calendarEvent.location

        // Set start time
        event.start = toApiEventDateTime(calendarEvent.start)

        // Set end time
        event.end = toApiEventDateTime(calendarEvent.end)

        // Set attendees
        if (calendarEvent.attendees.isNotEmpty()) {
            event.attendees = calendarEvent.attendees.map { attendee ->
                EventAttendee().apply {
                    email = attendee.email
                    displayName = attendee.displayName
                    optional = attendee.optional
                }
            }
        }

        // Set recurrence
        if (calendarEvent.recurrence.isNotEmpty()) {
            event.recurrence = calendarEvent.recurrence
        }

        return event
    }

    /**
     * Convert CalendarEventDateTime model to Google Calendar API EventDateTime
     */
    private fun toApiEventDateTime(dateTime: CalendarEventDateTime): EventDateTime {
        val eventDateTime = EventDateTime()

        if (dateTime.dateTime != null) {
            eventDateTime.dateTime = DateTime(dateTime.dateTime)
            eventDateTime.timeZone = dateTime.timeZone
        } else if (dateTime.date != null) {
            // All-day event
            eventDateTime.date = com.google.api.client.util.DateTime(dateTime.date)
        }

        return eventDateTime
    }
}
