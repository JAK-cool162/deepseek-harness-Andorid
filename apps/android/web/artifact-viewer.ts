/**
 * Artifact viewer: renders HTML and SVG files the agent opens or presents
 * instead of showing their source. The Worker's `xdg-open` reaches the page as
 * the runtime's cancelable view-text event; this module cancels it for
 * renderable files and shows them full screen in a sandboxed frame.
 *
 * The frame has scripts but an opaque origin (no `allow-same-origin`), so an
 * artifact cannot read the app's cookies, call the loopback server, or reach
 * the harness.
 * @module @deepseek-ai/dsh-android/web/artifact-viewer
 */
import { VIEW_TEXT_EVENT, type ViewTextDetail } from '@deepseek-ai/dsh-experimental-webworker-runtime/client'

/** Native endpoint that writes a POST body to the phone's Downloads folder under the `name` query. */
export const SAVE_URL = '/__dsh_save'
/** Loopback path that opens Android's share sheet for the posted file. */
export const SHARE_URL = '/__dsh_share'

/** File names this viewer renders; everything else keeps the runtime's text viewer. */
const RENDERABLE = /\.(?:html?|svg)$/i

/**
 * Whether a path names a file the viewer renders.
 * @param path - VFS path from the view-text event.
 * @returns True for HTML and SVG files.
 */
export function isRenderable(path: string): boolean {
  return RENDERABLE.test(path)
}

/**
 * The frame document for an artifact: SVG is wrapped so it scales to the screen.
 * @param path - VFS path.
 * @param text - File text.
 * @returns HTML for the frame's `srcdoc`.
 */
export function artifactDocument(path: string, text: string): string {
  if (!/\.svg$/i.test(path)) return text
  return `<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;display:grid;place-items:center;background:#fff}svg{max-width:100%;max-height:100%}</style>${text}`
}

const STYLE = `
  [data-dsh-artifact] { position: fixed; inset: 0; z-index: 2147483000; display: flex; flex-direction: column;
    background: var(--dsw-alias-bg-base, #16171b); color: var(--dsw-alias-label-primary, #f4f5f7); }
  [data-dsh-artifact] header { display: flex; align-items: center; gap: 8px; padding: 8px 12px;
    border-bottom: 1px solid var(--dsw-alias-border-l1, rgb(127 127 127 / 25%)); }
  [data-dsh-artifact] h2 { flex: 1; min-width: 0; margin: 0; font: 600 15px/22px system-ui, sans-serif;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  [data-dsh-artifact] button { min-height: 40px; padding: 0 14px; border-radius: 20px; border: 0;
    font: 500 14px system-ui, sans-serif; background: rgb(127 127 127 / 18%); color: inherit; }
  [data-dsh-artifact] iframe { flex: 1; width: 100%; border: 0; background: #fff; }
`

/**
 * Show one artifact full screen until closed.
 * @param path - VFS path, used for the title and the saved file name.
 * @param text - File text.
 */
export function showArtifact(path: string, text: string): void {
  document.querySelector('[data-dsh-artifact]')?.remove()
  if (document.querySelector('style[data-dsh-artifact-style]') === null) {
    const style = document.createElement('style')
    style.dataset.dshArtifactStyle = ''
    style.textContent = STYLE
    document.head.append(style)
  }
  const name = path.split('/').pop() ?? 'artifact.html'
  const root = document.createElement('div')
  root.dataset.dshArtifact = ''
  root.setAttribute('role', 'dialog')
  root.setAttribute('aria-label', name)
  const header = document.createElement('header')
  const title = document.createElement('h2')
  title.textContent = name
  const save = document.createElement('button')
  save.type = 'button'
  save.textContent = 'Save'
  save.addEventListener('click', () => {
    save.disabled = true
    void fetch(`${SAVE_URL}?name=${encodeURIComponent(name)}`, { method: 'POST', body: text })
      .then((response) => { save.textContent = response.ok ? 'Saved to Downloads' : 'Save failed' })
      .catch(() => { save.textContent = 'Save failed' })
  })
  const share = document.createElement('button')
  share.type = 'button'
  share.textContent = 'Share'
  share.addEventListener('click', () => {
    void fetch(`${SHARE_URL}?name=${encodeURIComponent(name)}`, { method: 'POST', body: text })
      .then((response) => { if (!response.ok) share.textContent = 'Share failed' })
      .catch(() => { share.textContent = 'Share failed' })
  })
  const close = document.createElement('button')
  close.type = 'button'
  close.textContent = 'Close'
  close.addEventListener('click', () => { root.remove() })
  header.append(title, share, save, close)
  const frame = document.createElement('iframe')
  frame.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-popups')
  frame.title = name
  frame.srcdoc = artifactDocument(path, text)
  root.append(header, frame)
  document.body.append(root)
  close.focus()
}

/**
 * Render renderable files the Worker opens instead of showing their source.
 * @returns Stops intercepting.
 */
export function installArtifactViewer(): () => void {
  const listener = (event: Event): void => {
    const { path, text } = (event as CustomEvent<ViewTextDetail>).detail
    if (!isRenderable(path)) return
    event.preventDefault()
    showArtifact(path, text)
  }
  globalThis.addEventListener(VIEW_TEXT_EVENT, listener)
  return () => { globalThis.removeEventListener(VIEW_TEXT_EVENT, listener) }
}
