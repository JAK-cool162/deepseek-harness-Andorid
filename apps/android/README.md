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
- **Agent skill**: [`defaults/`](defaults/) is packed as `android/defaults.tar.gz` and applied after saved state on every start, so the `android-app` skill that teaches the agent these features, and the style presets under `home/android-ui/presets/` such as `liquid-glass.css`, stay current.
- **Working notification**: while relayed requests are in flight, and for 20 seconds after the last one, [`WorkingService`](android/app/src/main/java/ai/deepseek/harness/WorkingService.java) runs in the foreground with an ongoing notification and elapsed time, holds a partial wake lock, and asks Android 16 to promote the notification to a live update. When work ends while the app is hidden, it posts a "finished" notification. The app asks for notification permission on first start.

## Full engine (native)

The on-device harness cannot start processes, so plugin installation, real shells, git, and Python need the full harness. **App settings → Full engine → Install or repair** sets it up inside the app with no other apps. [`NativeInstaller`](android/app/src/main/java/ai/deepseek/harness/NativeInstaller.java) resolves Termux's prebuilt Android packages (bash, coreutils, Node.js 24, npm, git, gh, Python, and their libraries, about 55 MB) from the Termux repository index, verifies each by SHA-256, unpacks it into the app's prefix `files/usr`, and rewrites the Termux paths in symbolic links, script shebangs, and git's shell path; paths compiled into Termux binaries that the engine still needs, such as OpenSSL's configuration file, terminfo, and git's system configuration, are pointed at the app's prefix through environment variables, because Termux's own directory belongs to another app or is missing. [`LinuxEngine`](android/app/src/main/java/ai/deepseek/harness/LinuxEngine.java) then runs `npm install -g @deepseek-ai/dsh@alpha` and adds Android builds of the harness's two native addons from `assets/native-addons/`, which `pnpm run build:addons` cross-compiles with the NDK because the published packages have none: `node-addon-require-builtin`, and this repository's `flock` addon from `native/system`, whose installed loader the app patches to accept Android. The addons are re-applied before every start, so an app update fixes an existing install. Everything runs natively: the app targets SDK 28, the last target that lets an app execute programs from its own storage and that grants classic shared-storage access, as Termux does. The install is safe to rerun and only fetches packages whose version changed. [`EngineService`](android/app/src/main/java/ai/deepseek/harness/EngineService.java) owns the install and the running `dsh web` as a foreground service, reads the sign-in link from its output, and keeps the last 400 log lines for the settings screen. When the full engine is selected, the app starts it at launch, waits up to 90 seconds for the link, and falls back to on-device mode otherwise.

Before every start the app writes its `github-actions` and `android-phone` skills into the engine's `~/.dsh/skills/`: heavy builds go to GitHub Actions through `gh`, and the agent knows it runs on a phone. **Sign in to GitHub** runs `gh auth login --web` ([`GitHubLogin`](android/app/src/main/java/ai/deepseek/harness/GitHubLogin.java)): it copies the one-time code, opens github.com/login/device, and then configures git, so no token passes through the app. **Phone storage → Allow** grants the engine `/sdcard` and USB drives under `/storage`; **Import a folder** copies a folder picked in Android's folder picker into `~/work/imports/` ([`FolderImport`](android/app/src/main/java/ai/deepseek/harness/FolderImport.java)). [`assets/licenses/THIRD_PARTY.txt`](android/app/src/main/assets/licenses/THIRD_PARTY.txt) lists what is bundled and downloaded.

## App settings and the Termux engine

Long-press the app icon and choose **App settings** ([`SettingsActivity`](android/app/src/main/java/ai/deepseek/harness/SettingsActivity.java)).

- **Style** selects a built-in preset ([`Style`](android/app/src/main/java/ai/deepseek/harness/Style.java)), such as liquid glass. The app injects it after each page load in both modes, before the agent's `theme.css`, so a custom theme still wins. **Accent color** and **Corners** override the harness's blue scale and radius tokens, which it sets inline on `<body>`, so the layout keeps the harness's own look.
- **Termux engine** runs the published `@deepseek-ai/dsh` in Termux and shows its `dsh web` page instead of the on-device page, for plugin installation, real shells, git, Python, and phone storage through `termux-setup-storage`. The screen lists one-time setup commands, asks for Termux's `RUN_COMMAND` permission, and takes the sign-in link `dsh web` prints. Only `127.0.0.1` and `localhost` links are accepted. At launch, [`Engine`](android/app/src/main/java/ai/deepseek/harness/Engine.java) starts `dsh web` through Termux when it is not running and the permission is granted, waits up to 45 seconds, and otherwise falls back to on-device mode with a notice. The first load uses the token link; later loads use the origin, because each start prints a new token while the signed cookie stays valid, and a 401 asks for a new link. Engine pages receive the mobile base style and a capture-phase Enter shim, because the published client still submits on Enter under touch. The working notification, saved state, and `theme.css` belong to on-device mode.

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
- **Plugin installation needs the full engine**: in on-device mode, installing needs a package-manager process, which the Worker cannot start.
- **Two native modules have no Android build**: the full engine installs `koffi` and `node-pty` without building them, so features that load them, such as a pseudo-terminal, fail in the full engine.
- **The target SDK keeps the app off Google Play**: SDK 28 is what lets the full engine run its programs; Google Play requires a newer target, so the app is distributed as an APK.
- **Downloads from the page are not handled**: the WebView has no download handler for files the UI offers to save.
- **The PATCH method cannot be relayed**: Android's `HttpURLConnection` refuses it, and the relay answers 405.
