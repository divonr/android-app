package com.example.ApI.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ApI.data.model.*
import com.example.ApI.ui.theme.*

/**
 * Popup composable for thinking budget control.
 * Shows either a discrete dropdown or continuous slider based on the budget type.
 */
@Composable
fun ThinkingBudgetPopup(
    visible: Boolean,
    budgetType: ThinkingBudgetType,
    currentValue: ThinkingBudgetValue,
    onValueChange: (ThinkingBudgetValue) -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = visible,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Surface, RoundedCornerShape(12.dp))
    ) {
        when (budgetType) {
            is ThinkingBudgetType.Discrete -> {
                // Discrete options dropdown
                DiscreteThinkingBudgetContent(
                    options = budgetType.options,
                    displayNames = budgetType.displayNames,
                    currentLevel = (currentValue as? ThinkingBudgetValue.Effort)?.level ?: budgetType.default,
                    onOptionSelected = { level ->
                        onValueChange(ThinkingBudgetValue.Effort(level))
                        onDismiss()
                    }
                )
            }
            is ThinkingBudgetType.Continuous -> {
                // Continuous slider
                ContinuousThinkingBudgetContent(
                    minTokens = budgetType.minTokens,
                    maxTokens = budgetType.maxTokens,
                    step = budgetType.step,
                    supportsOff = budgetType.supportsOff,
                    currentTokens = (currentValue as? ThinkingBudgetValue.Tokens)?.count ?: budgetType.default,
                    onTokensChange = { tokens ->
                        onValueChange(ThinkingBudgetValue.Tokens(tokens))
                    }
                )
            }
            ThinkingBudgetType.NotSupported, ThinkingBudgetType.InDevelopment -> {
                // Should not be shown, but handle gracefully
            }
        }
    }
}

/**
 * Content for discrete thinking budget options (dropdown menu items).
 */
@Composable
private fun DiscreteThinkingBudgetContent(
    options: List<String>,
    displayNames: Map<String, String>,
    currentLevel: String,
    onOptionSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        // Header
        Text(
            text = "עוצמת חשיבה",
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        options.forEach { option ->
            val displayName = displayNames[option] ?: option
            val isSelected = option == currentLevel

            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) Primary else OnSurface,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                onClick = { onOptionSelected(option) },
                modifier = Modifier.background(
                    if (isSelected) Primary.copy(alpha = 0.08f) else Color.Transparent
                )
            )
        }
    }
}

/**
 * Content for continuous thinking budget slider.
 * @param supportsOff If true, allows 0 value with a "jump" from 0 to minTokens
 */
@Composable
private fun ContinuousThinkingBudgetContent(
    minTokens: Int,
    maxTokens: Int,
    step: Int,
    supportsOff: Boolean,
    currentTokens: Int,
    onTokensChange: (Int) -> Unit
) {
    var sliderValue by remember(currentTokens) { mutableFloatStateOf(currentTokens.toFloat()) }

    // Calculate effective range
    val effectiveMin = if (supportsOff) 0f else minTokens.toFloat()
    // Threshold for jumping between 0 and minTokens (midpoint)
    val jumpThreshold = if (supportsOff) minTokens / 2f else 0f

    Column(
        modifier = Modifier
            .width(280.dp)
            .padding(16.dp)
    ) {
        // Header with current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "תקציב חשיבה",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant
            )

            // Display current value
            val displayValue = sliderValue.toInt()
            val isOff = displayValue == 0
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isOff) SurfaceVariant else Primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = if (isOff) "כבוי" else formatTokenCount(displayValue),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isOff) OnSurfaceVariant else Primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Slider
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                if (supportsOff) {
                    // Handle jump logic: if value is in the "dead zone" between 0 and minTokens
                    sliderValue = when {
                        newValue <= jumpThreshold -> 0f // Snap to 0 (off)
                        newValue < minTokens -> minTokens.toFloat() // Jump to minTokens
                        else -> {
                            // Snap to step values within normal range
                            val snappedValue = (newValue / step).toInt() * step
                            snappedValue.toFloat().coerceIn(minTokens.toFloat(), maxTokens.toFloat())
                        }
                    }
                } else {
                    // Normal snapping without off support
                    val snappedValue = (newValue / step).toInt() * step
                    sliderValue = snappedValue.toFloat().coerceIn(minTokens.toFloat(), maxTokens.toFloat())
                }
            },
            onValueChangeFinished = {
                onTokensChange(sliderValue.toInt())
            },
            valueRange = effectiveMin..maxTokens.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = if (sliderValue == 0f) OnSurfaceVariant else Primary,
                activeTrackColor = if (sliderValue == 0f) OnSurfaceVariant.copy(alpha = 0.5f) else Primary,
                inactiveTrackColor = Primary.copy(alpha = 0.24f)
            )
        )

        // Min/Max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (supportsOff) "כבוי" else formatTokenCount(minTokens),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = formatTokenCount(maxTokens),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Format token count for display (e.g., 1024 -> "1K", 128000 -> "128K").
 */
private fun formatTokenCount(tokens: Int): String {
    return when {
        tokens >= 1000 -> "${tokens / 1000}K"
        else -> tokens.toString()
    }
}

/**
 * Temperature control popup with slider.
 * The slider appears attached to the thermometer icon.
 */
@Composable
fun TemperaturePopup(
    visible: Boolean,
    config: TemperatureConfig?,
    currentValue: Float?,
    onValueChange: (Float?) -> Unit,
    onDismiss: () -> Unit
) {
    if (config == null) return

    DropdownMenu(
        expanded = visible,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Surface, RoundedCornerShape(12.dp))
    ) {
        TemperatureSliderContent(
            min = config.min,
            max = config.max,
            step = config.step,
            defaultValue = config.default,
            currentValue = currentValue,
            onValueChange = onValueChange
        )
    }
}

/**
 * Content for temperature slider popup.
 */
@Composable
private fun TemperatureSliderContent(
    min: Float,
    max: Float,
    step: Float,
    defaultValue: Float?,
    currentValue: Float?,
    onValueChange: (Float?) -> Unit
) {
    // Use current value or default, or midpoint if no default
    val effectiveValue = currentValue ?: defaultValue ?: ((min + max) / 2)
    var sliderValue by remember(effectiveValue) { mutableFloatStateOf(effectiveValue) }

    Column(
        modifier = Modifier
            .width(260.dp)
            .padding(16.dp)
    ) {
        // Header with current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "טמפרטורה",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceVariant
            )

            // Display current value
            val displayValue = if (currentValue == null) {
                "ברירת מחדל"
            } else {
                TemperatureConfigUtils.formatValueForDisplay(sliderValue)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (currentValue == null) SurfaceVariant else Primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (currentValue == null) OnSurfaceVariant else Primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Slider
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                // Snap to step values
                val steps = ((newValue - min) / step).toInt()
                sliderValue = (min + steps * step).coerceIn(min, max)
            },
            onValueChangeFinished = {
                onValueChange(sliderValue)
            },
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = Primary,
                activeTrackColor = Primary,
                inactiveTrackColor = Primary.copy(alpha = 0.24f)
            )
        )

        // Min/Max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = TemperatureConfigUtils.formatValueForDisplay(min),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = TemperatureConfigUtils.formatValueForDisplay(max),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Reset to default button (if default is available)
        if (defaultValue != null && currentValue != null) {
            TextButton(
                onClick = { onValueChange(null) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "איפוס לברירת מחדל",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary
                )
            }
        }
    }
}
