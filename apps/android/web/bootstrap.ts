/**
 * Android page bootstrap, spliced ahead of the stock shell entry in
 * `android.html`. It restores the saved overlay and the app's default files,
 * applies the mobile base style, connects the host Worker, starts state
 * persistence, renders HTML artifacts,
 * and applies the custom style unless the page runs in safe mode (`?safe`).
 * A failed start with saved state offers a reset.
 * @module @deepseek-ai/dsh-android/web/bootstrap
 */
import DshWorker from './worker.ts?worker'
import { connectWorkerHost, holdWorkerHostBoot, IMAGE_FILE_NAME } from '@deepseek-ai/dsh-experimental-webworker-runtime/client'
import { installArtifactViewer } from './artifact-viewer.ts'
import { clearSavedState, hasSavedState, startStatePersistence, STATE_URL } from './persistence.ts'
import { setWorking, startTheme } from './theme.ts'
import mobileBase from './mobile-base.css?inline'

// Before the first await: the cached shell entry may evaluate while the state probe is in flight.
holdWorkerHostBoot()
const base = document.createElement('style')
base.dataset.dshAndroidBase = ''
base.textContent = mobileBase
document.head.append(base)
const image = `preview/${IMAGE_FILE_NAME}`
/** App-owned files (the Android skill), applied after saved state so app updates replace them. */
const DEFAULTS_OVERLAY = 'android/defaults.tar.gz'
const safeMode = new URLSearchParams(location.search).has('safe')
// The native shell reports when the agent starts and stops working.
;(globalThis as { __DSH_ANDROID_WORKING__?: (working: boolean) => void }).__DSH_ANDROID_WORKING__ = setWorking
installArtifactViewer()
const restored = await hasSavedState().catch((error: unknown) => {
  console.warn('dsh-android: saved state probe failed; starting without it', error)
  return false
})
const worker = new DshWorker({ name: 'dsh-host' })
try {
  await connectWorkerHost(worker, { image, overlays: restored ? [STATE_URL, DEFAULTS_OVERLAY] : [DEFAULTS_OVERLAY] })
  startStatePersistence(worker)
  if (safeMode) console.warn('dsh-android: safe mode; the custom style is not applied')
  else startTheme(worker)
} catch (error) {
  console.error('dsh-android: harness start failed', error)
  if (restored && confirm('DeepSeek Harness could not start from its saved data. Reset saved data and restart?')) {
    await clearSavedState()
    location.reload()
  }
}
