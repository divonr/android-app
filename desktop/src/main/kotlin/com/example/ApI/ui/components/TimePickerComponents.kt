package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import com.example.ApI.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialTime: String,
    onTimeSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val timeParts = initialTime.split(":").map { it.toIntOrNull() ?: 0 }
    var selectedHour by remember { mutableStateOf(timeParts.getOrElse(0) { 0 }) }
    var selectedMinute by remember { mutableStateOf(timeParts.getOrElse(1) { 0 }) }
    var showQuickSelect by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("בחר שעה")
                TextButton(onClick = { showQuickSelect = !showQuickSelect }) {
                    Text(if (showQuickSelect) "בחירה מדויקת" else "בחירה מהירה")
                }
            }
        },
        text = {
            if (showQuickSelect) {
                QuickTimeSelector(
                    selectedHour = selectedHour,
                    selectedMinute = selectedMinute,
                    onHourSelected = { selectedHour = it },
                    onMinuteSelected = { selectedMinute = it }
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Digital time display
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Primary.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = String.format("%02d:%02d", selectedHour, selectedMinute),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Minute picker
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("דקות", style = MaterialTheme.typography.bodySmall)
                            NumberPicker(
                                value = selectedMinute,
                                onValueChange = { selectedMinute = it },
                                range = 0..59
                            )
                        }

                        Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))

                        // Hour picker
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("שעות", style = MaterialTheme.typography.bodySmall)
                            NumberPicker(
                                value = selectedHour,
                                onValueChange = { selectedHour = it },
                                range = 0..23
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val formattedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                onTimeSelected(formattedTime)
            }) {
                Text("אישור")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("ביטול")
            }
        }
    )
}

@Composable
private fun NumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange
) {
    val itemHeight = 40.dp
    val visibleItems = 3

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Up arrow button
        Surface(
            modifier = Modifier
                .size(32.dp)
                .clickable {
                    val nextValue = if (value >= range.last) range.first else value + 1
                    onValueChange(nextValue)
                },
            shape = RoundedCornerShape(4.dp),
            color = SurfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▲", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
            }
        }

        // Scrolling wheel area
        Box(
            modifier = Modifier
                .height(itemHeight * visibleItems)
                .width(48.dp)
                .pointerInput(value, range) {
                    detectDragGestures { _, dragAmount ->
                        // Drag up (negative Y) increases value, drag down decreases
                        if (dragAmount.y < -10f) {
                            // Dragging up - increase value
                            val nextValue = if (value >= range.last) range.first else value + 1
                            onValueChange(nextValue)
                        } else if (dragAmount.y > 10f) {
                            // Dragging down - decrease value
                            val prevValue = if (value <= range.first) range.last else value - 1
                            onValueChange(prevValue)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Display visible items around current value
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                for (i in -1..1) {
                    val displayValue = when (i) {
                        -1 -> if (value <= range.first) range.last else value - 1
                        0 -> value
                        1 -> if (value >= range.last) range.first else value + 1
                        else -> value
                    }
                    val isSelected = i == 0

                    Box(
                        modifier = Modifier
                            .height(itemHeight)
                            .width(48.dp)
                            .background(
                                if (isSelected) Primary.copy(alpha = 0.2f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayValue.toString().padStart(2, '0'),
                            style = if (isSelected) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                   else MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) Primary else OnSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Invisible clickable overlay for tap scrolling
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable {
                        // Cycle through values when clicking the wheel area
                        val nextValue = if (value >= range.last) range.first else value + 1
                        onValueChange(nextValue)
                    }
            )
        }

        // Down arrow button
        Surface(
            modifier = Modifier
                .size(32.dp)
                .clickable {
                    val prevValue = if (value <= range.first) range.last else value - 1
                    onValueChange(prevValue)
                },
            shape = RoundedCornerShape(4.dp),
            color = SurfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▼", style = MaterialTheme.typography.bodyLarge, color = OnSurface)
            }
        }
    }
}

@Composable
private fun QuickTimeSelector(
    selectedHour: Int,
    selectedMinute: Int,
    onHourSelected: (Int) -> Unit,
    onMinuteSelected: (Int) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Digital time display
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Primary.copy(alpha = 0.1f)
        ) {
            Text(
                text = String.format("%02d:%02d", selectedHour, selectedMinute),
                style = MaterialTheme.typography.headlineLarge,
                color = Primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(20.dp),
                textAlign = TextAlign.Center
            )
        }

        // Quick hour selection
        Text(
            text = "שעות נפוצות:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val commonHours = listOf(0, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23)
            items(commonHours) { hour ->
                QuickSelectChip(
                    text = String.format("%02d", hour),
                    isSelected = hour == selectedHour,
                    onClick = { onHourSelected(hour) }
                )
            }
        }

        // Quick minute selection
        Text(
            text = "דקות:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val commonMinutes = listOf(0, 15, 30, 45)
            items(commonMinutes) { minute ->
                QuickSelectChip(
                    text = String.format("%02d", minute),
                    isSelected = minute == selectedMinute,
                    onClick = { onMinuteSelected(minute) }
                )
            }
        }

        // All minutes grid (for precise selection)
        Text(
            text = "בחירה מדויקת של דקות:",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurface.copy(alpha = 0.7f)
        )

        LazyColumn(
            modifier = Modifier.height(120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Group minutes by rows of 6
            val minuteRows = (0..59).chunked(6)
            items(minuteRows) { minuteRow ->
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(minuteRow) { minute ->
                        QuickSelectChip(
                            text = String.format("%02d", minute),
                            isSelected = minute == selectedMinute,
                            onClick = { onMinuteSelected(minute) },
                            isSmall = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSelectChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    isSmall: Boolean = false
) {
    Surface(
        modifier = Modifier
            .clickable { onClick() }
            .then(
                if (isSmall) Modifier.size(32.dp)
                else Modifier.padding(vertical = 4.dp)
            ),
        shape = RoundedCornerShape(if (isSmall) 6.dp else 12.dp),
        color = if (isSelected) Primary else SurfaceVariant,
        shadowElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(
                horizontal = if (isSmall) 0.dp else 12.dp,
                vertical = if (isSmall) 0.dp else 8.dp
            )
        ) {
            Text(
                text = text,
                style = if (isSmall) MaterialTheme.typography.bodySmall
                       else MaterialTheme.typography.bodyMedium,
                color = if (isSelected) OnPrimary else OnSurfaceVariant,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
