/**
 * Attachment store helpers (CLOUD_RUNS_PLAN.md, "Web file routes").
 *
 * An attachment with a `sha256` is in the account's store and can be shown / downloaded on the
 * web whichever device attached it; one without exists only on the device that attached it.
 */
import type { Attachment } from '../api/types'
import { t } from '../i18n/he'

/** The store's limit for one file (the public URL is behind a proxy that caps a body at 100 MB). */
export const MAX_FILE_BYTES = 95 * 1024 * 1024

/**
 * The image types the server serves inline (`Content-Disposition: inline`, their own type).
 * Only these are ever put into an `<img>`; everything else — SVG and HTML included — is a download.
 */
const INLINE_IMAGE_TYPES = new Set([
  'image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/avif', 'image/bmp',
])

const SHA256_RE = /^[0-9a-f]{64}$/

function normalMime(mime: string | null | undefined): string {
  return (mime ?? '').split(';')[0].trim().toLowerCase()
}

/** Whether a file of this mime type may be shown as an image in the page. */
export function isInlineImageType(mime: string | null | undefined): boolean {
  return INLINE_IMAGE_TYPES.has(normalMime(mime))
}

/** The attachment's store hash, or null for a legacy / device-only attachment. */
export function attachmentHash(att: Attachment): string | null {
  return att.sha256 && SHA256_RE.test(att.sha256) ? att.sha256 : null
}

/** `GET /api/files/{sha256}?name=&mime=[&download=1]`, or null when the attachment has no hash. */
export function attachmentUrl(att: Attachment, download = false): string | null {
  const sha = attachmentHash(att)
  if (!sha) return null
  const query = new URLSearchParams()
  if (att.file_name) query.set('name', att.file_name)
  if (att.mime_type) query.set('mime', att.mime_type)
  if (download) query.set('download', '1')
  const qs = query.toString()
  return `/api/files/${sha}${qs ? `?${qs}` : ''}`
}

export type AttachmentKind = 'image' | 'file' | 'legacy'

/** How the web shows an attachment: an inline image, a downloadable file, or a device-only name. */
export function attachmentKind(att: Attachment): AttachmentKind {
  if (!attachmentHash(att)) return 'legacy'
  return isInlineImageType(att.mime_type) ? 'image' : 'file'
}

export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes < 10 * 1024 ? 1 : 0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0)} MB`
}

/** A failed upload, in words for the user, and whether trying the same file again can help. */
export interface UploadFailure {
  message: string
  retryable: boolean
}

/** Why a file cannot be uploaded at all (checked before any request), or null. */
export function fileRejection(file: File): string | null {
  if (file.size > MAX_FILE_BYTES) return t('attachment_too_large')
  if (file.size === 0) return t('attachment_empty_file')
  return null
}

export function isAbortError(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'AbortError'
}

/** Checked structurally (status + `{ code }` body), so it works with a mocked API client too. */
export function describeUploadError(err: unknown): UploadFailure {
  const e = typeof err === 'object' && err !== null ? (err as { status?: unknown; body?: unknown }) : {}
  const body = typeof e.body === 'object' && e.body !== null ? (e.body as { code?: unknown }) : {}
  if (e.status === 413 || body.code === 'file_too_large') {
    return { message: t('attachment_too_large'), retryable: false }
  }
  if (body.code === 'no_file') return { message: t('attachment_empty_file'), retryable: false }
  if (e.status === 401) return { message: t('attachment_auth_expired'), retryable: true }
  if (e.status === 502 || body.code === 'store_unavailable') {
    return { message: t('attachment_store_unavailable'), retryable: true }
  }
  return { message: t('attachment_upload_failed'), retryable: true }
}
