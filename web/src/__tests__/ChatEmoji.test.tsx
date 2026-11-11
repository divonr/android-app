/**
 * The composer's emoji keyboard: where the emoji and attach buttons sit, opening and closing
 * the panel, and inserting an emoji at the caret.
 */
import React, { useState } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import ChatInputArea from '../components/chat/ChatInputArea'
import { t } from '../i18n/he'

// The real picker is exercised in its own test below; here a stand-in that picks on demand
vi.mock('../components/chat/EmojiPanel', () => ({
  default: ({ onPick }: { onPick: (emoji: string) => void }) => (
    <div data-testid="emoji-panel">
      <button type="button" onClick={() => onPick('😀')}>pick-grin</button>
      <button type="button" onClick={() => onPick('👍🏽')}>pick-thumb</button>
    </div>
  ),
}))

const Composer: React.FC<{ initial?: string; onAttachFile?: () => void }> = ({
  initial = '',
  onAttachFile = () => {},
}) => {
  const [text, setText] = useState(initial)
  return (
    <div>
      <div data-testid="outside">conversation</div>
      <ChatInputArea
        inputText={text}
        onInputChange={setText}
        attachments={[]}
        onRemoveAttachment={() => {}}
        onRetryAttachment={() => {}}
        onAttachFile={onAttachFile}
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
    </div>
  )
}

const textarea = () => screen.getByPlaceholderText(t('type_message')) as HTMLTextAreaElement
const emojiButton = () => screen.getByRole('button', { name: t('emoji_keyboard_open') })
const keyboardButton = () => screen.getByRole('button', { name: t('emoji_keyboard_show_keyboard') })
const openPanel = async () => {
  fireEvent.click(emojiButton())
  return screen.findByTestId('emoji-panel')
}
/** True when `a` comes before `b` in the document */
const precedes = (a: Element, b: Element) =>
  (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0

describe('composer emoji keyboard', () => {
  it('puts the emoji button where the attach button was and the attach button by web search', () => {
    const onAttachFile = vi.fn()
    render(<Composer onAttachFile={onAttachFile} />)
    const attach = screen.getByRole('button', { name: t('attach_file') })
    const webSearch = screen.getByRole('button', { name: t('web_search_toggle') })
    const send = screen.getByRole('button', { name: 'Send message' })

    expect(precedes(emojiButton(), textarea())).toBe(true)
    expect(precedes(textarea(), attach)).toBe(true)
    expect(precedes(attach, webSearch)).toBe(true)
    expect(precedes(webSearch, send)).toBe(true)

    fireEvent.click(attach)
    expect(onAttachFile).toHaveBeenCalledTimes(1)
  })

  it('opens the panel, shows the keyboard icon while it is open, and closes it again', async () => {
    render(<Composer />)
    expect(screen.queryByTestId('emoji-panel')).toBeNull()
    expect(emojiButton()).toHaveAttribute('aria-expanded', 'false')

    await openPanel()
    expect(screen.queryByRole('button', { name: t('emoji_keyboard_open') })).toBeNull()
    expect(keyboardButton()).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(keyboardButton())
    expect(screen.queryByTestId('emoji-panel')).toBeNull()
    expect(emojiButton()).toBeInTheDocument()
    expect(textarea()).toHaveFocus()
  })

  it('closes on Escape and gives the text field the focus', async () => {
    render(<Composer />)
    await openPanel()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByTestId('emoji-panel')).toBeNull()
    expect(textarea()).toHaveFocus()
  })

  it('closes on a click outside the composer but not on one inside it', async () => {
    render(<Composer />)
    const panel = await openPanel()

    fireEvent.mouseDown(panel)
    fireEvent.mouseDown(textarea())
    expect(screen.getByTestId('emoji-panel')).toBeInTheDocument()

    fireEvent.mouseDown(screen.getByTestId('outside'))
    expect(screen.queryByTestId('emoji-panel')).toBeNull()
  })

  it('inserts at the caret, keeps the panel open and moves the caret past the emoji', async () => {
    render(<Composer initial="hello" />)
    await openPanel()
    textarea().setSelectionRange(2, 2)

    fireEvent.click(screen.getByText('pick-grin'))
    await waitFor(() => expect(textarea().value).toBe('he😀llo'))
    expect(textarea().selectionStart).toBe(2 + '😀'.length)
    expect(textarea().selectionEnd).toBe(2 + '😀'.length)
    expect(screen.getByTestId('emoji-panel')).toBeInTheDocument()

    // The next one follows the first, a multi-code-point emoji included
    fireEvent.click(screen.getByText('pick-thumb'))
    await waitFor(() => expect(textarea().value).toBe('he😀👍🏽llo'))
    expect(textarea().selectionStart).toBe(2 + '😀👍🏽'.length)
  })

  it('replaces the selection', async () => {
    render(<Composer initial="hello world" />)
    await openPanel()
    textarea().setSelectionRange(6, 11)

    fireEvent.click(screen.getByText('pick-grin'))
    await waitFor(() => expect(textarea().value).toBe('hello 😀'))
  })

  it('appends to an empty message', async () => {
    render(<Composer />)
    await openPanel()
    fireEvent.click(screen.getByText('pick-grin'))
    await waitFor(() => expect(textarea().value).toBe('😀'))
  })
})
