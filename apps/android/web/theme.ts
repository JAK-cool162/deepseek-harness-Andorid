/**
 * App style API. The agent (or the user) writes CSS to {@link THEME_PATH} in the
 * Worker's home; the page applies it live. CSS cannot run code, the file is
 * capped at {@link THEME_MAX_BYTES}, and launching in safe mode skips it, so a
 * broken style is always recoverable.
 *
 * While the agent works, the native shell sets `data-dsh-working` on the root
 * element, so a style can animate a background, show a GIF, and so on.
 * @module @deepseek-ai/dsh-android/web/theme
 */

/** Root-relative VFS path of the custom stylesheet. */
export const THEME_PATH = 'home/android-ui/theme.css'

/** Largest stylesheet applied; a bigger file is ignored with a console warning. */
export const THEME_MAX_BYTES = 512 * 1024

/** Interval between checks of the stylesheet while the page is visible. */
export const THEME_POLL_MS = 2_000

/** Root attribute present while the agent works. */
export const WORKING_ATTRIBUTE = 'data-dsh-working'

type ReadReply = { ok: true; bytes?: Uint8Array } | { ok: false; error: string }

/**
 * Read one Worker file through the runtime's `vfs-read` frame.
 * @param worker - The host Worker.
 * @param path - Root-relative path under `home/` or `workspace/`.
 * @returns The bytes, or undefined when the file does not exist.
 * @throws When the Worker refuses the read.
 */
export async function readWorkerFile(worker: Worker, path: string): Promise<Uint8Array | undefined> {
  const channel = new MessageChannel()
  const reply = new Promise<ReadReply>((resolve) => {
    channel.port1.onmessage = (event: MessageEvent<ReadReply>): void => { resolve(event.data) }
  })
  worker.postMessage({ t: 'vfs-read', path, port: channel.port2 }, [channel.port2])
  const result = await reply
  channel.port1.close()
  if (!result.ok) throw new Error(result.error)
  return result.bytes
}

/**
 * The stylesheet text to apply, or undefined to apply none.
 * @param bytes - File bytes, or undefined when absent.
 * @returns The CSS, or undefined when absent, empty, or over the size cap.
 */
export function themeText(bytes: Uint8Array | undefined): string | undefined {
  if (bytes === undefined || bytes.length === 0) return undefined
  if (bytes.length > THEME_MAX_BYTES) {
    console.warn(`dsh-android: ${THEME_PATH} is ${String(bytes.length)} bytes, over the ${String(THEME_MAX_BYTES)}-byte limit; not applied`)
    return undefined
  }
  return new TextDecoder().decode(bytes)
}

/**
 * Apply the custom stylesheet now and whenever it changes.
 * @param worker - The connected host Worker.
 * @returns Stops watching and removes the style.
 */
export function startTheme(worker: Worker): () => void {
  const style = document.createElement('style')
  style.dataset.dshAndroidTheme = ''
  // Last in <head>, so the custom rules win ties against the shell's own.
  document.head.append(style)
  let applied: string | undefined
  let reading = false
  const check = async (): Promise<void> => {
    if (reading || document.visibilityState !== 'visible') return
    reading = true
    try {
      const css = themeText(await readWorkerFile(worker, THEME_PATH)) ?? ''
      if (css !== applied) {
        style.textContent = css
        applied = css
      }
    } catch (error) {
      console.warn('dsh-android: theme read failed', error)
    } finally {
      reading = false
    }
  }
  void check()
  const timer = setInterval(() => { void check() }, THEME_POLL_MS)
  return () => {
    clearInterval(timer)
    style.remove()
  }
}

/**
 * Reflect the native shell's working state on the root element.
 * @param working - Whether the agent is working.
 */
export function setWorking(working: boolean): void {
  document.documentElement.toggleAttribute(WORKING_ATTRIBUTE, working)
}
