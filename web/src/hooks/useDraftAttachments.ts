/**
 * The composer's un-sent attachments (CLOUD_RUNS_PLAN.md, "Web file routes").
 *
 * A file starts uploading the moment it is attached and is kept in the store as "pending" until
 * a message that references it is saved. Until then the draft owns it: removing it — or leaving
 * the chat / the page with it un-sent — deletes it from the store again, so attach / remove
 * leaves nothing behind. (What slips through — a request the browser dropped, a crash — is swept
 * by the store after 24 h.)
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { files as filesApi } from '../api/client'
import type { Attachment } from '../api/types'
import {
  describeUploadError,
  fileRejection,
  isAbortError,
  isInlineImageType,
} from '../utils/attachments'

export type DraftStatus = 'uploading' | 'ready' | 'failed'

export interface DraftAttachment {
  /** Local id of this draft entry (the same file attached twice gives two entries) */
  id: string
  file: File
  file_name: string
  mime_type: string
  size: number
  status: DraftStatus
  /** Why the upload failed (status `failed`) */
  error?: string
  /** Whether uploading the same file again can help (status `failed`) */
  retryable?: boolean
  /** The server's Attachment (status `ready`): what goes into the message */
  attachment?: Attachment
  /** Object URL of the local file, for image thumbnails */
  previewUrl?: string
}

/**
 * Hashes this tab has put into a saved message or project. A draft never deletes them: the
 * server commits them in the background, and until it has, the store would still delete.
 */
const usedHashes = new Set<string>()

export function markAttachmentsUsed(attachments: Attachment[]): void {
  for (const a of attachments) if (a.sha256) usedHashes.add(a.sha256)
}

/** Tests only. */
export function resetUsedAttachments(): void {
  usedHashes.clear()
}

let nextDraftId = 0

function makePreview(file: File): string | undefined {
  if (!isInlineImageType(file.type) || typeof URL.createObjectURL !== 'function') return undefined
  try {
    return URL.createObjectURL(file)
  } catch {
    return undefined
  }
}

function dropPreview(item: DraftAttachment): void {
  if (item.previewUrl && typeof URL.revokeObjectURL === 'function') URL.revokeObjectURL(item.previewUrl)
}

export interface DraftAttachments {
  items: DraftAttachment[]
  /** An upload is still running */
  uploading: boolean
  /** An upload failed and is still in the draft */
  hasFailed: boolean
  /** Attach files: each starts uploading at once. Returns the reasons of files that were refused. */
  add: (files: File[]) => string[]
  /** Remove one entry; its upload is aborted / its stored file deleted */
  remove: (id: string) => void
  retry: (id: string) => void
  /** The message is being sent: hand over the uploaded attachments and empty the draft (nothing is deleted) */
  takeForSend: () => Attachment[]
  /** Drop everything and delete it from the store */
  discardAll: () => void
}

/**
 * @param scope the draft's owner (the chat id): when it changes, and on unmount, what is left in
 *   the draft is deleted from the store.
 */
export function useDraftAttachments(scope: string | undefined): DraftAttachments {
  const [items, setItems] = useState<DraftAttachment[]>([])
  // The truth, readable from callbacks that outlive a render (upload results, cleanups)
  const itemsRef = useRef<DraftAttachment[]>([])
  const controllers = useRef(new Map<string, AbortController>())

  const update = useCallback((fn: (prev: DraftAttachment[]) => DraftAttachment[]) => {
    itemsRef.current = fn(itemsRef.current)
    setItems(itemsRef.current)
  }, [])

  /** Delete a stored file nothing needs any more. Best effort: the store sweeps what is missed. */
  const release = useCallback((sha: string | null | undefined, size: number, keepalive = false) => {
    if (!sha || usedHashes.has(sha)) return
    const left = itemsRef.current
    // Another entry of the draft holds the same file — or may turn out to (same size, still
    // uploading): the store keeps one copy per hash, deleting it would take theirs too
    if (left.some((i) => i.attachment?.sha256 === sha)) return
    if (left.some((i) => i.status === 'uploading' && i.size === size)) return
    filesApi.delete(sha, { keepalive }).catch(() => {})
  }, [])

  const start = useCallback((id: string, file: File) => {
    const controller = new AbortController()
    controllers.current.set(id, controller)
    const present = () => itemsRef.current.some((i) => i.id === id)
    filesApi.upload(file, { signal: controller.signal }).then(
      (attachment) => {
        if (controllers.current.get(id) === controller) controllers.current.delete(id)
        if (!present()) {
          // Removed while the upload was finishing: it is stored, and nobody wants it
          release(attachment.sha256, file.size)
          return
        }
        update((prev) => prev.map((i) => (
          i.id === id ? { ...i, status: 'ready', attachment, error: undefined, retryable: undefined } : i
        )))
      },
      (err) => {
        if (controllers.current.get(id) === controller) controllers.current.delete(id)
        if (!present() || isAbortError(err)) return
        const failure = describeUploadError(err)
        update((prev) => prev.map((i) => (
          i.id === id ? { ...i, status: 'failed', error: failure.message, retryable: failure.retryable } : i
        )))
      },
    )
  }, [release, update])

  const add = useCallback((picked: File[]): string[] => {
    const rejected: string[] = []
    for (const file of picked) {
      const reason = fileRejection(file)
      if (reason) {
        rejected.push(`${file.name}: ${reason}`)
        continue
      }
      const item: DraftAttachment = {
        id: `draft-${++nextDraftId}`,
        file,
        file_name: file.name,
        mime_type: file.type || 'application/octet-stream',
        size: file.size,
        status: 'uploading',
        previewUrl: makePreview(file),
      }
      update((prev) => [...prev, item])
      start(item.id, file)
    }
    return rejected
  }, [start, update])

  const remove = useCallback((id: string) => {
    const item = itemsRef.current.find((i) => i.id === id)
    if (!item) return
    update((prev) => prev.filter((i) => i.id !== id))
    dropPreview(item)
    // Still uploading: stop it. If it got through anyway, its result deletes it (see start)
    controllers.current.get(id)?.abort()
    if (item.status === 'ready') release(item.attachment?.sha256, item.size)
  }, [release, update])

  const retry = useCallback((id: string) => {
    const item = itemsRef.current.find((i) => i.id === id)
    if (!item || item.status !== 'failed') return
    update((prev) => prev.map((i) => (
      i.id === id ? { ...i, status: 'uploading', error: undefined, retryable: undefined } : i
    )))
    start(id, item.file)
  }, [start, update])

  const takeForSend = useCallback((): Attachment[] => {
    const taken = itemsRef.current
    const attachments = taken.flatMap((i) => (i.status === 'ready' && i.attachment ? [i.attachment] : []))
    markAttachmentsUsed(attachments)
    taken.forEach(dropPreview)
    // Nothing else can be in the draft at a send (it is blocked meanwhile); be safe anyway
    taken.forEach((i) => controllers.current.get(i.id)?.abort())
    update(() => [])
    return attachments
  }, [update])

  const discard = useCallback((keepalive: boolean) => {
    const dropped = itemsRef.current
    if (dropped.length === 0) return
    update(() => [])
    dropped.forEach((i) => {
      dropPreview(i)
      controllers.current.get(i.id)?.abort()
    })
    const seen = new Set<string>()
    for (const i of dropped) {
      const sha = i.status === 'ready' ? i.attachment?.sha256 : null
      if (!sha || seen.has(sha)) continue
      seen.add(sha)
      release(sha, i.size, keepalive)
    }
  }, [release, update])

  const discardAll = useCallback(() => discard(false), [discard])

  // Leaving the chat (or the page component) with un-sent attachments
  useEffect(() => () => discard(false), [scope, discard])

  // Closing / reloading the tab: the deletes must outlive the page
  useEffect(() => {
    const onPageHide = (e: PageTransitionEvent) => {
      // A page kept in the back/forward cache comes back with its draft
      if (!e.persisted) discard(true)
    }
    window.addEventListener('pagehide', onPageHide)
    return () => window.removeEventListener('pagehide', onPageHide)
  }, [discard])

  return {
    items,
    uploading: items.some((i) => i.status === 'uploading'),
    hasFailed: items.some((i) => i.status === 'failed'),
    add,
    remove,
    retry,
    takeForSend,
    discardAll,
  }
}
