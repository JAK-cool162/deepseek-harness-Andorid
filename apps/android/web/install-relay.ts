/**
 * Side-effect module that patches the Worker global's `fetch`; imported first by
 * the Android Worker entry.
 * @module @deepseek-ai/dsh-android/web/install-relay
 */
import { installFetchRelay } from './relay.ts'

installFetchRelay(self)
