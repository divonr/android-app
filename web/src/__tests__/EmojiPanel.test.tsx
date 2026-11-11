/**
 * The real emoji picker behind the composer's panel: it mounts with the app's Hebrew labels and
 * category tabs, from data bundled with it.
 */
import React from 'react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import EmojiPanel from '../components/chat/EmojiPanel'
import { t } from '../i18n/he'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('EmojiPanel', () => {
  it('renders the search field and the category tabs without fetching anything', { timeout: 30000 }, () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    render(<EmojiPanel onPick={() => {}} />)

    expect(screen.getByPlaceholderText(t('emoji_search_hint'))).toBeInTheDocument()
    for (const key of [
      'emoji_cat_smileys_people',
      'emoji_cat_animals_nature',
      'emoji_cat_food_drink',
      'emoji_cat_activities',
      'emoji_cat_travel_places',
      'emoji_cat_objects',
      'emoji_cat_symbols',
      'emoji_cat_flags',
    ] as const) {
      expect(screen.getAllByLabelText(t(key)).length).toBeGreaterThan(0)
    }
    expect(document.querySelectorAll('img').length).toBe(0)
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
