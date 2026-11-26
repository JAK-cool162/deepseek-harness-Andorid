/**
 * Saved Worker state. The page asks the host Worker for an overlay archive of
 * `home/` and `workspace/` and stores it through the native server, which
 * serves it back as the first-start overlay on the next launch.
 * @module @deepseek-ai/dsh-android/web/persistence
 */

/** Native endpoint: GET returns the saved archive (404 when none), PUT replaces it, DELETE removes it. */
export const STATE_URL = '/__dsh_state'

/** Minimum interval between snapshots while the page stays visible. */
export const SNAPSHOT_INTERVAL_MS = 20_000

/** The reply the runtime posts on a `vfs-export` port. */
type ExportReply = { ok: true; archive: Uint8Array } | { ok: false; error: string }

/**
 * Request one overlay archive from the host Worker.
 * @param worker - The host Worker.
 * @returns The gzip-compressed archive.
 * @throws When the Worker refuses the export.
 */
export async function requestSnapshot(worker: Worker): Promise<Uint8Array> {
  const channel = new MessageChannel()
  const reply = new Promise<ExportReply>((resolve) => {
    channel.port1.onmessage = (event: MessageEvent<ExportReply>): void => { resolve(event.data) }
  })
  worker.postMessage({ t: 'vfs-export', port: channel.port2 }, [channel.port2])
  const result = await reply
  channel.port1.close()
  if (!result.ok) throw new Error(result.error)
  return result.archive
}

/**
 * Whether the native server holds a saved archive.
 * @returns True when a GET of the state endpoint would return one.
 */
export async function hasSavedState(): Promise<boolean> {
  const response = await fetch(STATE_URL, { method: 'HEAD', cache: 'no-store' })
  return response.ok
}

/**
 * Delete the saved archive.
 * @returns Resolves once the native server removed it.
 */
export async function clearSavedState(): Promise<void> {
  await fetch(STATE_URL, { method: 'DELETE' })
}

/**
 * Snapshot on an interval while visible and whenever the page becomes hidden,
 * uploading only archives whose bytes changed.
 * @param worker - The connected host Worker.
 * @returns Stops the schedule.
 */
export function startStatePersistence(worker: Worker): () => void {
  let last = ''
  let running = false
  const save = async (): Promise<void> => {
    if (running) return
    running = true
    try {
      const archive = await requestSnapshot(worker)
      const digest = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', archive as Uint8Array<ArrayBuffer>)),
        byte => byte.toString(16).padStart(2, '0')).join('')
      if (digest === last) return
      const response = await fetch(STATE_URL, { method: 'PUT', body: archive as Uint8Array<ArrayBuffer> })
      if (!response.ok) throw new Error(`state upload failed with ${String(response.status)}`)
      last = digest
    } catch (error) {
      console.warn('dsh-android: state snapshot failed', error)
    } finally {
      running = false
    }
  }
  const timer = setInterval(() => { if (document.visibilityState === 'visible') void save() }, SNAPSHOT_INTERVAL_MS)
  const onVisibility = (): void => { if (document.visibilityState === 'hidden') void save() }
  document.addEventListener('visibilitychange', onVisibility)
  ;(globalThis as { __DSH_ANDROID_SAVE__?: () => Promise<void> }).__DSH_ANDROID_SAVE__ = save
  return () => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisibility)
  }
}
