package com.example.ApI.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ApI.data.model.ChildLockSettings
import com.example.ApI.ui.theme.*

@Composable
fun ChildLockSettingsSection(
    settings: ChildLockSettings,
    onSettingsChange: (Boolean, String, String, String) -> Unit,
    onShowSetupDialog: () -> Unit,
    onShowDisableDialog: () -> Unit,
    deviceId: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Child Lock Main Switch
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceVariant,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "מצב נעילת ילדים",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurface,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "הגדר שעות בהן האפליקציה אינה פעילה",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onShowSetupDialog()
                            } else {
                                onShowDisableDialog()
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Primary,
                            checkedTrackColor = Primary.copy(alpha = 0.3f),
                            uncheckedThumbColor = OnSurfaceVariant,
                            uncheckedTrackColor = OnSurfaceVariant.copy(alpha = 0.2f)
                        )
                    )
                }

                // Show time range when enabled
                if (settings.enabled) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Surface,
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "Start Time",
                                    tint = OnSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "משעה: ${settings.startTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = "End Time",
                                    tint = OnSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "עד שעה: ${settings.endTime}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
