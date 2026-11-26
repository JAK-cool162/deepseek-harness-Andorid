# `@deepseek-ai/dsh-android`

English | [中文](README.zh.md)

The Android application runs DeepSeek Harness on the phone itself. The whole harness plugin tree runs inside the WebView's Web Worker through the [browser-worker runtime](../../packages/experimental/webworker-runtime/README.md), so no computer or server is needed. Only the model provider is contacted over the network.

## How it works

| Part | Owner | Role |
|---|---|---|
| Web UI and VFS image | [`apps/web`](../web/README.md) `build:preview` | The shipped Web shell plus the packed harness image that the Worker mounts as its filesystem. |
| Bootstrap page `android.html` | [`web/bootstrap.ts`](web/bootstrap.ts) | Restores the saved state overlay, connects the host Worker, and saves state while the app runs. |
| Host Worker | [`web/worker.ts`](web/worker.ts) | Installs the [fetch relay](web/relay.ts), then runs the unchanged runtime Worker bundle. |
| Loopback server | [`LocalServer.java`](android/app/src/main/java/ai/deepseek/harness/LocalServer.java) | Serves the assets on `127.0.0.1`, stores the state archive, and relays cross-origin requests natively. |
| Activity | [`MainActivity.java`](android/app/src/main/java/ai/deepseek/harness/MainActivity.java) | Hosts the WebView, file picker, and external links, and saves on pause. |

The relay exists because browsers block responses from hosts that send no CORS headers. The Worker rewrites every cross-origin `fetch` to `/__dsh_relay/<encoded URL>`. The server forwards it with the same method, headers, and body and streams the response back chunk by chunk, so model replies stream as they arrive. The server listens only on `127.0.0.1`. Every request except the boot redirect must carry a cookie holding a random per-launch token, so other apps on the device cannot use the relay or read saved state.

Saved state is the Worker's `home/` (settings, credentials, Session logs) and `workspace/` (files and the default Workspace). The page asks the Worker for a `vfs-export` overlay archive every 20 seconds while visible and whenever the app goes to the background, and uploads it only when it changed. The server keeps it in app-private storage, with the previous copy as `harness-state.tar.gz.prev`. The next launch passes it to the Worker as an overlay. If a start from saved data fails, the page offers to reset it.

## Phone features

- **Touch behavior**: [`web/mobile-base.css`](web/mobile-base.css) stops controls from starting text selection or showing a tap highlight, adds press feedback, and fades menus and dialogs in; it is off under reduced motion. The page stays clear of the status bar, display cutout, navigation bar, and keyboard.
- **Artifacts**: the `present` tool's HTML files open in the shell's preview tab. Any HTML or SVG file the Worker opens with `xdg-open` renders full screen in a sandboxed frame ([`web/artifact-viewer.ts`](web/artifact-viewer.ts)) with **Save**, which writes it to Downloads through `/__dsh_save`.
- **Style API**: the page applies `/dsh/home/android-ui/theme.css` live, read through the runtime's `vfs-read` frame every two seconds ([`web/theme.ts`](web/theme.ts)). Only CSS applies, files over 512 KB are ignored, and the agent's write outside the workspace needs the user's approval. `html[data-dsh-working]` is set while the agent works. Long-press the app icon and choose **Safe mode** to start without the custom style.
- **Agent skill**: [`defaults/`](defaults/) is packed as `android/defaults.tar.gz` and applied after saved state on every start, so the `android-app` skill that teaches the agent these features stays current.
- **Working notification**: while relayed requests are in flight, and for 20 seconds after the last one, [`WorkingService`](android/app/src/main/java/ai/deepseek/harness/WorkingService.java) runs in the foreground with an ongoing notification and elapsed time, holds a partial wake lock, and asks Android 16 to promote the notification to a live update. When work ends while the app is hidden, it posts a "finished" notification. The app asks for notification permission on first start.

## Build

Requirements: Node `^24.22.3 || >=26.13.1`, pnpm, JDK 17 or newer, and the Android SDK (platform 35, build-tools 35) with `ANDROID_HOME` set or `android/local.properties` naming `sdk.dir`.

```sh
pnpm install
pnpm run build:artifacts
pnpm --filter @deepseek-ai/dsh-web-frontend run build:preview
pnpm --filter @deepseek-ai/dsh-android run build:apk
```

`build:apk` builds the bootstrap with Vite, assembles `android/app/src/main/assets/www/`, and runs `./gradlew assembleRelease`. The APK is written to `android/app/build/outputs/apk/release/app-release.apk`. The Android asset pipeline strips a `.gz` suffix and inflates the file, so the assembly stores every gzip archive as `<name>.gz.raw` and the server maps requests back.

Release signing reads `DSH_ANDROID_KEYSTORE`, `DSH_ANDROID_KEYSTORE_PASSWORD`, `DSH_ANDROID_KEY_ALIAS`, and `DSH_ANDROID_KEY_PASSWORD`. Without them the release APK is signed with the local debug key, which installs normally but cannot be updated in place by an APK signed with another key.

Native tests run with `./gradlew testReleaseUnitTest` from `android/`; the relay's TypeScript tests run with `pnpm vitest run apps/android/tests`.

## Install and use

1. Copy `app-release.apk` to the phone and open it; allow installs from that source when Android asks.
2. Open **DeepSeek Harness**, continue past the preview notice, and choose **Add API Key** with a key from the DeepSeek platform, or **Sign in**.
3. Chat in the default Workspace. Files the agent writes live in the app's `workspace/` and survive restarts.

Android 8.0 (API 26) or newer with an up-to-date Android System WebView is required.

## Known Limitations and Deferred Work

- **Runtime limits apply**: the Worker shell is not bash and has no `git` or network tools, PTC Node programs and Office conversion are unavailable, and plugin installation fails; see the runtime's [limitations](../../packages/experimental/webworker-runtime/README.md#known-limitations-and-deferred-work).
- **State is saved by snapshot**: a change made less than 20 seconds before Android kills the app in the background can be lost. Each snapshot holds the whole `home/` and `workspace/` trees, so very large workspaces cost memory and storage time.
- **Background work depends on the working service**: Android refuses to start a foreground service while the app is in the background, so a turn that goes idle for 20 seconds in the background and then resumes runs without the notification and wake lock.
- **Island display is up to the system**: the working notification requests promotion on Android 16; vendor islands such as HyperOS show it only when they support that request.
- **Plugin installation is unavailable**: installing needs a package-manager process, which the Worker cannot start.
- **Downloads from the page are not handled**: the WebView has no download handler for files the UI offers to save.
- **The PATCH method cannot be relayed**: Android's `HttpURLConnection` refuses it, and the relay answers 405.
