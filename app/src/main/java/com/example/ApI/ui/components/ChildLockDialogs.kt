package com.example.ApI.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ApI.ui.theme.OnSurface
import com.example.ApI.ui.theme.SurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildLockSetupDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: (Boolean) -> Unit,
    startTime: String,
    onStartTimeChange: (String) -> Unit,
    endTime: String,
    onEndTimeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("הגדרת נעילת ילדים") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "להפעלת מצב נעילת ילדים, הקלידו סיסמה. זכרו את הסיסמה! לא תהיה אפשרות לשחרר את נעילת הילדים ללא הסיסמה",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("סיסמה") },
                    placeholder = { Text("הקלד סיסמה") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { onPasswordVisibilityChange(!passwordVisible) }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    }
                )

                // Time selection
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Start time
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "משעה:",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showStartTimePicker = true },
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = startTime,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Select Time",
                                    tint = OnSurface
                                )
                            }
                        }
                    }

                    // End time
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "עד שעה:",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.7f)
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showEndTimePicker = true },
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = endTime,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Select Time",
                                    tint = OnSurface
                                )
                            }
                        }
                    }
                }

                // Time picker dialogs
                if (showStartTimePicker) {
                    TimePickerDialog(
                        initialTime = startTime,
                        onTimeSelected = { time ->
                            onStartTimeChange(time)
                            showStartTimePicker = false
                        },
                        onDismiss = { showStartTimePicker = false }
                    )
                }

                if (showEndTimePicker) {
                    TimePickerDialog(
                        initialTime = endTime,
                        onTimeSelected = { time ->
                            onEndTimeChange(time)
                            showEndTimePicker = false
                        },
                        onDismiss = { showEndTimePicker = false }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = password.isNotEmpty()
            ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildLockDisableDialog(
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("שחרור נעילת ילדים") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "הקלד את הסיסמה כדי לשחרר את נעילת הילדים:",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("סיסמה") },
                    placeholder = { Text("הקלד סיסמה") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { onPasswordVisibilityChange(!passwordVisible) }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = password.isNotEmpty()
            ) {
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
