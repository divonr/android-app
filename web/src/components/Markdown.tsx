/**
 * Markdown — react-markdown renderer styled to match CodeBlockRenderer.kt,
 * TableRenderer.kt and MarkdownText.kt from the Android app.
 *
 * - Code blocks: dark bg + language label top-left + copy button top-right
 * - Inline code: surface-variant bg + primary-light color
 * - GFM tables: border/header from TableRenderer.kt
 * - KaTeX math (remark-math + rehype-katex)
 * - RTL-safe: dir="auto" on the wrapper so Hebrew flows naturally
 */

import React, { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeHighlight from 'rehype-highlight'
import 'katex/dist/katex.min.css'
import styles from './Markdown.module.css'
import { t } from '../i18n/he'

// ---------------------------------------------------------------------------
// Code block with language label + copy button
// ---------------------------------------------------------------------------

interface CodeBlockProps {
  className?: string
  children?: React.ReactNode
  inline?: boolean
}

const CodeBlock: React.FC<CodeBlockProps> = ({ className, children, inline, ...rest }) => {
  const [copied, setCopied] = useState(false)

  const code = String(children ?? '').replace(/\n$/, '')
  // rehype-highlight prefixes the class with `hljs ` (e.g. "hljs language-js"),
  // so a startsWith check misses real fenced blocks. Detect a block by the
  // presence of `language-` anywhere, or by a multi-line body.
  const isBlock = (className?.includes('language-') ?? false) || code.includes('\n')
  const langMatch = className?.match(/language-(\w+)/)
  const lang = langMatch ? langMatch[1] : ''

  const handleCopy = () => {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  if (!isBlock || inline) {
    return (
      <code className={`${styles.inlineCode} ${className ?? ''}`} dir="ltr" {...rest}>
        {children}
      </code>
    )
  }

  return (
    // dir="ltr" prevents the Unicode BIDI algorithm from reversing punctuation
    // in code blocks when the parent has dir="rtl".
    <div className={styles.codeWrapper} dir="ltr">
      {/* Header bar: language label + copy button */}
      <div className={styles.codeHeader}>
        <span className={styles.codeLang}>{lang || 'code'}</span>
        <button
          className={styles.copyBtn}
          onClick={handleCopy}
          aria-label={t('copy_code')}
        >
          {copied ? t('copied') : t('copy_code')}
        </button>
      </div>
      <code className={`${styles.codeBody} ${className ?? ''}`} {...rest}>
        {children}
      </code>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Main Markdown component
// ---------------------------------------------------------------------------

interface MarkdownProps {
  content: string
  className?: string
}

const Markdown: React.FC<MarkdownProps> = ({ content, className }) => {
  return (
    <div className={`${styles.markdown} ${className ?? ''}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex, rehypeHighlight]}
        components={{
          code: CodeBlock as React.ComponentType<React.HTMLAttributes<HTMLElement>>,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}

export default Markdown
