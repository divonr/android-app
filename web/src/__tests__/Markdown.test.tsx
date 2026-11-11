import React from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import Markdown from '../components/Markdown'

// Stub CSS modules
vi.mock('../components/Markdown.module.css', () => ({
  default: new Proxy(
    {},
    { get: (_t, prop) => String(prop) },
  ),
}))

// KaTeX CSS import — no-op in test environment
vi.mock('katex/dist/katex.min.css', () => ({}))

const SAMPLE_CONTENT = `
# Heading

This is a paragraph with **bold** and _italic_ text.

## Table

| Name | Value |
|------|-------|
| foo  | 42    |
| bar  | 99    |

## Code

\`\`\`javascript
const x = 1 + 1;
console.log(x);
\`\`\`

## Math

Inline math: $x^2 + y^2 = z^2$

Display math:

$$
E = mc^2
$$
`

describe('Markdown component', () => {
  it('renders without crashing', () => {
    const { container } = render(<Markdown content={SAMPLE_CONTENT} />)
    expect(container).toBeTruthy()
  })

  it('renders heading', () => {
    render(<Markdown content={SAMPLE_CONTENT} />)
    expect(screen.getByRole('heading', { level: 1, name: /heading/i })).toBeInTheDocument()
  })

  it('renders table rows', () => {
    render(<Markdown content={SAMPLE_CONTENT} />)
    // GFM table should produce cells
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByText('foo')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('renders code block', () => {
    const { container } = render(<Markdown content={SAMPLE_CONTENT} />)
    // rehype-highlight splits code into spans; check the pre/code element exists
    const pre = container.querySelector('pre')
    expect(pre).toBeInTheDocument()
    // The raw text content of the pre block should contain our code
    expect(pre?.textContent).toContain('const')
    expect(pre?.textContent).toContain('console.log')
  })

  it('renders bold text', () => {
    render(<Markdown content="This is **bold** text." />)
    const bold = screen.getByText('bold')
    expect(bold.tagName.toLowerCase()).toBe('strong')
  })

  it('renders inline math via KaTeX (does not crash)', () => {
    // KaTeX renders math — just verify no throw and something appears
    const { container } = render(<Markdown content="Inline $x^2$" />)
    // KaTeX output wraps in .katex spans or similar
    expect(container.innerHTML).toBeTruthy()
  })

  it('renders empty content without error', () => {
    const { container } = render(<Markdown content="" />)
    expect(container).toBeTruthy()
  })
})
