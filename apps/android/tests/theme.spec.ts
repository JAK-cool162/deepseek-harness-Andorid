/**
 * Style API guardrails and artifact rendering decisions: only bounded, present
 * stylesheets apply, and only HTML and SVG files render as artifacts.
 */
import { describe, expect, it, vi } from 'vitest'
import { artifactDocument, isRenderable } from '../web/artifact-viewer.ts'
import { THEME_MAX_BYTES, themeText } from '../web/theme.ts'

const bytes = (text: string): Uint8Array => new TextEncoder().encode(text)

describe('themeText', () => {
  it('applies a present stylesheet', () => {
    expect(themeText(bytes('body { color: red }'))).toBe('body { color: red }')
  })

  it('applies nothing for a missing or empty file', () => {
    expect(themeText(undefined)).toBeUndefined()
    expect(themeText(new Uint8Array(0))).toBeUndefined()
  })

  it('refuses a stylesheet over the size cap', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    expect(themeText(new Uint8Array(THEME_MAX_BYTES + 1))).toBeUndefined()
    expect(themeText(new Uint8Array(THEME_MAX_BYTES))).toBeDefined()
    expect(warn).toHaveBeenCalledOnce()
    warn.mockRestore()
  })
})

describe('artifact rendering', () => {
  it('renders HTML and SVG only', () => {
    expect(isRenderable('/dsh/workspace/a/report.HTML')).toBe(true)
    expect(isRenderable('chart.svg')).toBe(true)
    expect(isRenderable('notes.md')).toBe(false)
    expect(isRenderable('page.html.txt')).toBe(false)
  })

  it('wraps SVG in a scaling page and passes HTML through', () => {
    expect(artifactDocument('a.html', '<p>x</p>')).toBe('<p>x</p>')
    expect(artifactDocument('a.svg', '<svg></svg>')).toMatch(/^<!doctype html>.*<svg><\/svg>$/)
  })
})
