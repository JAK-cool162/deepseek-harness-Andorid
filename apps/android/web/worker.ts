/**
 * Android host Worker entry: installs the fetch relay, then evaluates the
 * unchanged runtime Worker bundle. Static import order runs the relay first;
 * shell processes start from this same URL and inherit it.
 * @module @deepseek-ai/dsh-android/web/worker
 */
import './install-relay.ts'
import '@deepseek-ai/dsh-experimental-webworker-runtime/worker'
