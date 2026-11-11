/**
 * components/chat/SystemPromptDialog.tsx — System prompt editor dialog.
 *
 * Mirrors SystemPromptDialog.kt: multiline text editor, save/cancel buttons.
 * Uses R0 Dialog + DialogButton components.
 */

import React, { useState, useEffect } from 'react'
import Dialog, { DialogButton } from '../../ui/Dialog'
import { t } from '../../i18n/he'

interface SystemPromptDialogProps {
  open: boolean
  onClose: () => void
  /** Current system prompt text */
  currentPrompt: string
  /** Called with the new prompt text when user saves */
  onSave: (prompt: string) => void
}

const SystemPromptDialog: React.FC<SystemPromptDialogProps> = ({
  open,
  onClose,
  currentPrompt,
  onSave,
}) => {
  const [draft, setDraft] = useState(currentPrompt)

  // Sync draft when dialog opens with new prompt
  useEffect(() => {
    if (open) setDraft(currentPrompt)
  }, [open, currentPrompt])

  const handleSave = () => {
    onSave(draft)
    onClose()
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={t('system_prompt')}
      maxWidth={480}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onClose} />
          <DialogButton label={t('approve')} onClick={handleSave} primary />
        </>
      }
    >
      <textarea
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder={t('system_prompt')}
        rows={8}
        style={{
          width: '100%',
          padding: '10px 12px',
          background: 'var(--surface-variant)',
          border: '1px solid var(--message-border)',
          borderRadius: 'var(--radius-card)',
          color: 'var(--on-surface)',
          fontSize: 'var(--fs-body-medium)',
          fontFamily: 'inherit',
          resize: 'vertical',
          outline: 'none',
          boxSizing: 'border-box',
          lineHeight: 1.5,
          direction: 'auto' as React.CSSProperties['direction'],
        }}
      />
    </Dialog>
  )
}

export default SystemPromptDialog
