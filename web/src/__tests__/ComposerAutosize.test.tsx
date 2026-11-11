/**
 * The composer's textarea height: one line while empty, growing with the text up to the
 * maximum, and back to one line when the text is cleared by any path (not only by typing).
 *
 * jsdom lays nothing out, so scrollHeight and clientWidth are stubbed: 22px a line plus 12px
 * of padding, as in the real composer.
 */
import React, { useState } from 'react'
import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import ChatInputArea from '../components/chat/ChatInputArea'
import { COMPOSER_MAX_HEIGHT, composerHeight, fitTextarea } from '../components/chat/textareaAutosize'
import { t } from '../i18n/he'

const LINE = 22
const PADDING = 12
let width = 300

const scrollHeightDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollHeight')
const clientWidthDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth')

beforeEach(() => {
  width = 300
  Object.defineProperty(HTMLTextAreaElement.prototype, 'scrollHeight', {
    configurable: true,
    get(this: HTMLTextAreaElement) {
      // A browser never reports less than the box itself: a stale inline height shows here
      const content = this.value.split('\n').length * LINE + PADDING
      return Math.max(content, parseInt(this.style.height || '0', 10))
    },
  })
  Object.defineProperty(HTMLTextAreaElement.prototype, 'clientWidth', {
    configurable: true,
    get: () => width,
  })
})

afterEach(() => {
  delete (HTMLTextAreaElement.prototype as unknown as Record<string, unknown>).scrollHeight
  delete (HTMLTextAreaElement.prototype as unknown as Record<string, unknown>).clientWidth
  // The prototype's own getters stay as they were
  expect(Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'scrollHeight')).toEqual(scrollHeightDescriptor)
  expect(Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'clientWidth')).toEqual(clientWidthDescriptor)
})

const lines = (n: number) => Array.from({ length: n }, (_, i) => `line ${i}`).join('\n')

let setTextFromOutside: (text: string) => void = () => {}

const Composer: React.FC<{ initial?: string }> = ({ initial = '' }) => {
  const [text, setText] = useState(initial)
  setTextFromOutside = setText
  return (
    <ChatInputArea
      inputText={text}
      onInputChange={setText}
      attachments={[]}
      onRemoveAttachment={() => {}}
      onRetryAttachment={() => {}}
      onAttachFile={() => {}}
      streaming={false}
      editingMsg={false}
      webSearch={false}
      showWebSearch
      onToggleWebSearch={() => {}}
      onSend={() => {}}
      onStop={() => {}}
      onConfirmEdit={() => {}}
      onConfirmEditAndResend={() => {}}
      onCancelEdit={() => {}}
      onKeyDown={() => {}}
    />
  )
}

const textarea = () => screen.getByPlaceholderText(t('type_message')) as HTMLTextAreaElement

describe('composerHeight', () => {
  it('leaves an empty textarea to the stylesheet whatever it measures', () => {
    expect(composerHeight('', 34, 300)).toBeNull()
    expect(composerHeight('', 150, 300)).toBeNull()
    expect(composerHeight('', 900, 40)).toBeNull()
  })

  it('follows the content up to the maximum', () => {
    expect(composerHeight('a', 34, 300)).toBe('34px')
    expect(composerHeight('a\nb\nc', 78, 300)).toBe('78px')
    expect(composerHeight(lines(20), 452, 300)).toBe(`${COMPOSER_MAX_HEIGHT}px`)
  })

  it('does not trust a measurement of a textarea with no width or no layout', () => {
    expect(composerHeight('some text', 2000, 0)).toBeNull()
    expect(composerHeight('some text', 0, 300)).toBeNull()
  })
})

describe('fitTextarea', () => {
  it('shrinks a textarea whose text was replaced without an input event', () => {
    const ta = document.createElement('textarea')
    ta.value = lines(4)
    fitTextarea(ta)
    expect(ta.style.height).toBe(`${4 * LINE + PADDING}px`)

    ta.value = 'one'
    fitTextarea(ta)
    expect(ta.style.height).toBe(`${LINE + PADDING}px`)

    ta.value = ''
    fitTextarea(ta)
    expect(ta.style.height).toBe('')
  })
})

describe('composer textarea height', () => {
  it('has no inline height while empty', () => {
    render(<Composer />)
    expect(textarea().style.height).toBe('')
    expect(textarea().rows).toBe(1)
  })

  it('grows as text is typed and stops at the maximum', () => {
    render(<Composer />)
    fireEvent.change(textarea(), { target: { value: lines(3) } })
    expect(textarea().style.height).toBe(`${3 * LINE + PADDING}px`)
    fireEvent.change(textarea(), { target: { value: lines(12) } })
    expect(textarea().style.height).toBe(`${COMPOSER_MAX_HEIGHT}px`)
  })

  it('is sized for a text it starts with or is given (an edit loading a message)', () => {
    render(<Composer initial={lines(2)} />)
    expect(textarea().style.height).toBe(`${2 * LINE + PADDING}px`)
    act(() => setTextFromOutside(lines(4)))
    expect(textarea().style.height).toBe(`${4 * LINE + PADDING}px`)
  })

  it('returns to one line when the text is cleared from outside (a send, a cancelled edit)', () => {
    render(<Composer />)
    fireEvent.change(textarea(), { target: { value: lines(8) } })
    expect(textarea().style.height).toBe(`${COMPOSER_MAX_HEIGHT}px`)

    act(() => setTextFromOutside(''))
    expect(textarea().value).toBe('')
    expect(textarea().style.height).toBe('')
  })

  it('shrinks when lines are removed', () => {
    render(<Composer initial={lines(5)} />)
    fireEvent.change(textarea(), { target: { value: lines(2) } })
    expect(textarea().style.height).toBe(`${2 * LINE + PADDING}px`)
  })
})
