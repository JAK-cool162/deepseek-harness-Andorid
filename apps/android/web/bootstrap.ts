/**
 * Android page bootstrap, spliced ahead of the stock shell entry in
 * `android.html`. It restores the saved overlay, connects the host Worker, and
 * starts state persistence; a failed start with saved state offers a reset.
 * @module @deepseek-ai/dsh-android/web/bootstrap
 */
import DshWorker from './worker.ts?worker'
import { connectWorkerHost, holdWorkerHostBoot, IMAGE_FILE_NAME } from '@deepseek-ai/dsh-experimental-webworker-runtime/client'
import { clearSavedState, hasSavedState, startStatePersistence, STATE_URL } from './persistence.ts'

// Before the first await: the cached shell entry may evaluate while the state probe is in flight.
holdWorkerHostBoot()
const image = `preview/${IMAGE_FILE_NAME}`
const restored = await hasSavedState().catch((error: unknown) => {
  console.warn('dsh-android: saved state probe failed; starting without it', error)
  return false
})
const worker = new DshWorker({ name: 'dsh-host' })
try {
  await connectWorkerHost(worker, { image, overlays: restored ? [STATE_URL] : [] })
  startStatePersistence(worker)
} catch (error) {
  console.error('dsh-android: harness start failed', error)
  if (restored && confirm('DeepSeek Harness could not start from its saved data. Reset saved data and restart?')) {
    await clearSavedState()
    location.reload()
  }
}
