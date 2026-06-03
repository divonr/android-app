package com.example.ApI.data.model

import kotlinx.serialization.Serializable

// ==================== Gmail Models ====================

/**
 * Gmail message model
 */
@Serializable
data class GmailMessage(
    val id: String,
    val threadId: String,
    val subject: String?,
    val from: String?,
    val to: List<String>,
    val date: String?,
    val snippet: String?,
    val body: String?,
    val isRead: Boolean = false,
    val labels: List<String> = emptyList()
)

/**
 * Gmail label model
 */
@Serializable
data class GmailLabel(
    val id: String,
    val name: String,
    val type: String
)

// ==================== Calendar Models ====================

/**
 * Calendar event model
 */
@Serializable
data class CalendarEvent(
    val id: String?,
    val summary: String,
    val description: String?,
    val location: String?,
    val start: CalendarEventDateTime,
    val end: CalendarEventDateTime,
    val attendees: List<CalendarAttendee> = emptyList(),
    val recurrence: List<String> = emptyList(),
    val reminders: CalendarReminders? = null,
    val htmlLink: String? = null,
    val status: String? = null,
    val created: String? = null,
    val updated: String? = null
)

/**
 * Calendar event date/time
 */
@Serializable
data class CalendarEventDateTime(
    val dateTime: String?,  // RFC3339 format
    val date: String?,      // Date only (for all-day events)
    val timeZone: String? = null
)

/**
 * Calendar attendee model
 */
@Serializable
data class CalendarAttendee(
    val email: String,
    val displayName: String? = null,
    val optional: Boolean = false,
    val responseStatus: String? = null,
    val organizer: Boolean = false
)

/**
 * Calendar reminders
 */
@Serializable
data class CalendarReminders(
    val useDefault: Boolean = true,
    val overrides: List<CalendarReminderOverride> = emptyList()
)

/**
 * Calendar reminder override
 */
@Serializable
data class CalendarReminderOverride(
    val method: String,  // "email" or "popup"
    val minutes: Int
)

/**
 * Calendar metadata
 */
@Serializable
data class Calendar(
    val id: String,
    val summary: String,
    val description: String? = null,
    val timeZone: String? = null,
    val primary: Boolean = false,
    val accessRole: String? = null
)

// ==================== Drive Models ====================

/**
 * Google Drive file model
 */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long? = null,
    val createdTime: String?,
    val modifiedTime: String?,
    val webViewLink: String? = null,
    val webContentLink: String? = null,
    val iconLink: String? = null,
    val thumbnailLink: String? = null,
    val parents: List<String> = emptyList(),
    val isFolder: Boolean = false,
    val description: String? = null,
    val starred: Boolean = false,
    val trashed: Boolean = false
) {
    companion object {
        const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
        const val MIME_TYPE_DOCUMENT = "application/vnd.google-apps.document"
        const val MIME_TYPE_SPREADSHEET = "application/vnd.google-apps.spreadsheet"
        const val MIME_TYPE_PRESENTATION = "application/vnd.google-apps.presentation"
    }
}

/**
 * Drive file permissions
 */
@Serializable
data class DrivePermission(
    val id: String,
    val type: String,  // "user", "group", "domain", "anyone"
    val role: String,  // "owner", "organizer", "fileOrganizer", "writer", "commenter", "reader"
    val emailAddress: String? = null,
    val displayName: String? = null
)

/**
 * Drive about info (storage quota, user info)
 */
@Serializable
data class DriveAbout(
    val user: GoogleWorkspaceUser,
    val storageQuota: DriveStorageQuota? = null
)

/**
 * Drive storage quota
 */
@Serializable
data class DriveStorageQuota(
    val limit: Long,
    val usage: Long,
    val usageInDrive: Long,
    val usageInDriveTrash: Long
)
