/**
 * SkillsPage — R6 refactor.
 * Mirrors Android SkillsScreen.kt: list with enable/disable toggle,
 * create dialog, import-from-text dialog, delete confirm.
 * Tap a skill → navigate to /skills/:name/edit (SkillEditorPage).
 */
import React, { useEffect, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { skills as skillsApi } from '../api/client'
import type { InstalledSkill } from '../api/types'
import ScreenTopBar from '../components/ScreenTopBar'
import Dialog, { DialogButton } from '../ui/Dialog'
import { t } from '../i18n/he'
import styles from './SkillsPage.module.css'

// ── Skill card ────────────────────────────────────────────────────────────────

interface SkillCardProps {
  skill: InstalledSkill
  onEdit: () => void
  onToggle: (enabled: boolean) => void
  onDelete: () => void
  onExport: () => void
}

const SkillCard: React.FC<SkillCardProps> = ({ skill, onEdit, onToggle, onDelete, onExport }) => (
  <div
    className={`${styles.skillCard} ${!skill.enabled ? styles.skillCardDisabled : ''}`}
    onClick={onEdit}
    role="button"
    aria-label={skill.name}
  >
    {/* Icon */}
    <div className={styles.skillIcon} aria-hidden="true">✨</div>

    {/* Text */}
    <div className={styles.skillInfo}>
      <p className={`${styles.skillName} ${skill.enabled ? styles.skillNameEnabled : styles.skillNameDisabled}`}>
        {skill.name}
      </p>
      <p className={`${styles.skillDescription} ${skill.enabled ? styles.skillDescEnabled : styles.skillDescDisabled}`}>
        {skill.description || 'No description'}
      </p>
    </div>

    {/* Export download button */}
    <button
      className={styles.topbarBtn}
      onClick={(e) => { e.stopPropagation(); onExport() }}
      aria-label={`Export ${skill.name} as ZIP`}
      title="ייצוא ZIP"
      style={{ fontSize: 16 }}
    >
      ⬇
    </button>

    {/* Toggle (click doesn't bubble to card's onEdit) */}
    <label
      className={styles.toggle}
      onClick={(e) => e.stopPropagation()}
      title={skill.enabled ? 'Disable' : 'Enable'}
    >
      <input
        type="checkbox"
        checked={skill.enabled}
        onChange={(e) => onToggle(e.target.checked)}
        aria-label={`${skill.enabled ? 'Disable' : 'Enable'} ${skill.name}`}
      />
      <span className={styles.toggleSlider} />
    </label>
  </div>
)

// ── Import ZIP dialog ─────────────────────────────────────────────────────────

interface ImportZipDialogProps {
  onImport: (file: File) => Promise<void>
  onClose: () => void
}

const ImportZipDialog: React.FC<ImportZipDialogProps> = ({ onImport, onClose }) => {
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  const handleImport = async () => {
    if (!file) { setErr('בחר קובץ ZIP'); return }
    setBusy(true)
    setErr('')
    try {
      await onImport(file)
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Import failed')
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      title="ייבוא סקיל מ-ZIP"
      onClose={onClose}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onClose} />
          <DialogButton label={busy ? '...' : 'ייבוא'} onClick={handleImport} primary disabled={busy || !file} />
        </>
      }
    >
      <p className={styles.dialogHint}>בחר קובץ ZIP המכיל תיקיית סקיל עם קובץ SKILL.md</p>
      <div className={styles.dialogField}>
        <input
          type="file"
          accept=".zip,application/zip"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          style={{ color: 'var(--on-surface)', fontFamily: 'inherit', fontSize: 'var(--fs-body-small)' }}
        />
      </div>
      {err && <div className={styles.error}>{err}</div>}
    </Dialog>
  )
}

// ── Import text dialog ────────────────────────────────────────────────────────

interface ImportTextDialogProps {
  onImport: (text: string) => Promise<void>
  onClose: () => void
}

const ImportTextDialog: React.FC<ImportTextDialogProps> = ({ onImport, onClose }) => {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  const handleImport = async () => {
    if (!text.trim()) { setErr(t('skill_import_from_text') + ' is empty'); return }
    setBusy(true)
    setErr('')
    try {
      await onImport(text)
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Import failed')
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      title={t('skill_import_text_title')}
      onClose={onClose}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onClose} />
          <DialogButton label={busy ? '...' : t('skill_import_from_text')} onClick={handleImport} primary disabled={busy || !text.includes('---')} />
        </>
      }
    >
      <p className={styles.dialogHint}>{t('skill_import_text_hint')}</p>
      <div className={styles.dialogField}>
        <textarea
          className={styles.dialogTextarea}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={'---\nname: my-skill\ndescription: ...\n---\n\n# My Skill\n...'}
          autoFocus
        />
      </div>
      {err && <div className={styles.error}>{err}</div>}
    </Dialog>
  )
}

// ── Create skill dialog ───────────────────────────────────────────────────────

interface CreateDialogProps {
  onCreate: (name: string, description: string) => Promise<void>
  onClose: () => void
}

const CreateSkillDialog: React.FC<CreateDialogProps> = ({ onCreate, onClose }) => {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  const handleCreate = async () => {
    if (!name.trim() || !description.trim()) { setErr('שם ותיאור נדרשים'); return }
    setBusy(true)
    setErr('')
    try {
      await onCreate(name.trim(), description.trim())
    } catch (e) {
      setErr(e instanceof Error ? e.message : 'Create failed')
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      title={t('skill_create_new')}
      onClose={onClose}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onClose} />
          <DialogButton label={busy ? '...' : t('create')} onClick={handleCreate} primary disabled={busy || !name.trim() || !description.trim()} />
        </>
      }
    >
      <div className={styles.dialogField}>
        <label className={styles.dialogLabel}>{t('skill_create_name_label')}</label>
        <input
          className={styles.dialogInput}
          value={name}
          onChange={(e) => setName(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-'))}
          placeholder="my-skill-name"
          autoFocus
        />
      </div>
      <div className={styles.dialogField}>
        <label className={styles.dialogLabel}>{t('skill_create_desc_label')}</label>
        <input
          className={styles.dialogInput}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="תיאור קצר של מה הסקיל עושה ומתי להשתמש בו"
        />
      </div>
      {err && <div className={styles.error}>{err}</div>}
    </Dialog>
  )
}

// ── Delete confirm dialog ─────────────────────────────────────────────────────

interface DeleteDialogProps {
  skillName: string
  onConfirm: () => Promise<void>
  onClose: () => void
}

const DeleteSkillDialog: React.FC<DeleteDialogProps> = ({ skillName, onConfirm, onClose }) => {
  const [busy, setBusy] = useState(false)

  const handleDelete = async () => {
    setBusy(true)
    try {
      await onConfirm()
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      title={t('skill_delete_title')}
      onClose={onClose}
      actions={
        <>
          <DialogButton label={t('cancel')} onClick={onClose} />
          <DialogButton label={busy ? '...' : t('skill_delete_confirm')} onClick={handleDelete} primary disabled={busy} />
        </>
      }
    >
      <p style={{ color: 'var(--on-surface-variant)', margin: 0 }}>
        למחוק את הסקיל &quot;{skillName}&quot; וכל הקבצים שלו?
      </p>
    </Dialog>
  )
}

// ── Main SkillsPage ───────────────────────────────────────────────────────────

const SkillsPage: React.FC = () => {
  const navigate = useNavigate()
  const [skills, setSkills] = useState<InstalledSkill[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showImportDialog, setShowImportDialog] = useState(false)
  const [showImportZipDialog, setShowImportZipDialog] = useState(false)
  const [showCreateDialog, setShowCreateDialog] = useState(false)
  const [deletingSkill, setDeletingSkill] = useState<InstalledSkill | null>(null)

  const loadSkills = useCallback(async () => {
    setLoading(true)
    try {
      const list = await skillsApi.list()
      setSkills(list)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Load failed')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadSkills() }, [loadSkills])

  // ── Toggle ─────────────────────────────────────────────────────────────────

  const handleToggle = async (skill: InstalledSkill, enabled: boolean) => {
    try {
      const updated = await skillsApi.setEnabled(skill.name, enabled)
      setSkills((prev) => prev.map((s) => (s.name === updated.name ? updated : s)))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Toggle failed')
    }
  }

  // ── Delete ─────────────────────────────────────────────────────────────────

  const handleDelete = async (name: string) => {
    await skillsApi.delete(name)
    setSkills((prev) => prev.filter((s) => s.name !== name))
    setDeletingSkill(null)
  }

  // ── Import ─────────────────────────────────────────────────────────────────

  const handleImport = async (text: string) => {
    await skillsApi.create({ importText: text })
    setShowImportDialog(false)
    await loadSkills()
  }

  const handleImportZip = async (file: File) => {
    await skillsApi.importZip(file)
    setShowImportZipDialog(false)
    await loadSkills()
  }

  // ── Export ─────────────────────────────────────────────────────────────────

  const handleExport = (skillName: string) => {
    const url = skillsApi.exportUrl(skillName)
    const a = document.createElement('a')
    a.href = url
    a.download = `${skillName}.zip`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  }

  // ── Create ─────────────────────────────────────────────────────────────────

  const handleCreate = async (name: string, description: string) => {
    const created = await skillsApi.create({ name, description, body: `# ${name}\n\n${description}\n` })
    setShowCreateDialog(false)
    await loadSkills()
    // Navigate to editor
    navigate(`/skills/${encodeURIComponent(created.name)}/edit`)
  }

  // ── Topbar action buttons ──────────────────────────────────────────────────

  const topbarActions = (
    <>
      <button
        className={styles.topbarBtn}
        onClick={() => setShowImportZipDialog(true)}
        aria-label="ייבוא מ-ZIP"
        title="ייבוא מ-ZIP"
        style={{ fontSize: 14 }}
      >
        📦
      </button>
      <button
        className={styles.topbarBtn}
        onClick={() => setShowImportDialog(true)}
        aria-label={t('skill_import_from_text')}
        title={t('skill_import_from_text')}
      >
        ⬇
      </button>
      <button
        className={styles.topbarBtn}
        onClick={() => setShowCreateDialog(true)}
        aria-label={t('skill_create_new')}
        title={t('skill_create_new')}
      >
        +
      </button>
    </>
  )

  return (
    <>
      <div className={styles.page}>
        <ScreenTopBar
          title={t('skills_title')}
          headingAriaLabel="Skills"
          onBack={() => navigate(-1)}
          actions={topbarActions}
        />

        <div className={styles.content}>
          {error && (
            <div className={styles.error} role="alert">
              {error}
              <button className={styles.errorClose} onClick={() => setError(null)}>✕</button>
            </div>
          )}

          {loading ? (
            <div className={styles.loading}>טוען סקילים…</div>
          ) : skills.length === 0 ? (
            <div className={styles.empty}>
              <div className={styles.emptyIcon} aria-hidden="true">✨</div>
              <p className={styles.emptyTitle}>{t('skills_empty_title')}</p>
              <p className={styles.emptyHint}>{t('skills_empty_hint')}</p>
            </div>
          ) : (
            skills.map((skill) => (
              <SkillCard
                key={skill.name}
                skill={skill}
                onEdit={() => navigate(`/skills/${encodeURIComponent(skill.name)}/edit`)}
                onToggle={(enabled) => handleToggle(skill, enabled)}
                onDelete={() => setDeletingSkill(skill)}
                onExport={() => handleExport(skill.name)}
              />
            ))
          )}
        </div>
      </div>

      {/* Dialogs rendered outside the page column */}
      {showImportZipDialog && (
        <ImportZipDialog
          onImport={handleImportZip}
          onClose={() => setShowImportZipDialog(false)}
        />
      )}
      {showImportDialog && (
        <ImportTextDialog
          onImport={handleImport}
          onClose={() => setShowImportDialog(false)}
        />
      )}
      {showCreateDialog && (
        <CreateSkillDialog
          onCreate={handleCreate}
          onClose={() => setShowCreateDialog(false)}
        />
      )}
      {deletingSkill && (
        <DeleteSkillDialog
          skillName={deletingSkill.name}
          onConfirm={() => handleDelete(deletingSkill.name)}
          onClose={() => setDeletingSkill(null)}
        />
      )}
    </>
  )
}

export default SkillsPage
