/**
 * How a saved attachment is shown on the web (messages and project files).
 *
 *  - has a store hash + a safe image type → the image itself, loaded from the file route only
 *    once it scrolls into view; a click opens it full screen in the app (ImageViewer), a
 *    second control downloads it
 *  - has a store hash, any other type → a download link (never rendered in the page: the server
 *    serves those as downloads, and the UI never turns their bytes into a document)
 *  - no hash (attached before the store existed, or by a device that has not uploaded it) →
 *    the name with a "only on the device that attached it" hint
 */
import React, { useEffect, useRef, useState } from 'react'
import { MdAttachFile, MdFileDownload, MdPhoto } from '../../ui/icons'
import type { Attachment } from '../../api/types'
import { attachmentKind, attachmentUrl, formatFileSize } from '../../utils/attachments'
import { t } from '../../i18n/he'
import { openImageOnClick } from './ImageViewer'

// ─── Lazy image ───────────────────────────────────────────────────────────────

/** True once the element has come near the viewport (and stays true). */
export function useNearViewport<T extends Element>(): [React.RefObject<T>, boolean] {
  const ref = useRef<T>(null)
  // No IntersectionObserver (old browser, tests): load right away
  const [near, setNear] = useState(() => typeof IntersectionObserver === 'undefined')

  useEffect(() => {
    const el = ref.current
    if (near || !el || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          setNear(true)
          observer.disconnect()
        }
      },
      { rootMargin: '200px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [near])

  return [ref, near]
}

interface StoreImageProps {
  att: Attachment
  /** Size of the box the image is fitted into */
  width: number | string
  height: number | string
  fit?: 'cover' | 'contain'
  radius?: number
  /** The file could not be loaded (deleted from the store, store unreachable) */
  onError: () => void
}

/**
 * An image attachment from the store. The request is made only when the placeholder is near
 * the viewport, so opening a long chat does not download every image in it.
 */
export const StoreImage: React.FC<StoreImageProps> = ({ att, width, height, fit = 'cover', radius = 8, onError }) => {
  const [ref, near] = useNearViewport<HTMLSpanElement>()
  const src = attachmentUrl(att)
  if (!src) return null

  return (
    <span
      ref={ref}
      data-testid="attachment-image"
      style={{
        display: 'block',
        width,
        height,
        maxWidth: '100%',
        borderRadius: radius,
        overflow: 'hidden',
        background: 'rgba(0,0,0,0.18)',
      }}
    >
      {near && (
        <img
          src={src}
          alt={att.file_name}
          loading="lazy"
          decoding="async"
          draggable={false}
          onError={onError}
          style={{ display: 'block', width: '100%', height: '100%', objectFit: fit }}
        />
      )}
    </span>
  )
}

// ─── Attachment in a message ──────────────────────────────────────────────────

interface AttachmentViewProps {
  att: Attachment
  /** On the user's (primary coloured) bubble */
  onPrimary?: boolean
}

const AttachmentView: React.FC<AttachmentViewProps> = ({ att, onPrimary = false }) => {
  const kind = attachmentKind(att)
  const [broken, setBroken] = useState(false)
  const chipBg = onPrimary ? 'rgba(255,255,255,0.15)' : 'var(--surface-variant)'
  const chipColor = onPrimary ? 'rgba(255,255,255,0.9)' : 'var(--on-surface-variant)'
  const hintColor = onPrimary ? 'rgba(255,255,255,0.7)' : 'var(--on-surface-variant)'

  const chipStyle: React.CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    background: chipBg,
    borderRadius: 6,
    padding: '4px 8px',
    fontSize: 12,
    color: chipColor,
    maxWidth: '100%',
    minWidth: 0,
    textDecoration: 'none',
  }
  const nameStyle: React.CSSProperties = {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    minWidth: 0,
  }

  /** A name-only chip with a reason below it: the file cannot be had here. */
  const unavailable = (reason: string, state: 'legacy' | 'unavailable') => (
    <span
      data-testid="attachment"
      data-attachment-state={state}
      title={reason}
      style={{ display: 'inline-flex', flexDirection: 'column', gap: 2, maxWidth: '100%', minWidth: 0 }}
    >
      <span style={{ ...chipStyle, opacity: 0.75 }}>
        {kind === 'image' ? <MdPhoto size={13} style={{ flexShrink: 0 }} /> : <MdAttachFile size={13} style={{ flexShrink: 0 }} />}
        <span style={nameStyle}>{att.file_name}</span>
      </span>
      <span style={{ fontSize: 10, color: hintColor, paddingInlineStart: 2 }}>{reason}</span>
    </span>
  )

  if (kind === 'legacy') return unavailable(t('attachment_device_only'), 'legacy')

  const downloadUrl = attachmentUrl(att, true)!
  const size = formatFileSize(att.size)

  if (kind === 'image') {
    // Deleted from the store / store unreachable: the name instead of a broken image
    if (broken) return unavailable(t('attachment_unavailable'), 'unavailable')
    return (
      <span
        data-testid="attachment"
        data-attachment-state="image"
        style={{ position: 'relative', display: 'inline-block', maxWidth: '100%' }}
      >
        {/* The thumbnail opens the image in the in-app viewer (a modified click: in a new tab,
            served inline, sandboxed); the corner button downloads it */}
        <a
          href={attachmentUrl(att)!}
          target="_blank"
          rel="noopener noreferrer"
          onClick={(e) => openImageOnClick(e, att)}
          title={att.file_name}
          aria-label={`${t('attachment_open_image')}: ${att.file_name}`}
          style={{ display: 'block' }}
        >
          <StoreImage att={att} width={200} height={150} onError={() => setBroken(true)} />
        </a>
        <a
          href={downloadUrl}
          download={att.file_name}
          title={`${t('attachment_download')}: ${att.file_name}`}
          aria-label={`${t('attachment_download')}: ${att.file_name}`}
          style={{
            position: 'absolute',
            bottom: 6,
            insetInlineEnd: 6,
            width: 26,
            height: 26,
            borderRadius: '50%',
            background: 'rgba(0,0,0,0.6)',
            color: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <MdFileDownload size={16} />
        </a>
      </span>
    )
  }

  return (
    <a
      data-testid="attachment"
      data-attachment-state="file"
      href={downloadUrl}
      download={att.file_name}
      title={`${t('attachment_download')}: ${att.file_name}`}
      aria-label={`${t('attachment_download')}: ${att.file_name}`}
      style={chipStyle}
    >
      <MdAttachFile size={13} style={{ flexShrink: 0 }} />
      <span style={nameStyle}>{att.file_name}</span>
      {size && <span style={{ flexShrink: 0, opacity: 0.75 }} dir="ltr">{size}</span>}
      <MdFileDownload size={14} style={{ flexShrink: 0 }} />
    </a>
  )
}

export default AttachmentView
