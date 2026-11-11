package com.example.ApI.ui.components.emoji

import android.content.res.Configuration
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.example.ApI.R
import com.example.ApI.ui.theme.MessageBorder
import com.example.ApI.ui.theme.OnSurface
import com.example.ApI.ui.theme.OnSurfaceVariant
import com.example.ApI.ui.theme.Primary
import com.example.ApI.ui.theme.Surface
import com.example.ApI.ui.theme.SurfaceVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What is shown under the composer in place of (or along with) the system keyboard. */
enum class EmojiKeyboardMode {
    /** Nothing of the emoji keyboard: the system keyboard, or no keyboard. */
    Closed,

    /** The emoji panel, where the system keyboard would be. */
    Emoji,

    /** The emoji search strip, above the system keyboard that types the query. */
    Search
}

@Stable
class EmojiKeyboardState {
    var mode by mutableStateOf(EmojiKeyboardMode.Closed)

    /**
     * Keeps the panel's space reserved (and the panel drawn) after it was asked to give way to
     * the system keyboard, until that keyboard has finished rising over it — so the composer
     * stays where it is during the swap.
     */
    var holdSpace by mutableStateOf(false)

    val isOpen: Boolean get() = mode != EmojiKeyboardMode.Closed

    /** Hands the bottom of the screen over to the system keyboard, which the caller brings up. */
    fun switchToSystemKeyboard() {
        holdSpace = true
        mode = EmojiKeyboardMode.Closed
    }
}

/**
 * The area under the composer. It is as tall as the system keyboard when that is showing (the
 * chat screen draws edge to edge and does not pan or resize by itself), and as tall as the
 * system keyboard last was when the emoji panel is showing instead, so the composer does not
 * move when the two swap.
 *
 * @param onEmojiPicked insert this emoji at the composer's cursor
 * @param onBackspace delete the character before the composer's cursor
 * @param onFocusComposer give the composer the focus, without bringing the system keyboard up
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiKeyboardHost(
    state: EmojiKeyboardState,
    onEmojiPicked: (String) -> Unit,
    onBackspace: () -> Unit,
    onFocusComposer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val store = remember(context) { EmojiStore.get(context) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // The screen's own padding already covers the navigation bar, which the IME inset includes
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val imeHeight = (WindowInsets.ime.getBottom(density) - navigationBottom).coerceAtLeast(0)
    val imeTargetHeight =
        (WindowInsets.imeAnimationTarget.getBottom(density) - navigationBottom).coerceAtLeast(0)
    val currentImeHeight by rememberUpdatedState(imeHeight)
    val currentImeTargetHeight by rememberUpdatedState(imeTargetHeight)

    // Remember how tall the system keyboard is, per orientation. Floating and split keyboards
    // report next to nothing and are ignored.
    var savedImeHeight by remember(landscape) { mutableIntStateOf(store.imeHeight(landscape)) }
    val minKeyboardPx = with(density) { MIN_KEYBOARD_HEIGHT.roundToPx() }
    LaunchedEffect(imeTargetHeight, landscape) {
        if (imeTargetHeight >= minKeyboardPx && imeTargetHeight != savedImeHeight) {
            savedImeHeight = imeTargetHeight
            store.saveImeHeight(landscape, imeTargetHeight)
        }
    }

    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val panelHeight = run {
        val fallback = if (landscape) {
            (screenHeightPx * 0.5f).toInt()
        } else {
            (screenHeightPx * 0.38f).toInt().coerceIn(
                with(density) { 240.dp.roundToPx() },
                with(density) { 320.dp.roundToPx() }
            )
        }
        val wanted = if (savedImeHeight > 0) savedImeHeight else fallback
        // Always leave room for the composer and some of the conversation above the panel
        wanted.coerceAtMost((screenHeightPx * 0.65f).toInt()).coerceAtLeast(minKeyboardPx)
    }

    // The panel gives way once the system keyboard has risen to its full height
    LaunchedEffect(state.holdSpace) {
        if (state.holdSpace) {
            withTimeoutOrNull(HOLD_SPACE_TIMEOUT_MS) {
                snapshotFlow { currentImeHeight to currentImeTargetHeight }
                    .first { (height, target) -> height > 0 && height >= target }
            }
            state.holdSpace = false
        }
    }

    LaunchedEffect(state.mode) {
        when (state.mode) {
            EmojiKeyboardMode.Emoji -> {
                // The system keyboard is on its way down. Should it come up again by any other
                // road while the panel is open, the panel yields to it.
                val hidden = withTimeoutOrNull(IME_SETTLE_TIMEOUT_MS) {
                    snapshotFlow { currentImeHeight }.first { it == 0 }
                }
                if (hidden == null) {
                    state.mode = EmojiKeyboardMode.Closed
                } else {
                    snapshotFlow { currentImeHeight }.first { it > 0 }
                    state.switchToSystemKeyboard()
                }
            }
            EmojiKeyboardMode.Search -> {
                // Dismissing the system keyboard (its own back or hide key) ends the search
                withTimeoutOrNull(IME_SETTLE_TIMEOUT_MS) {
                    snapshotFlow { currentImeHeight }.first { it > 0 }
                }
                snapshotFlow { currentImeHeight }.first { it == 0 }
                state.mode = EmojiKeyboardMode.Emoji
                onFocusComposer()
            }
            EmojiKeyboardMode.Closed -> Unit
        }
    }

    val showPanel = state.mode == EmojiKeyboardMode.Emoji || state.holdSpace
    // Opening over a showing system keyboard takes its space at once (the keyboard then slides
    // away over the panel); with no keyboard up, the space opens and closes with a short slide.
    val reserved by animateIntAsState(
        targetValue = if (showPanel) panelHeight else 0,
        animationSpec = if (showPanel && imeHeight > 0) snap() else tween(durationMillis = 180),
        label = "emojiPanelSpace"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        if (state.mode == EmojiKeyboardMode.Search) {
            EmojiSearchStrip(
                store = store,
                onEmojiPicked = onEmojiPicked,
                onClose = {
                    state.mode = EmojiKeyboardMode.Emoji
                    onFocusComposer()
                }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { maxOf(imeHeight, reserved).toDp() })
                .clipToBounds()
                .background(SurfaceVariant)
        ) {
            if (showPanel || reserved > 0) {
                EmojiPanel(
                    store = store,
                    onEmojiPicked = onEmojiPicked,
                    onBackspace = onBackspace,
                    onSearch = {
                        // The panel's space stays until the keyboard for the query has risen
                        state.holdSpace = true
                        state.mode = EmojiKeyboardMode.Search
                    },
                    // A fixed height, revealed from the top: the grid is not re-laid out while
                    // the space opens and closes
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .requiredHeight(with(density) { panelHeight.toDp() })
                )
            }
        }
    }
}

private val MIN_KEYBOARD_HEIGHT = 160.dp
private const val HOLD_SPACE_TIMEOUT_MS = 700L
private const val IME_SETTLE_TIMEOUT_MS = 1500L

// ─── The panel ───────────────────────────────────────────────────────────────

private sealed interface GridEntry {
    val key: String

    class Header(val section: EmojiSection) : GridEntry {
        override val key = "header:${section.name}"
    }

    class Cell(val section: EmojiSection, val item: EmojiItem?, val fixedForm: String?) : GridEntry {
        override val key = "${section.name}:${fixedForm ?: item?.emoji}"
    }
}

private class GridModel(
    val entries: List<GridEntry>,
    /** The sections present, each with the index of its header in [entries]. */
    val sections: List<Pair<EmojiSection, Int>>
) {
    fun sectionAt(index: Int): EmojiSection? =
        sections.lastOrNull { it.second <= index }?.first ?: sections.firstOrNull()?.first
}

private fun buildGridModel(categories: List<EmojiCategory>, recents: List<String>): GridModel {
    val entries = ArrayList<GridEntry>()
    val sections = ArrayList<Pair<EmojiSection, Int>>()
    if (recents.isNotEmpty()) {
        sections.add(EmojiSection.RECENTS to entries.size)
        entries.add(GridEntry.Header(EmojiSection.RECENTS))
        recents.forEach { entries.add(GridEntry.Cell(EmojiSection.RECENTS, null, it)) }
    }
    categories.forEach { category ->
        sections.add(category.section to entries.size)
        entries.add(GridEntry.Header(category.section))
        category.emojis.forEach { entries.add(GridEntry.Cell(category.section, it, null)) }
    }
    return GridModel(entries, sections)
}

/**
 * The emoji keyboard: a row of category tabs between the search and backspace keys, over one
 * continuous grid of every emoji, recently used first.
 */
@Composable
private fun EmojiPanel(
    store: EmojiStore,
    onEmojiPicked: (String) -> Unit,
    onBackspace: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories by produceState<List<EmojiCategory>?>(initialValue = null, store) {
        value = store.categories()
    }
    // The recents shown are the ones from when the panel opened: the grid does not shift under
    // the finger as emojis are picked
    val recentsAtOpen = remember(categories == null) {
        Snapshot.withoutReadObservation {
            store.recents.filter { categories == null || store.itemOf(it) != null }
        }
    }
    val model = remember(categories, recentsAtOpen) {
        categories?.let { buildGridModel(it, recentsAtOpen) }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val activeSection by remember(model) {
        derivedStateOf { model?.sectionAt(gridState.firstVisibleItemIndex) }
    }
    /** The grid entry whose skin-tone popup is open. */
    var variantsFor by remember { mutableStateOf<String?>(null) }

    val pick: (String) -> Unit = { emoji ->
        store.addRecent(emoji)
        onEmojiPicked(emoji)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PanelKey(
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.emoji_search),
                onClick = onSearch
            )
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                model?.sections?.forEach { (section, startIndex) ->
                    CategoryTab(
                        section = section,
                        active = section == activeSection,
                        onClick = {
                            variantsFor = null
                            scope.launch { gridState.scrollToItem(startIndex) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            BackspaceKey(onBackspace = onBackspace)
        }

        if (model == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 40.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)
            ) {
                items(
                    count = model.entries.size,
                    key = { model.entries[it].key },
                    span = { index ->
                        if (model.entries[index] is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    },
                    contentType = { if (model.entries[it] is GridEntry.Header) 0 else 1 }
                ) { index ->
                    when (val entry = model.entries[index]) {
                        is GridEntry.Header -> Text(
                            text = stringResource(entry.section.titleRes),
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 4.dp)
                        )
                        is GridEntry.Cell -> {
                            val item = entry.item ?: entry.fixedForm?.let { store.itemOf(it) }
                            val shown = entry.fixedForm ?: item?.let { store.preferredForm(it) } ?: return@items
                            EmojiCell(
                                emoji = shown,
                                onClick = {
                                    variantsFor = null
                                    pick(shown)
                                },
                                onLongClick = if (item != null && item.variants.isNotEmpty()) {
                                    { variantsFor = entry.key }
                                } else null
                            ) {
                                if (variantsFor == entry.key && item != null) {
                                    SkinTonePopup(
                                        item = item,
                                        onDismiss = { variantsFor = null },
                                        onChosen = { chosen ->
                                            variantsFor = null
                                            store.rememberTone(item, chosen)
                                            pick(chosen)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = OnSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun CategoryTab(
    section: EmojiSection,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (active) Primary.copy(alpha = 0.18f) else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = stringResource(section.titleRes),
                tint = if (active) Primary else OnSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Deletes once when pressed, then keeps deleting for as long as it is held. */
@Composable
private fun BackspaceKey(onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val currentOnBackspace by rememberUpdatedState(onBackspace)
    LaunchedEffect(pressed) {
        if (pressed) {
            currentOnBackspace()
            delay(400)
            while (true) {
                currentOnBackspace()
                delay(60)
            }
        }
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Backspace,
            contentDescription = stringResource(R.string.emoji_backspace),
            tint = OnSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(
    emoji: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = 24.sp, textAlign = TextAlign.Center, maxLines = 1)
        overlay()
    }
}

/** The row of skin tones of one emoji, floating above its cell. */
@Composable
private fun SkinTonePopup(
    item: EmojiItem,
    onDismiss: () -> Unit,
    onChosen: (String) -> Unit
) {
    val marginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(marginPx) { AboveAnchorPositionProvider(marginPx) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        // Not focusable: the composer keeps its focus and cursor while the popup is up
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true)
    ) {
        // Tones always run light to dark, left to right
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Surface,
                shadowElevation = 8.dp,
                modifier = Modifier.border(1.dp, MessageBorder, RoundedCornerShape(14.dp))
            ) {
                Row(modifier = Modifier.padding(4.dp)) {
                    (listOf(item.emoji) + item.variants).forEach { form ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onChosen(form) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = form, fontSize = 26.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Centred above the anchor, kept inside the window; below it when there is no room above. */
private class AboveAnchorPositionProvider(private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val centred = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = centred.coerceIn(marginPx, maxX)
        val above = anchorBounds.top - popupContentSize.height - marginPx / 2
        val y = if (above >= 0) above else anchorBounds.bottom + marginPx / 2
        return IntOffset(x, y)
    }
}

// ─── Search ──────────────────────────────────────────────────────────────────

/**
 * Emoji search, WhatsApp style: a row of results over a search field, sitting on top of the
 * system keyboard that types the query. Picked results go to the composer.
 */
@Composable
private fun EmojiSearchStrip(
    store: EmojiStore,
    onEmojiPicked: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val categories by produceState<List<EmojiCategory>?>(initialValue = null, store) {
        value = store.categories()
    }
    val recentsAtOpen = remember { Snapshot.withoutReadObservation { store.recents } }
    val results: List<String> = remember(query, categories) {
        if (query.isBlank()) recentsAtOpen
        else store.search(query.trim()).map { store.preferredForm(it) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (results.isEmpty() && query.isNotBlank() && categories != null) {
                Text(
                    text = stringResource(R.string.emoji_search_no_results),
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(results, key = { it }) { emoji ->
                        EmojiCell(
                            emoji = emoji,
                            onClick = {
                                store.addRecent(emoji)
                                onEmojiPicked(emoji)
                            },
                            onLongClick = null,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PanelKey(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.emoji_search_close),
                onClick = onClose
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Surface,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = OnSurface),
                        cursorBrush = SolidColor(Primary),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Search
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.emoji_search_hint),
                                        color = OnSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable { query = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.emoji_search_clear),
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
