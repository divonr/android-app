import React, { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeHighlight from 'rehype-highlight'
import 'katex/dist/katex.min.css'
import styles from './Markdown.module.css'

// ---------------------------------------------------------------------------
// Code block with copy button
// ---------------------------------------------------------------------------

interface CodeBlockProps {
  className?: string
  children?: React.ReactNode
}

const CodeBlock: React.FC<CodeBlockProps> = ({ className, children, ...rest }) => {
  const [copied, setCopied] = useState(false)

  const isBlock = className?.startsWith('language-')
  const code = String(children ?? '').replace(/\n$/, '')

  const handleCopy = () => {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    })
  }

  if (!isBlock) {
    return (
      <code className={`${styles.inlineCode} ${className ?? ''}`} {...rest}>
        {children}
      </code>
    )
  }

  return (
    <div className={styles.codeWrapper}>
      <button
        className={styles.copyBtn}
        onClick={handleCopy}
        aria-label="Copy code"
      >
        {copied ? 'Copied!' : 'Copy'}
      </button>
      <code className={className} {...rest}>
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
