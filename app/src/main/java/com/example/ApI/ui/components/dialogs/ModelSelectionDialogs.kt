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
import androidx.compose.material.icons.automirrored.filled.Sort
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
import com.example.ApI.data.model.getDisplayNameFromProviderKey
import com.example.ApI.data.model.isCustomProvider
import com.example.ApI.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Sort options for model list
 */
enum class ModelSortOption {
    RECOMMENDED,  // Default order from the JSON file
    BY_PRICE,     // Sort by price (cheapest first)
    NEWEST_FIRST  // Sort by release date (newest first)
}

/**
 * Model selection dialog with provider tabs and favorites.
 * Shows tabs for all available providers (those with active API keys),
 * plus a star/favorites tab as the first tab.
 * User can switch between providers by tapping tabs or swiping.
 *
 * NOTE: Switching tabs does NOT change the selected provider - only browsing.
 * The provider is only changed when a model is actually selected.
 * If dismissed without selection, the original provider/model remain unchanged.
 *
 * @param availableProviders List of providers with active API keys
 * @param currentProvider The currently selected provider (determines initial tab)
 * @param starredModels List of starred/favorite models
 * @param onModelSelected Callback when a model is selected, receives provider and model name
 * @param onToggleStar Callback when star icon is clicked, receives provider key and model name
 * @param onDismiss Callback when dialog is dismissed without selection
 * @param onRefresh Optional callback to refresh the model list
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ModelSelectorDialog(
    availableProviders: List<Provider>,
    currentProvider: Provider?,
    starredModels: List<StarredModel>,
    onModelSelected: (Provider, String) -> Unit,
    onToggleStar: (String, String) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: (() -> Unit)? = null
) {
    var customModelName by remember { mutableStateOf("") }
    var showPricing by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(ModelSortOption.RECOMMENDED) }
    var showSortMenu by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Total pages = star tab (1) + providers
    val totalPages = availableProviders.size + 1

    // Find the initial page index based on current provider
    // Page 0 = star tab, Page 1+ = providers
    val initialPageIndex = remember(currentProvider, availableProviders) {
        if (currentProvider == null) 0
        else {
            val providerIndex = availableProviders.indexOfFirst { it.provider == currentProvider.provider }
            if (providerIndex >= 0) providerIndex + 1 else 1
        }
    }

    // Pager state for swiping between providers
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { totalPages }
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

                            // Sort button with dropdown menu
                            Box {
                                IconButton(
                                    onClick = { showSortMenu = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = "Sort models",
                                        tint = if (sortOption != ModelSortOption.RECOMMENDED) Primary else OnSurface.copy(alpha = 0.5f)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("לפי מחיר") },
                                        onClick = {
                                            sortOption = ModelSortOption.BY_PRICE
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (sortOption == ModelSortOption.BY_PRICE) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Primary)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("מהחדש לישן") },
                                        onClick = {
                                            sortOption = ModelSortOption.NEWEST_FIRST
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (sortOption == ModelSortOption.NEWEST_FIRST) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Primary)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("מומלץ") },
                                        onClick = {
                                            sortOption = ModelSortOption.RECOMMENDED
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (sortOption == ModelSortOption.RECOMMENDED) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Primary)
                                            }
                                        }
                                    )
                                }
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

                    // Use custom model button - uses the currently viewed provider (not star tab)
                    if (customModelName.isNotBlank() && availableProviders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        // If on star tab (page 0), use first provider; otherwise use current provider
                        val viewedProvider = if (pagerState.currentPage == 0) {
                            availableProviders.firstOrNull()
                        } else {
                            availableProviders.getOrNull(pagerState.currentPage - 1)
                        }
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
                        // Tabs in RTL order (star first on right, then providers)
                        ScrollableTabRow(
                            selectedTabIndex = pagerState.currentPage,
                            containerColor = Surface,
                            contentColor = Primary,
                            edgePadding = 0.dp,
                            divider = {}
                        ) {
                            // Star/favorites tab (first = rightmost in RTL)
                            Tab(
                                selected = pagerState.currentPage == 0,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(0)
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (starredModels.isNotEmpty()) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = "מועדפים",
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                selectedContentColor = Primary,
                                unselectedContentColor = OnSurface.copy(alpha = 0.6f)
                            )

                            // Provider tabs
                            availableProviders.forEachIndexed { index, provider ->
                                Tab(
                                    selected = pagerState.currentPage == index + 1,
                                    onClick = {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(index + 1)
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

                        Spacer(modifier = Modifier.height(8.dp))

                        // HorizontalPager for swipeable model lists
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.heightIn(max = 300.dp)
                        ) { pageIndex ->
                            if (pageIndex == 0) {
                                // Star/favorites page
                                StarredModelsPage(
                                    starredModels = starredModels,
                                    availableProviders = availableProviders,
                                    showPricing = showPricing,
                                    onModelSelected = onModelSelected,
                                    onToggleStar = onToggleStar
                                )
                            } else {
                                // Provider page
                                val provider = availableProviders[pageIndex - 1]
                                ModelListForProvider(
                                    provider = provider,
                                    showPricing = showPricing,
                                    starredModels = starredModels,
                                    sortOption = sortOption,
                                    onModelSelected = { modelName ->
                                        onModelSelected(provider, modelName)
                                    },
                                    onToggleStar = { modelName ->
                                        onToggleStar(provider.provider, modelName)
                                    }
                                )
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
 * Empty state / starred models page
 */
@Composable
private fun StarredModelsPage(
    starredModels: List<StarredModel>,
    availableProviders: List<Provider>,
    showPricing: Boolean,
    onModelSelected: (Provider, String) -> Unit,
    onToggleStar: (String, String) -> Unit
) {
    if (starredModels.isEmpty()) {
        // Empty state
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "גישה מהירה",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "כאן יופיעו המודלים ששמרת בכוכב",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface.copy(alpha = 0.7f)
            )
        }
    } else {
        // List of starred models
        LazyColumn(
            modifier = Modifier.fillMaxHeight()
        ) {
            items(starredModels) { starred ->
                // Find the provider and model for this starred item
                val provider = availableProviders.find { it.provider == starred.provider }
                val model = provider?.models?.find { it.name == starred.modelName }

                if (provider != null) {
                    val modelName = starred.modelName
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModelSelected(provider, modelName) },
                        color = Surface
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = modelName,
                                    color = OnSurface,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                // Show provider name for starred models
                                Text(
                                    text = getProviderDisplayName(starred.provider),
                                    color = OnSurface.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                // Display pricing information when toggle is enabled
                                if (showPricing && model != null) {
                                    model.pricing?.let { pricing ->
                                        PricingText(pricing)
                                    } ?: run {
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
                            // Star icon (always filled for starred items)
                            IconButton(
                                onClick = { onToggleStar(starred.provider, modelName) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "הסר ממועדפים",
                                    tint = Primary
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = OnSurface.copy(alpha = 0.2f))
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
    return if (isCustomProvider(providerKey)) {
        getDisplayNameFromProviderKey(providerKey)
    } else {
        stringResource(id = when(providerKey) {
            "openai" -> R.string.provider_openai
            "poe" -> R.string.provider_poe
            "google" -> R.string.provider_google
            "anthropic" -> R.string.provider_anthropic
            "cohere" -> R.string.provider_cohere
            "openrouter" -> R.string.provider_openrouter
            "llmstats" -> R.string.provider_llmstats
            else -> R.string.provider_default_name
        })
    }
}

/**
 * Compares two models by price.
 * Returns negative if a is cheaper, positive if b is cheaper, 0 if equal.
 * Output price is more important than input price in case of conflict.
 */
private fun compareByPrice(a: Model, b: Model): Int {
    val aPricing = a.pricing
    val bPricing = b.pricing

    // Get input prices (use points if USD not available)
    val aInput = aPricing?.input_price_per_1k ?: aPricing?.input_points_per_1k ?: Double.MAX_VALUE
    val bInput = bPricing?.input_price_per_1k ?: bPricing?.input_points_per_1k ?: Double.MAX_VALUE

    // Get output prices (use points if USD not available)
    val aOutput = aPricing?.output_price_per_1k ?: aPricing?.output_points_per_1k ?: Double.MAX_VALUE
    val bOutput = bPricing?.output_price_per_1k ?: bPricing?.output_points_per_1k ?: Double.MAX_VALUE

    // Compare: output is more important than input
    return when {
        aInput < bInput && aOutput < bOutput -> -1  // a is cheaper
        aInput > bInput && aOutput > bOutput -> 1   // b is cheaper
        aInput == bInput && aOutput < bOutput -> -1 // same input, a cheaper output
        aInput == bInput && aOutput > bOutput -> 1  // same input, b cheaper output
        aOutput < bOutput -> -1  // output wins in conflict: a cheaper output
        aOutput > bOutput -> 1   // output wins in conflict: b cheaper output
        else -> 0  // equal
    }
}

/**
 * Sorts the model list based on the selected sort option
 */
private fun sortModels(models: List<Model>, sortOption: ModelSortOption): List<Model> {
    return when (sortOption) {
        ModelSortOption.RECOMMENDED -> models  // Keep original order
        ModelSortOption.BY_PRICE -> models.sortedWith { a, b -> compareByPrice(a, b) }
        ModelSortOption.NEWEST_FIRST -> models.sortedByDescending { it.releaseOrder ?: 0 }
    }
}

/**
 * Model list for a single provider page
 */
@Composable
private fun ModelListForProvider(
    provider: Provider,
    showPricing: Boolean,
    starredModels: List<StarredModel>,
    sortOption: ModelSortOption,
    onModelSelected: (String) -> Unit,
    onToggleStar: (String) -> Unit
) {
    val sortedModels = remember(provider.models, sortOption) {
        sortModels(provider.models, sortOption)
    }

    LazyColumn(
        modifier = Modifier.fillMaxHeight()
    ) {
        items(sortedModels) { model ->
            val modelName = model.name ?: model.toString()
            val isStarred = starredModels.any {
                it.provider == provider.provider && it.modelName == modelName
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModelSelected(modelName) },
                color = Surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
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
                    // Star toggle icon
                    IconButton(
                        onClick = { onToggleStar(modelName) },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (isStarred) "הסר ממועדפים" else "הוסף למועדפים",
                            tint = if (isStarred) Primary else OnSurface.copy(alpha = 0.3f)
                        )
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
