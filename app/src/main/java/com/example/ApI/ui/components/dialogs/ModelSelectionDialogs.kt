package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.theme.*

@Composable
fun ProviderSelectorDialog(
    providers: List<Provider>,
    onProviderSelected: (Provider) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 300.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "בחירת ספק API",
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface,
                            fontWeight = FontWeight.Bold
                        )

                        if (onRefresh != null) {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh models",
                                    tint = Primary
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp)
                    ) {
                        items(providers) { provider ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onProviderSelected(provider) },
                                color = Surface
                            ) {
                                Text(
                                    text = stringResource(id = when(provider.provider) {
                                        "openai" -> R.string.provider_openai
                                        "poe" -> R.string.provider_poe
                                        "google" -> R.string.provider_google
                                        "anthropic" -> R.string.provider_anthropic
                                        "cohere" -> R.string.provider_cohere
                                        "openrouter" -> R.string.provider_openrouter
                                        else -> R.string.provider_openai
                                    }),
                                    color = OnSurface,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth()
                                )
                            }
                            HorizontalDivider(color = OnSurface.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorDialog(
    models: List<Model>,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var customModelName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Surface
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .widthIn(max = 320.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "בחירת מודל",
                            style = MaterialTheme.typography.headlineSmall,
                            color = OnSurface,
                            fontWeight = FontWeight.Bold
                        )

                        if (onRefresh != null) {
                            IconButton(
                                onClick = onRefresh,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh models",
                                    tint = Primary
                                )
                            }
                        }
                    }

                    // Custom model input section
                    Text(
                        text = "או הכנס שם מדויק:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    OutlinedTextField(
                        value = customModelName,
                        onValueChange = { customModelName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = "הכנס שם מדויק...",
                                color = OnSurface.copy(alpha = 0.7f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = OnSurface.copy(alpha = 0.3f),
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface
                        ),
                        singleLine = true
                    )

                    // Use custom model button
                    if (customModelName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onModelSelected(customModelName.trim()) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Primary,
                                contentColor = OnPrimary
                            )
                        ) {
                            Text("Use: ${customModelName.trim()}")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Divider
                    HorizontalDivider(color = OnSurface.copy(alpha = 0.3f))

                    Spacer(modifier = Modifier.height(8.dp))

                    // Available models section
                    Text(
                        text = "מודלים זמינים:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp)
                    ) {
                        items(models) { model ->
                            val modelName = model.name ?: model.toString()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onModelSelected(modelName) },
                                color = Surface
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    Text(
                                        text = modelName,
                                        color = OnSurface,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    // Display pricing information for Poe models
                                    model.pricing?.let { pricing ->
                                        PricingText(pricing)
                                    } ?: run {
                                        // Fallback to legacy min_points if no pricing object
                                        model.min_points?.let { points ->
                                            Text(
                                                text = "Min points: $points",
                                                color = OnSurface.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = OnSurface.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Displays pricing information for Poe models.
 * Supports three pricing types:
 * - Fixed pricing: exact points per message
 * - Token-based pricing: points per 1k input/output tokens
 * - Legacy pricing: minimum points (deprecated)
 */
@Composable
private fun PricingText(pricing: PoePricing) {
    when {
        // Fixed pricing (exact points per message)
        pricing.isFixedPricing -> {
            Text(
                text = "Points: ${pricing.points}",
                color = OnSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Token-based pricing (points per 1k tokens)
        pricing.isTokenBasedPricing -> {
            Column {
                pricing.input_points_per_1k?.let { inputPoints ->
                    Text(
                        text = "Input: ${formatPoints(inputPoints)} pts/1k tokens",
                        color = OnSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                pricing.output_points_per_1k?.let { outputPoints ->
                    Text(
                        text = "Output: ${formatPoints(outputPoints)} pts/1k tokens",
                        color = OnSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        // Legacy pricing (min_points)
        pricing.isLegacyPricing -> {
            Text(
                text = "Min points: ${pricing.min_points}",
                color = OnSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Formats points value, removing unnecessary decimal places
 */
private fun formatPoints(points: Double): String {
    return if (points == points.toLong().toDouble()) {
        points.toLong().toString()
    } else {
        String.format("%.2f", points)
    }
}
