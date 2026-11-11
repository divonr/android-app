/**
 * EmojiPanel — the emoji keyboard under the web composer (the Android app's EmojiKeyboard).
 *
 * Built on emoji-picker-react with native glyphs: the emoji data ships inside this chunk and
 * nothing is fetched at run time. Categories (recents first), search, and skin tones — the
 * tone picker by the search field, and a long press on an emoji for its own tones. Recents and
 * the chosen tone are kept by the picker in localStorage.
 *
 * Imported lazily by ChatInputArea so the picker and its data stay out of the main bundle.
 */

import React from 'react'
import EmojiPicker, {
  Categories,
  EmojiStyle,
  SkinTonePickerLocation,
  SuggestionMode,
  Theme,
} from 'emoji-picker-react'
import type { EmojiClickData } from 'emoji-picker-react'
import { t } from '../../i18n/he'

const EMOJI_PANEL_HEIGHT = 320

interface EmojiPanelProps {
  /** An emoji was chosen: insert it at the composer's caret */
  onPick: (emoji: string) => void
}

// The Android keyboard's tab order
const categories = [
  { category: Categories.SUGGESTED, name: t('emoji_cat_recents') },
  { category: Categories.SMILEYS_PEOPLE, name: t('emoji_cat_smileys_people') },
  { category: Categories.ANIMALS_NATURE, name: t('emoji_cat_animals_nature') },
  { category: Categories.FOOD_DRINK, name: t('emoji_cat_food_drink') },
  { category: Categories.ACTIVITIES, name: t('emoji_cat_activities') },
  { category: Categories.TRAVEL_PLACES, name: t('emoji_cat_travel_places') },
  { category: Categories.OBJECTS, name: t('emoji_cat_objects') },
  { category: Categories.SYMBOLS, name: t('emoji_cat_symbols') },
  { category: Categories.FLAGS, name: t('emoji_cat_flags') },
]

const labels = {
  searchPlaceholder: t('emoji_search_hint'),
  searchLabel: t('emoji_search'),
  searchClear: t('emoji_search_clear'),
  searchResultsNone: t('emoji_search_no_results'),
}

// The app's dark theme in place of the picker's own colours
const themeVars = {
  '--epr-bg-color': 'var(--surface-variant)',
  '--epr-category-label-bg-color': 'var(--surface-variant)',
  '--epr-text-color': 'var(--on-surface-variant)',
  '--epr-highlight-color': 'var(--primary)',
  '--epr-hover-bg-color': 'var(--primary-15)',
  '--epr-focus-bg-color': 'var(--primary-20)',
  '--epr-picker-border-color': 'transparent',
  '--epr-search-input-bg-color': 'var(--surface)',
  '--epr-search-input-bg-color-active': 'var(--surface)',
  '--epr-search-border-color': 'transparent',
  '--epr-search-border-color-active': 'var(--primary)',
  '--epr-emoji-variation-picker-bg-color': 'var(--surface)',
  '--epr-skin-tone-picker-menu-color': 'var(--surface)',
  '--epr-emoji-size': '26px',
  borderRadius: 0,
} as React.CSSProperties

// Native glyphs need no image; this keeps the picker from ever naming a CDN
const noImageUrl = () => ''

const EmojiPanel: React.FC<EmojiPanelProps> = ({ onPick }) => (
  <div data-testid="emoji-panel" style={{ height: EMOJI_PANEL_HEIGHT }}>
    <EmojiPicker
      onEmojiClick={(data: EmojiClickData) => onPick(data.emoji)}
      emojiStyle={EmojiStyle.NATIVE}
      getEmojiUrl={noImageUrl}
      colorScheme={Theme.DARK}
      categories={categories}
      labels={labels}
      suggestedEmojisMode={SuggestionMode.RECENT}
      skinTonePickerLocation={SkinTonePickerLocation.SEARCH}
      previewConfig={{ showPreview: false }}
      autoFocusSearch={false}
      width="100%"
      height={EMOJI_PANEL_HEIGHT}
      style={themeVars}
    />
  </div>
)

export default EmojiPanel
