package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ApI.tools.ToolRegistry
import com.example.ApI.ui.theme.*

/**
 * Data class representing a tool for display in the toggle dropdown
 */
data class ToolItem(
    val id: String,
    val name: String,
    val integrationTitle: String
)

/**
 * Dropdown menu for toggling tools on/off.
 * Shows all active functions grouped by integration.
 */
@Composable
fun ToolToggleDropdown(
    visible: Boolean,
    enabledToolIds: List<String>,
    excludedToolIds: List<String>,
    onToolToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Get all tools that are enabled (from integrations settings)
    val toolItems = remember(enabledToolIds) {
        val toolRegistry = ToolRegistry.getInstance()
        enabledToolIds.mapNotNull { toolId ->
            val tool = toolRegistry.getTool(toolId)
            if (tool != null) {
                ToolItem(
                    id = tool.id,
                    name = tool.name,
                    integrationTitle = getIntegrationTitle(tool.id)
                )
            } else if (toolId == "get_current_group_conversations") {
                ToolItem(
                    id = toolId,
                    name = "Other conversations in group", // Or localize if possible
                    integrationTitle = getIntegrationTitle(toolId)
                )
            } else {
                null
            }
        }
    }

    // Group tools by integration
    val groupedTools = remember(toolItems) {
        toolItems.groupBy { it.integrationTitle }
            .toSortedMap() // Sort by integration title
    }

    // Calculate master checkbox state
    val allToolIds = remember(toolItems) { toolItems.map { it.id } }
    val allEnabled = allToolIds.isNotEmpty() && allToolIds.none { it in excludedToolIds }
    val noneEnabled = allToolIds.isNotEmpty() && allToolIds.all { it in excludedToolIds }

    DropdownMenu(
        expanded = visible,
        onDismissRequest = onDismiss,
        modifier = modifier
            .background(Surface, RoundedCornerShape(12.dp))
            .widthIn(min = 280.dp, max = 400.dp)
    ) {
        if (groupedTools.isEmpty()) {
            // No tools available
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "אין כלים זמינים",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                // Master checkbox - controls all tools
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "כל הכלים",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurface,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TriStateCheckbox(
                                state = when {
                                    allEnabled -> ToggleableState.On
                                    noneEnabled -> ToggleableState.Off
                                    else -> ToggleableState.Indeterminate
                                },
                                onClick = null, // Handled by DropdownMenuItem onClick
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Primary,
                                    uncheckedColor = OnSurfaceVariant.copy(alpha = 0.5f)
                                )
                            )
                        }
                    },
                    onClick = {
                        // If all enabled or some enabled -> disable all (exclude all)
                        // If none enabled -> enable all (remove all exclusions)
                        if (allEnabled || !noneEnabled) {
                            // Exclude all tools
                            allToolIds.forEach { toolId ->
                                if (toolId !in excludedToolIds) {
                                    onToolToggle(toolId, false) // false = not enabled = exclude
                                }
                            }
                        } else {
                            // Enable all tools (remove exclusions)
                            allToolIds.forEach { toolId ->
                                if (toolId in excludedToolIds) {
                                    onToolToggle(toolId, true) // true = enabled = remove exclusion
                                }
                            }
                        }
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = OnSurfaceVariant.copy(alpha = 0.2f),
                    thickness = 1.dp
                )

                groupedTools.entries.forEachIndexed { index, (integrationTitle, tools) ->
                    // Integration header
                    Text(
                        text = integrationTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = OnSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    // Tools in this integration
                    tools.forEach { tool ->
                        val isEnabled = tool.id !in excludedToolIds

                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = tool.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Checkbox(
                                        checked = isEnabled,
                                        onCheckedChange = null, // Handled by onClick
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Primary,
                                            uncheckedColor = OnSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    )
                                }
                            },
                            onClick = {
                                onToolToggle(tool.id, !isEnabled)
                            }
                        )
                    }

                    // Divider between integration groups (except for the last one)
                    if (index < groupedTools.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = OnSurfaceVariant.copy(alpha = 0.12f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Get the integration title for a tool ID
 */
private fun getIntegrationTitle(toolId: String): String {
    return when {
        toolId.startsWith("github_") -> "GitHub"
        toolId.startsWith("gmail_") -> "Gmail"
        toolId.startsWith("calendar_") -> "Calendar"
        toolId.startsWith("drive_") -> "Drive"
        toolId == "get_date_time" -> "Built-in Tools"
        toolId == "get_current_group_conversations" -> "Built-in Tools"
        else -> "Other"
    }
}
