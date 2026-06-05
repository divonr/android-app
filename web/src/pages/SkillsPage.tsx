import React, { useEffect, useState, useCallback } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { skills as skillsApi } from '../api/client'
import type { InstalledSkill } from '../api/types'
import styles from './SkillsPage.module.css'

// ─── Skill Editor ─────────────────────────────────────────────────────────────

interface SkillEditorProps {
  skill: InstalledSkill | null   // null = new skill
  onSave: (params: { name?: string; description?: string; body?: string }) => Promise<void>
  onCancel: () => void
}

const SkillEditor: React.FC<SkillEditorProps> = ({ skill, onSave, onCancel }) => {
  const [name, setName] = useState(skill?.name ?? '')
  const [description, setDescription] = useState(skill?.description ?? '')
  const [body, setBody] = useState(skill?.body ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  // Load full body if not present
  useEffect(() => {
    if (skill && !skill.body) {
      skillsApi.getContent(skill.name).then((content) => setBody(content)).catch(() => {})
    }
  }, [skill])

  const handleSave = async () => {
    if (!name.trim()) {
      setError('Skill name is required')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onSave({ name: name.trim(), description: description.trim(), body: body.trim() })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
      setBusy(false)
    }
  }

  return (
    <div className={styles.editor}>
      <div className={styles.editorHeader}>
        <h2 className={styles.editorTitle}>{skill ? `Edit: ${skill.name}` : 'New Skill'}</h2>
        <button className={styles.btnSecondary} onClick={onCancel}>Cancel</button>
      </div>

      <div className={styles.formRow}>
        <label>Skill Name</label>
        <input
          className={styles.input}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="my_skill"
          disabled={!!skill}
        />
      </div>
      <div className={styles.formRow}>
        <label>Description</label>
        <input
          className={styles.input}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="What this skill does"
        />
      </div>
      <div className={styles.formRow}>
        <label>Body (Markdown)</label>
        <textarea
          className={`${styles.textarea} ${styles.editorBody}`}
          value={body}
          onChange={(e) => setBody(e.target.value)}
          placeholder="# Skill Name&#10;&#10;Describe the skill in markdown..."
          rows={20}
        />
      </div>

      {error && <div className={styles.error}>{error}</div>}

      <div className={styles.editorFooter}>
        <button className={styles.btnPrimary} onClick={handleSave} disabled={busy}>
          {busy ? 'Saving…' : 'Save Skill'}
        </button>
      </div>
    </div>
  )
}

// ─── Import skill ────────────────────────────────────────────────────────────

interface ImportSkillProps {
  onImport: (text: string) => Promise<void>
  onCancel: () => void
}

const ImportSkillForm: React.FC<ImportSkillProps> = ({ onImport, onCancel }) => {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const handleImport = async () => {
    if (!text.trim()) {
      setError('Paste skill text to import')
      return
    }
    setBusy(true)
    setError('')
    try {
      await onImport(text)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Import failed')
      setBusy(false)
    }
  }

  return (
    <div className={styles.editor}>
      <div className={styles.editorHeader}>
        <h2 className={styles.editorTitle}>Import Skill</h2>
        <button className={styles.btnSecondary} onClick={onCancel}>Cancel</button>
      </div>
      <div className={styles.formRow}>
        <label>Paste skill content (Markdown)</label>
        <textarea
          className={`${styles.textarea} ${styles.editorBody}`}
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder="Paste full skill markdown here…"
          rows={16}
          autoFocus
        />
      </div>
      {error && <div className={styles.error}>{error}</div>}
      <div className={styles.editorFooter}>
        <button className={styles.btnPrimary} onClick={handleImport} disabled={busy || !text.trim()}>
          {busy ? 'Importing…' : 'Import'}
        </button>
      </div>
    </div>
  )
}

// ─── Main SkillsPage ──────────────────────────────────────────────────────────

type ViewMode = 'list' | 'edit' | 'new' | 'import'

const SkillsPage: React.FC = () => {
  const [skills, setSkills] = useState<InstalledSkill[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [viewMode, setViewMode] = useState<ViewMode>('list')
  const [editingSkill, setEditingSkill] = useState<InstalledSkill | null>(null)

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

  // ── Toggle skill ──────────────────────────────────────────────────────────

  const handleToggle = async (skill: InstalledSkill) => {
    try {
      const updated = await skillsApi.setEnabled(skill.name, !skill.enabled)
      setSkills((prev) => prev.map((s) => s.name === updated.name ? updated : s))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Toggle failed')
    }
  }

  // ── Delete skill ──────────────────────────────────────────────────────────

  const handleDelete = async (name: string) => {
    if (!window.confirm(`Delete skill "${name}"?`)) return
    try {
      await skillsApi.delete(name)
      setSkills((prev) => prev.filter((s) => s.name !== name))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Delete failed')
    }
  }

  // ── Save skill (create or update) ─────────────────────────────────────────

  const handleSave = async (params: { name?: string; description?: string; body?: string }) => {
    if (editingSkill) {
      // Update existing: patch enabled stays the same; body update via create with name
      await skillsApi.create({ ...params, name: editingSkill.name })
    } else {
      await skillsApi.create(params)
    }
    setViewMode('list')
    setEditingSkill(null)
    await loadSkills()
  }

  // ── Import skill ──────────────────────────────────────────────────────────

  const handleImport = async (text: string) => {
    await skillsApi.create({ importText: text })
    setViewMode('list')
    await loadSkills()
  }

  // ── Render ────────────────────────────────────────────────────────────────

  if (viewMode === 'edit' || viewMode === 'new') {
    return (
      <div className={styles.page}>
        <SkillEditor
          skill={viewMode === 'edit' ? editingSkill : null}
          onSave={handleSave}
          onCancel={() => { setViewMode('list'); setEditingSkill(null) }}
        />
      </div>
    )
  }

  if (viewMode === 'import') {
    return (
      <div className={styles.page}>
        <ImportSkillForm
          onImport={handleImport}
          onCancel={() => setViewMode('list')}
        />
      </div>
    )
  }

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <h1 className={styles.title}>Skills</h1>
        <div className={styles.headerActions}>
          <button className={styles.btnSecondary} onClick={() => setViewMode('import')}>
            Import
          </button>
          <button className={styles.btnPrimary} onClick={() => { setEditingSkill(null); setViewMode('new') }}>
            + New Skill
          </button>
        </div>
      </div>

      {error && (
        <div className={styles.error} role="alert">
          {error}
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {loading ? (
        <div className={styles.loading}>Loading skills…</div>
      ) : skills.length === 0 ? (
        <div className={styles.empty}>
          <p>No skills installed yet.</p>
          <p>Skills extend the assistant's capabilities with custom instructions.</p>
        </div>
      ) : (
        <div className={styles.list}>
          {skills.map((skill) => (
            <div key={skill.name} className={`${styles.skillRow} ${!skill.enabled ? styles.skillRowDisabled : ''}`}>
              <div className={styles.skillInfo}>
                <div className={styles.skillHeader}>
                  <span className={styles.skillName}>{skill.name}</span>
                  {skill.enabled && <span className={styles.enabledBadge}>Active</span>}
                </div>
                <span className={styles.skillDescription}>{skill.description || 'No description'}</span>
              </div>
              <div className={styles.skillActions}>
                <label className={styles.toggle} title={skill.enabled ? 'Disable' : 'Enable'}>
                  <input
                    type="checkbox"
                    checked={skill.enabled}
                    onChange={() => handleToggle(skill)}
                  />
                  <span className={styles.toggleSlider} />
                </label>
                <button
                  className={styles.iconBtn}
                  onClick={() => { setEditingSkill(skill); setViewMode('edit') }}
                  title="Edit"
                >
                  ✏️
                </button>
                <button
                  className={`${styles.iconBtn} ${styles.deleteBtn}`}
                  onClick={() => handleDelete(skill.name)}
                  title="Delete"
                >
                  🗑
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default SkillsPage
