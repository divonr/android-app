package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.ui.theme.OnSurfaceVariant
import com.example.ApI.ui.theme.SurfaceVariant
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val hebrewWeekdays = listOf(
    "יום שני", "יום שלישי", "יום רביעי", "יום חמישי", "יום שישי", "שבת", "יום ראשון"
)

private val hebrewMonths = listOf(
    "ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני",
    "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר"
)

/** Local calendar date of a message's ISO-8601 datetime, or null if missing/unparseable. */
fun messageLocalDate(datetime: String?): LocalDate? {
    if (datetime.isNullOrBlank()) return null
    return try {
        Instant.parse(datetime).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (e: Exception) {
        null
    }
}

/** WhatsApp-style date label: today / yesterday / weekday (last week) / full date. */
fun formatChatDateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val daysAgo = ChronoUnit.DAYS.between(date, today)
    return when {
        daysAgo == 0L -> "היום"
        daysAgo == 1L -> "אתמול"
        daysAgo in 2..6 -> hebrewWeekdays[date.dayOfWeek.value - 1]
        date.year == today.year -> "${date.dayOfMonth} ב${hebrewMonths[date.monthValue - 1]}"
        else -> "${date.dayOfMonth} ב${hebrewMonths[date.monthValue - 1]} ${date.year}"
    }
}

/** Rounded date pill shown between messages of different days and floating while scrolling. */
@Composable
fun DateChip(
    text: String,
    modifier: Modifier = Modifier,
    elevated: Boolean = false
) {
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = OnSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .then(if (elevated) Modifier.shadow(3.dp, shape) else Modifier)
            .background(SurfaceVariant, shape)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/** Full-width row centering a [DateChip], used as an inline separator in the message list. */
@Composable
fun DateSeparator(
    date: LocalDate,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        DateChip(text = formatChatDateLabel(date))
    }
}
