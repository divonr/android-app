package com.example.ApI.ui.components.emoji

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Paint
import android.icu.text.BreakIterator
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiFlags
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.graphics.PaintCompat
import com.example.ApI.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The emoji keyboard's sections, in tab order. */
enum class EmojiSection(
    val assetId: String?,
    @StringRes val titleRes: Int,
    val icon: ImageVector
) {
    RECENTS(null, R.string.emoji_cat_recents, Icons.Outlined.Schedule),
    SMILEYS_PEOPLE("smileys_people", R.string.emoji_cat_smileys_people, Icons.Outlined.EmojiEmotions),
    ANIMALS_NATURE("animals_nature", R.string.emoji_cat_animals_nature, Icons.Outlined.Pets),
    FOOD_DRINK("food_drink", R.string.emoji_cat_food_drink, Icons.Outlined.EmojiFoodBeverage),
    ACTIVITIES("activities", R.string.emoji_cat_activities, Icons.Outlined.SportsSoccer),
    TRAVEL_PLACES("travel_places", R.string.emoji_cat_travel_places, Icons.Outlined.DirectionsCar),
    OBJECTS("objects", R.string.emoji_cat_objects, Icons.Outlined.EmojiObjects),
    SYMBOLS("symbols", R.string.emoji_cat_symbols, Icons.Outlined.EmojiSymbols),
    FLAGS("flags", R.string.emoji_cat_flags, Icons.Outlined.EmojiFlags)
}

/**
 * One emoji of the dataset.
 *
 * @param emoji the neutral form
 * @param keywords its English name followed by its search keywords, '|'-separated, lower case
 * @param variants its skin-tone forms the device can draw (empty when it has none)
 */
@Immutable
class EmojiItem(
    val emoji: String,
    val keywords: String,
    val variants: List<String>
) {
    val name: String get() = keywords.substringBefore('|')

    /** Every word of the name and keywords, for prefix search. */
    val words: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        keywords.split('|', ' ', '-').filter { it.isNotEmpty() }
    }
}

@Immutable
class EmojiCategory(val section: EmojiSection, val emojis: List<EmojiItem>)

/**
 * The emoji keyboard's data and what it remembers between runs: the bundled dataset (filtered
 * down to what this device can draw), recently used emojis, the skin tone chosen per emoji, and
 * the height of the system keyboard, which the emoji keyboard takes the place of.
 */
class EmojiStore private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("emoji_keyboard", Context.MODE_PRIVATE)

    private val loadMutex = Mutex()

    @Volatile
    private var loaded: List<EmojiCategory>? = null

    /** By neutral form and by every skin-tone form: the emoji it belongs to. */
    @Volatile
    private var byEmoji: Map<String, EmojiItem> = emptyMap()

    /** Most recent first. */
    var recents: List<String> by mutableStateOf(
        prefs.getString(KEY_RECENTS, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
    )
        private set

    /** Neutral form -> the skin-tone form the user chose for it. */
    private val tones = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(KEY_TONE_PREFIX) && value is String) put(key.removePrefix(KEY_TONE_PREFIX), value)
        }
    }

    /** The dataset, without the emojis this device has no glyph for. Loaded once. */
    suspend fun categories(): List<EmojiCategory> {
        loaded?.let { return it }
        return loadMutex.withLock {
            loaded ?: withContext(Dispatchers.Default) { readAsset() }.also { categories ->
                byEmoji = buildMap {
                    categories.forEach { category ->
                        category.emojis.forEach { item ->
                            put(item.emoji, item)
                            item.variants.forEach { put(it, item) }
                        }
                    }
                }
                loaded = categories
            }
        }
    }

    private fun readAsset(): List<EmojiCategory> {
        val paint = Paint()
        val sectionsById = EmojiSection.entries.filter { it.assetId != null }.associateBy { it.assetId }
        val result = mutableListOf<EmojiCategory>()
        var section: EmojiSection? = null
        var items = mutableListOf<EmojiItem>()
        fun flush() {
            section?.let { if (items.isNotEmpty()) result.add(EmojiCategory(it, items)) }
            items = mutableListOf()
        }
        appContext.assets.open(ASSET_PATH).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.isEmpty() || line.startsWith('#') -> Unit
                    line.startsWith('@') -> {
                        flush()
                        section = sectionsById[line.substring(1)]
                    }
                    section != null -> {
                        val parts = line.split('\t')
                        val emoji = parts[0]
                        if (PaintCompat.hasGlyph(paint, emoji)) {
                            val variants = parts.getOrNull(2)
                                ?.split(' ')
                                ?.filter { it.isNotEmpty() && PaintCompat.hasGlyph(paint, it) }
                                ?: emptyList()
                            items.add(EmojiItem(emoji, parts.getOrElse(1) { "" }, variants))
                        }
                    }
                }
            }
        }
        flush()
        return result
    }

    /** The emoji a recent or a skin-tone form belongs to (null before the dataset is loaded). */
    fun itemOf(emoji: String): EmojiItem? = byEmoji[emoji]

    /** The form to show and insert for [item]: the remembered skin tone, else the neutral one. */
    fun preferredForm(item: EmojiItem): String {
        if (item.variants.isEmpty()) return item.emoji
        return tones[item.emoji]?.takeIf { it in item.variants } ?: item.emoji
    }

    fun rememberTone(item: EmojiItem, chosen: String) {
        if (chosen == item.emoji) {
            tones.remove(item.emoji)
            prefs.edit().remove(KEY_TONE_PREFIX + item.emoji).apply()
        } else {
            tones[item.emoji] = chosen
            prefs.edit().putString(KEY_TONE_PREFIX + item.emoji, chosen).apply()
        }
    }

    fun addRecent(emoji: String) {
        val updated = (listOf(emoji) + recents.filter { it != emoji }).take(MAX_RECENTS)
        recents = updated
        prefs.edit().putString(KEY_RECENTS, updated.joinToString("\n")).apply()
    }

    /**
     * English prefix search over names and keywords: every word of [query] has to start some
     * word of the emoji. Emojis whose name starts with the query come first.
     */
    fun search(query: String, limit: Int = 80): List<EmojiItem> {
        val tokens = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val whole = tokens.joinToString(" ")
        val all = loaded ?: return emptyList()
        val nameStarts = mutableListOf<EmojiItem>()
        val nameWord = mutableListOf<EmojiItem>()
        val keyword = mutableListOf<EmojiItem>()
        all.forEach { category ->
            category.emojis.forEach { item ->
                if (tokens.all { token -> item.words.any { it.startsWith(token) } }) {
                    val name = item.name
                    when {
                        name.startsWith(whole) -> nameStarts.add(item)
                        tokens.all { token -> name.split(' ').any { it.startsWith(token) } } -> nameWord.add(item)
                        else -> keyword.add(item)
                    }
                }
            }
        }
        return (nameStarts + nameWord + keyword).take(limit)
    }

    /** The last system keyboard height seen in this orientation, in pixels (0 when never seen). */
    fun imeHeight(landscape: Boolean): Int = prefs.getInt(imeHeightKey(landscape), 0)

    fun saveImeHeight(landscape: Boolean, heightPx: Int) {
        prefs.edit().putInt(imeHeightKey(landscape), heightPx).apply()
    }

    private fun imeHeightKey(landscape: Boolean) = if (landscape) KEY_IME_HEIGHT_LAND else KEY_IME_HEIGHT_PORT

    companion object {
        private const val ASSET_PATH = "emoji/emoji.tsv"
        private const val KEY_RECENTS = "recents"
        private const val KEY_TONE_PREFIX = "tone:"
        private const val KEY_IME_HEIGHT_PORT = "ime_height_portrait"
        private const val KEY_IME_HEIGHT_LAND = "ime_height_landscape"
        private const val MAX_RECENTS = 40

        @Volatile
        private var instance: EmojiStore? = null

        fun get(context: Context): EmojiStore =
            instance ?: synchronized(this) {
                instance ?: EmojiStore(context.applicationContext).also { instance = it }
            }
    }
}

// ─── Editing the composer's text ─────────────────────────────────────────────

/** Puts [inserted] at the cursor, in place of the selection if there is one. */
fun TextFieldValue.insertAtCursor(inserted: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    return TextFieldValue(
        text = text.replaceRange(start, end, inserted),
        selection = TextRange(start + inserted.length)
    )
}

/**
 * What a backspace key does: removes the selection, or else the whole character before the
 * cursor — a full emoji even when it is built of several code points (skin tones, ZWJ sequences,
 * flags, keycaps).
 */
fun TextFieldValue.deleteBeforeCursor(): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    if (start != end) {
        return TextFieldValue(text = text.removeRange(start, end), selection = TextRange(start))
    }
    if (start == 0) return this
    val from = previousCharacterStart(text, start)
    return TextFieldValue(text = text.removeRange(from, start), selection = TextRange(from))
}

private const val ZWJ = 0x200D

private fun isEmojiModifier(codePoint: Int) = codePoint in 0x1F3FB..0x1F3FF

/** Characters that only ever continue the emoji before them. */
private fun isEmojiExtender(codePoint: Int) =
    isEmojiModifier(codePoint) ||
        codePoint == 0xFE0F || codePoint == 0xFE0E || // variation selectors
        codePoint == 0x20E3 || // combining enclosing keycap
        codePoint in 0xE0020..0xE007F // tag characters (subdivision flags)

/**
 * The start of the user-perceived character that ends at [index]. ICU finds the boundary; on
 * devices whose ICU predates an emoji sequence it may stop inside it, so the boundary is then
 * walked back over joiners, skin tones and the other continuation characters.
 */
internal fun previousCharacterStart(text: String, index: Int): Int {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var start = iterator.preceding(index)
    if (start == BreakIterator.DONE) return (index - 1).coerceAtLeast(0)
    while (start > 0) {
        val first = text.codePointAt(start)
        val before = text.codePointBefore(start)
        start = when {
            // "<emoji> ZWJ | <emoji>": the piece before the joiner belongs to this character
            before == ZWJ -> {
                val joiner = start - 1
                if (joiner == 0) return 0
                iterator.preceding(joiner).takeIf { it != BreakIterator.DONE } ?: return joiner
            }
            // The found piece is only a continuation (or a joiner): take in what it continues
            isEmojiExtender(first) || first == ZWJ ->
                iterator.preceding(start).takeIf { it != BreakIterator.DONE } ?: return start
            else -> return start
        }
    }
    return start
}
