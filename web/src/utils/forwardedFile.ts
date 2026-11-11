/**
 * An image forwarded from the image viewer to another chat, on its way there.
 *
 * The viewer puts the file here and navigates to the chat; the chat page picks it up and
 * attaches it to its draft (a normal upload: the draft owns it like any attached file).
 * In memory only — a file handed over and never collected is simply dropped.
 */
let pending: { chatId: string; file: File } | null = null

export function setForwardedFile(chatId: string, file: File): void {
  pending = { chatId, file }
}

/** The file waiting for [chatId], if any (left in place: see clearForwardedFile). */
export function peekForwardedFile(chatId: string): File | null {
  return pending && pending.chatId === chatId ? pending.file : null
}

/** Forget [file] once the chat page has attached it. */
export function clearForwardedFile(file: File): void {
  if (pending?.file === file) pending = null
}
