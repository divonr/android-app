/**
 * SkillEditorPage — R6 refactor.
 * Mirrors Android SkillEditorScreen.kt: monospace markdown editor for a skill's body.
 * Route: /skills/:name/edit
 */
import React, { useEffect, useState, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { skills as skillsApi } from '../api/client'
import type { InstalledSkill } from '../api/types'
import ScreenTopBar from '../components/ScreenTopBar'
import { t } from '../i18n/he'
import styles from './SkillEditorPage.module.css'

const SkillEditorPage: React.FC = () => {
  const { name } = useParams<{ name: string }>()
  const navigate = useNavigate()

  const [skill, setSkill] = useState<InstalledSkill | null>(null)
  const [body, setBody] = useState('')
  const [description, setDescription] = useState('')
  const [loading, setLoading] = useState(true)
  const [isDirty, setIsDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [savedMsg, setSavedMsg] = useState('')
  const [error, setError] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const skillName = name ? decodeURIComponent(name) : ''

  const load = useCallback(async () => {
    if (!skillName) return
    setLoading(true)
    try {
      const [list, content] = await Promise.all([
        skillsApi.list(),
        skillsApi.getContent(skillName),
      ])
      const found = list.find((s) => s.name === skillName)
      if (!found) {
        setError(t('skill_editor_not_found'))
        return
      }
      setSkill(found)
      setDescription(found.description)
      setBody(content ?? '')
      setIsDirty(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Load failed')
    } finally {
      setLoading(false)
    }
  }, [skillName])

  useEffect(() => { load() }, [load])

  const handleBodyChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setBody(e.target.value)
    setIsDirty(true)
    setSavedMsg('')
  }

  const handleDescChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setDescription(e.target.value)
    setIsDirty(true)
    setSavedMsg('')
  }

  const handleSave = async () => {
    if (!skill) return
    setSaving(true)
    setError(null)
    try {
      await skillsApi.create({
        name: skill.name,
        description: description.trim(),
        body: body,
      })
      setIsDirty(false)
      setSavedMsg(t('skill_editor_saved'))
      setTimeout(() => setSavedMsg(''), 2000)
    } catch (err) {
      setError(err instanceof Error ? err.message : t('skill_editor_save_error'))
    } finally {
      setSaving(false)
    }
  }

  // ── Topbar action: save button (shown only when dirty) ─────────────────────

  const topbarActions = (
    <>
      {isDirty && (
        <button
          className={`${styles.topbarBtn} ${styles.topbarBtnPrimary}`}
          onClick={handleSave}
          disabled={saving}
          aria-label={t('save')}
          title={t('save')}
        >
          {saving ? '…' : '💾'}
        </button>
      )}
      {savedMsg && (
        <span style={{ fontSize: 'var(--fs-body-small)', color: 'var(--accent-green)', whiteSpace: 'nowrap' }}>
          {savedMsg}
        </span>
      )}
    </>
  )

  if (loading) return (
    <div className={styles.page}>
      <ScreenTopBar title={skillName} headingAriaLabel="Skill Editor" onBack={() => navigate('/skills')} />
      <div className={styles.loading}>טוען…</div>
    </div>
  )

  if (error && !skill) return (
    <div className={styles.page}>
      <ScreenTopBar title={skillName} headingAriaLabel="Skill Editor" onBack={() => navigate('/skills')} />
      <div className={styles.error} role="alert">{error}</div>
    </div>
  )

  return (
    <div className={styles.page}>
      <ScreenTopBar
        title={skill?.name ?? skillName}
        headingAriaLabel="Skill Editor"
        onBack={() => navigate('/skills')}
        actions={topbarActions}
      />

      {/* Metadata: description */}
      <div className={styles.metaSection}>
        <div className={styles.metaField}>
          <label className={styles.metaLabel}>{t('skill_create_desc_label')}</label>
          <input
            className={styles.metaInput}
            value={description}
            onChange={handleDescChange}
            placeholder="תיאור קצר"
          />
        </div>
      </div>

      {error && <div className={styles.error} role="alert">{error}</div>}

      {/* Editor area — LTR, monospace */}
      <div className={styles.editorWrapper} dir="ltr">
        <textarea
          ref={textareaRef}
          className={styles.editor}
          value={body}
          onChange={handleBodyChange}
          spellCheck={false}
          aria-label="Skill body editor"
          placeholder={'# Skill Name\n\nDescribe the skill in markdown...'}
        />
      </div>
    </div>
  )
}

export default SkillEditorPage
