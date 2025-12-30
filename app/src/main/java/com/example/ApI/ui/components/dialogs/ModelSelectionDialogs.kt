package com.example.ApI.ui.components.dialogs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ApI.R
import com.example.ApI.data.model.*
import com.example.ApI.ui.theme.*
import kotlinx.coroutines.launch

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

/**
 * Model selection dialog with provider tabs.
 * Shows tabs for all available providers (those with active API keys).
 * User can switch between providers by tapping tabs or swiping.
 *
 * NOTE: Switching tabs does NOT change the selected provider - only browsing.
 * The provider is only changed when a model is actually selected.
 * If dismissed without selection, the original provider/model remain unchanged.
 *
 * @param availableProviders List of providers with active API keys
 * @param currentProvider The currently selected provider (determines initial tab)
 * @param onModelSelected Callback when a model is selected, receives provider and model name
 * @param onDismiss Callback when dialog is dismissed without selection
 * @param onRefresh Optional callback to refresh the model list
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelSelectorDialog(
    availableProviders: List<Provider>,
    currentProvider: Provider?,
    onModelSelected: (Provider, String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var customModelName by remember { mutableStateOf("") }
    var showPricing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Find the initial page index based on current provider
    val initialPageIndex = remember(currentProvider, availableProviders) {
        availableProviders.indexOfFirst { it.provider == currentProvider?.provider }
            .takeIf { it >= 0 } ?: 0
    }

    // Pager state for swiping between providers
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { availableProviders.size }
    )

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

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pricing toggle button
                            IconButton(
                                onClick = { showPricing = !showPricing },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AttachMoney,
                                    contentDescription = if (showPricing) "Hide pricing" else "Show pricing",
                                    tint = if (showPricing) Primary else OnSurface.copy(alpha = 0.5f)
                                )
                            }

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

                    // Use custom model button - uses the currently viewed provider
                    if (customModelName.isNotBlank() && availableProviders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val viewedProvider = availableProviders.getOrNull(pagerState.currentPage)
                        Button(
                            onClick = {
                                viewedProvider?.let { provider ->
                                    onModelSelected(provider, customModelName.trim())
                                }
                            },
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

                    // Provider tabs - only show if there are providers
                    if (availableProviders.isNotEmpty()) {
                        // RTL TabRow - we need to reverse the direction for proper RTL swiping
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            ScrollableTabRow(
                                selectedTabIndex = pagerState.currentPage,
                                containerColor = Surface,
                                contentColor = Primary,
                                edgePadding = 0.dp,
                                divider = {}
                            ) {
                                availableProviders.forEachIndexed { index, provider ->
                                    Tab(
                                        selected = pagerState.currentPage == index,
                                        onClick = {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(index)
                                            }
                                        },
                                        text = {
                                            Text(
                                                text = getProviderDisplayName(provider.provider),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        selectedContentColor = Primary,
                                        unselectedContentColor = OnSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // HorizontalPager for swipeable model lists
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.heightIn(max = 300.dp)
                            ) { pageIndex ->
                                val provider = availableProviders[pageIndex]
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                    ModelListForProvider(
                                        provider = provider,
                                        showPricing = showPricing,
                                        onModelSelected = { modelName ->
                                            onModelSelected(provider, modelName)
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // No providers available
                        Text(
                            text = "אין ספקים זמינים. הוסף מפתח API בהגדרות.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Display name for provider in tabs
 */
@Composable
private fun getProviderDisplayName(providerKey: String): String {
    return stringResource(id = when(providerKey) {
        "openai" -> R.string.provider_openai
        "poe" -> R.string.provider_poe
        "google" -> R.string.provider_google
        "anthropic" -> R.string.provider_anthropic
        "cohere" -> R.string.provider_cohere
        "openrouter" -> R.string.provider_openrouter
        else -> R.string.provider_openai
    })
}

/**
 * Model list for a single provider page
 */
@Composable
private fun ModelListForProvider(
    provider: Provider,
    showPricing: Boolean,
    onModelSelected: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxHeight()
    ) {
        items(provider.models) { model ->
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
                    // Display pricing information when toggle is enabled
                    if (showPricing) {
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
            }
            HorizontalDivider(color = OnSurface.copy(alpha = 0.2f))
        }
    }
}

/**
 * Displays pricing information for models.
 * Supports pricing types:
 * - USD pricing: price per 1k input/output tokens
 * - Fixed pricing: exact points per message (Poe)
 * - Token-based pricing: points per 1k input/output tokens (Poe)
 * - Legacy pricing: minimum points (deprecated)
 */
@Composable
private fun PricingText(pricing: ModelPricing) {
    when {
        // USD pricing (price per 1k tokens)
        pricing.hasUsdPricing -> {
            Column {
                pricing.input_price_per_1k?.let { inputPrice ->
                    Text(
                        text = "Input: ${formatPrice(inputPrice)}/1k tokens",
                        color = OnSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                pricing.output_price_per_1k?.let { outputPrice ->
                    Text(
                        text = "Output: ${formatPrice(outputPrice)}/1k tokens",
                        color = OnSurface.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        // Fixed pricing (exact points per message)
        pricing.isFixedPricing -> {
            Text(
                text = "Points: ${pricing.points}",
                color = OnSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Token-based pricing (points per 1k tokens - Poe)
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
 * Formats USD price value with dollar sign
 */
private fun formatPrice(price: Double): String {
    return if (price < 0.001) {
        String.format("$%.5f", price)
    } else if (price < 0.01) {
        String.format("$%.4f", price)
    } else if (price == price.toLong().toDouble()) {
        String.format("$%.0f", price)
    } else {
        String.format("$%.4f", price)
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
