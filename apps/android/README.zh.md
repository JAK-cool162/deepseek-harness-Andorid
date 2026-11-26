# `@deepseek-ai/dsh-android`

[English](README.md) | 中文

Android 应用在手机本机上运行 DeepSeek Harness。整个 Harness 插件树通过[浏览器 Worker 运行时](../../packages/experimental/webworker-runtime/README.zh.md)运行在 WebView 的 Web Worker 中，无需电脑或服务器。只有模型服务商需要通过网络访问。

## 工作原理

| 部分 | 所属 | 作用 |
|---|---|---|
| Web 界面与 VFS 镜像 | [`apps/web`](../web/README.md) `build:preview` | 随附的 Web 外壳，以及 Worker 挂载为文件系统的 Harness 打包镜像。 |
| 引导页 `android.html` | [`web/bootstrap.ts`](web/bootstrap.ts) | 恢复已保存的状态覆盖层，连接宿主 Worker，并在应用运行期间保存状态。 |
| 宿主 Worker | [`web/worker.ts`](web/worker.ts) | 先安装 [fetch 中继](web/relay.ts)，再运行未修改的运行时 Worker 包。 |
| 回环服务器 | [`LocalServer.java`](android/app/src/main/java/ai/deepseek/harness/LocalServer.java) | 在 `127.0.0.1` 上提供资源，保存状态归档，并以原生方式中继跨源请求。 |
| Activity | [`MainActivity.java`](android/app/src/main/java/ai/deepseek/harness/MainActivity.java) | 承载 WebView、文件选择器和外部链接，并在暂停时保存。 |

需要中继，是因为浏览器会拦截未发送 CORS 头的主机的响应。Worker 把每个跨源 `fetch` 改写为 `/__dsh_relay/<编码后的 URL>`。服务器以相同的方法、请求头和请求体转发，并逐块流式返回响应，因此模型回复会边到达边显示。服务器只监听 `127.0.0.1`。除启动重定向外，每个请求都必须携带保存每次启动随机令牌的 Cookie，因此设备上的其他应用无法使用中继或读取已保存的状态。

已保存的状态是 Worker 的 `home/`（设置、凭据、Session 日志）和 `workspace/`（文件与默认 Workspace）。页面在可见时每 20 秒、以及应用转入后台时，向 Worker 请求一个 `vfs-export` 覆盖层归档，仅在内容变化时上传。服务器把它保存在应用私有存储中，并把上一份保留为 `harness-state.tar.gz.prev`。下次启动时，它作为覆盖层传给 Worker。如果从已保存数据启动失败，页面会提示重置。

## 手机功能

- **触摸行为**：[`web/mobile-base.css`](web/mobile-base.css) 让控件不再触发文本选择或显示点按高亮，加入按压反馈，并让菜单和对话框淡入；减少动态效果时关闭。页面会避开状态栏、刘海区域、导航栏和键盘。
- **产物**：`present` 工具给出的 HTML 文件在外壳的预览标签页中打开。Worker 用 `xdg-open` 打开的 HTML 或 SVG 文件会在沙箱框架中全屏渲染（[`web/artifact-viewer.ts`](web/artifact-viewer.ts)），并提供 **Save**，经 `/__dsh_save` 写入“下载”目录。
- **样式 API**：页面每两秒经运行时的 `vfs-read` 帧读取 `/dsh/home/android-ui/theme.css` 并实时应用（[`web/theme.ts`](web/theme.ts)）。只应用 CSS，超过 512 KB 的文件会被忽略，智能体在工作区外写入需要用户批准。智能体工作时会设置 `html[data-dsh-working]`。长按应用图标并选择 **Safe mode** 可在不加载自定义样式的情况下启动。
- **智能体技能**：[`defaults/`](defaults/) 打包为 `android/defaults.tar.gz`，每次启动都在已保存状态之后应用，使教智能体使用这些功能的 `android-app` 技能，以及 `home/android-ui/presets/` 下的样式预设（如 `liquid-glass.css`）保持最新。
- **工作通知**：在中继请求进行期间以及最后一个请求结束后的 20 秒内，[`WorkingService`](android/app/src/main/java/ai/deepseek/harness/WorkingService.java) 以前台服务运行，显示带已用时间的常驻通知，持有部分唤醒锁，并请求 Android 16 将通知提升为实时动态。应用在后台时工作结束，会发出“已完成”通知。应用首次启动时会请求通知权限。

## 完整引擎（原生）

设备内 Harness 无法启动进程，因此安装插件、真实 shell、git 和 Python 需要完整的 Harness。**App settings → Full engine → Install or repair** 会在应用内部完成安装，不需要其他应用。[`NativeInstaller`](android/app/src/main/java/ai/deepseek/harness/NativeInstaller.java) 从 Termux 仓库索引解析 Termux 预编译的 Android 软件包（bash、coreutils、Node.js 24、npm、git、gh、Python 及其库，约 55 MB），逐个按 SHA-256 校验，解包到应用的前缀 `files/usr`，并改写符号链接、脚本 shebang 和 git 的 shell 路径中的 Termux 路径；Termux 二进制文件中编译进去、引擎仍需要的路径，例如 OpenSSL 配置文件、terminfo 和 git 的系统配置，则通过环境变量指向应用的前缀，因为 Termux 自己的目录属于另一个应用或并不存在。随后 [`LinuxEngine`](android/app/src/main/java/ai/deepseek/harness/LinuxEngine.java) 运行 `npm install -g @deepseek-ai/dsh@alpha`，并从 `assets/native-addons/` 加入 Harness 的 `node-addon-require-builtin` Android 构建；已发布的软件包没有 Android 构建，因此 `pnpm run build:addons` 用 NDK 交叉编译它。所有程序都以原生方式运行：应用的目标 SDK 为 28，这是最后一个允许应用执行自身存储中的程序并授予传统共享存储访问权限的目标版本，与 Termux 相同。安装可以安全地重复运行，只会获取版本变化的软件包。[`EngineService`](android/app/src/main/java/ai/deepseek/harness/EngineService.java) 作为前台服务负责安装和运行中的 `dsh web`，从其输出中读取登录链接，并为设置页面保留最近 400 行日志。选择完整引擎后，应用在启动时启动它，最多等待 90 秒获取链接，否则回退到设备内模式。

每次启动前，应用都会把它的 `github-actions` 和 `android-phone` 技能写入引擎的 `~/.dsh/skills/`：繁重的构建通过 `gh` 交给 GitHub Actions，智能体也知道自己运行在手机上。**Sign in to GitHub** 运行 `gh auth login --web`（[`GitHubLogin`](android/app/src/main/java/ai/deepseek/harness/GitHubLogin.java)）：复制一次性代码、打开 github.com/login/device，然后配置 git，因此令牌不会经过应用。**Phone storage → Allow** 让引擎访问 `/sdcard` 和 `/storage` 下的 USB 驱动器；**Import a folder** 把在 Android 文件夹选择器中选中的文件夹复制到 `~/work/imports/`（[`FolderImport`](android/app/src/main/java/ai/deepseek/harness/FolderImport.java)）。[`assets/licenses/THIRD_PARTY.txt`](android/app/src/main/assets/licenses/THIRD_PARTY.txt) 列出了随附和下载的内容。

## 应用设置与 Termux 引擎

长按应用图标并选择 **App settings**（[`SettingsActivity`](android/app/src/main/java/ai/deepseek/harness/SettingsActivity.java)）。

- **Style** 选择内置预设（[`Style`](android/app/src/main/java/ai/deepseek/harness/Style.java)），例如液态玻璃。应用在两种模式下每次页面加载后注入它，并放在智能体的 `theme.css` 之前，因此自定义主题仍然优先。**Accent color** 和 **Corners** 覆盖 Harness 以内联方式设在 `<body>` 上的蓝色色阶和圆角令牌，因此布局保持 Harness 原有的外观。
- **Termux engine** 在 Termux 中运行已发布的 `@deepseek-ai/dsh`，并显示它的 `dsh web` 页面而不是设备内页面，用于安装插件、真实 shell、git、Python，以及通过 `termux-setup-storage` 访问手机存储。该页面列出一次性安装命令，请求 Termux 的 `RUN_COMMAND` 权限，并接收 `dsh web` 打印的登录链接。只接受 `127.0.0.1` 和 `localhost` 链接。启动时，如果引擎未运行且已获权限，[`Engine`](android/app/src/main/java/ai/deepseek/harness/Engine.java) 会通过 Termux 启动 `dsh web`，最多等待 45 秒，否则回退到设备内模式并给出提示。首次加载使用带令牌的链接；之后只加载源地址，因为每次启动都会打印新令牌，而已签名的 Cookie 仍然有效；收到 401 时会请求新链接。引擎页面会注入移动端基础样式和捕获阶段的 Enter 垫片，因为已发布的客户端在触摸设备上仍会按 Enter 发送。工作通知、已保存状态和 `theme.css` 属于设备内模式。

## 构建

要求：Node `^24.22.3 || >=26.13.1`、pnpm、JDK 17 或更高版本，以及 Android SDK（platform 35、build-tools 35），并设置 `ANDROID_HOME` 或在 `android/local.properties` 中写明 `sdk.dir`。

```sh
pnpm install
pnpm run build:artifacts
pnpm --filter @deepseek-ai/dsh-web-frontend run build:preview
pnpm --filter @deepseek-ai/dsh-android run build:apk
```

`build:apk` 用 Vite 构建引导脚本，组装 `android/app/src/main/assets/www/`，并运行 `./gradlew assembleRelease`。APK 输出到 `android/app/build/outputs/apk/release/app-release.apk`。Android 资源流水线会去掉 `.gz` 后缀并解压文件，因此组装步骤把每个 gzip 归档存为 `<name>.gz.raw`，由服务器把请求映射回去。

发布签名读取 `DSH_ANDROID_KEYSTORE`、`DSH_ANDROID_KEYSTORE_PASSWORD`、`DSH_ANDROID_KEY_ALIAS` 和 `DSH_ANDROID_KEY_PASSWORD`。未设置时，发布 APK 使用本机调试密钥签名，可以正常安装，但不能被其他密钥签名的 APK 原地更新。

原生测试在 `android/` 下运行 `./gradlew testReleaseUnitTest`；中继的 TypeScript 测试运行 `pnpm vitest run apps/android/tests`。

## 安装与使用

1. 把 `app-release.apk` 复制到手机并打开；Android 询问时，允许从该来源安装。
2. 打开 **DeepSeek Harness**，跳过预览说明，选择 **Add API Key** 并填入 DeepSeek 开放平台的密钥，或选择 **Sign in**。
3. 在默认 Workspace 中对话。智能体写入的文件位于应用的 `workspace/` 中，重启后仍然保留。

需要 Android 8.0（API 26）或更高版本，以及最新的 Android System WebView。

## Known Limitations and Deferred Work

- **运行时限制同样适用**：Worker shell 不是 bash，没有 `git` 或网络工具，PTC Node 程序和 Office 转换不可用，插件安装会失败；见运行时的[限制](../../packages/experimental/webworker-runtime/README.zh.md#known-limitations-and-deferred-work)。
- **状态按快照保存**：Android 在后台结束应用前不到 20 秒内的更改可能丢失。每个快照包含完整的 `home/` 和 `workspace/` 树，因此非常大的 Workspace 会占用较多内存和保存时间。
- **后台工作依赖工作服务**：应用在后台时 Android 拒绝启动前台服务，因此在后台空闲 20 秒后又继续的轮次将在没有通知和唤醒锁的情况下运行。
- **岛屿显示取决于系统**：工作通知在 Android 16 上请求提升；HyperOS 等厂商的岛屿只有在支持该请求时才会显示。
- **安装插件需要完整引擎**：在设备内模式下，安装需要包管理器进程，而 Worker 无法启动进程。
- **两个原生模块没有 Android 构建**：完整引擎安装 `koffi` 和 `node-pty` 时不构建它们，因此在完整引擎中，加载它们的功能（例如伪终端）会失败。
- **目标 SDK 使应用无法上架 Google Play**：SDK 28 让完整引擎能够运行其程序；Google Play 要求更新的目标版本，因此应用以 APK 形式分发。
- **不处理页面下载**：WebView 没有处理界面提供保存的文件的下载处理器。
- **无法中继 PATCH 方法**：Android 的 `HttpURLConnection` 拒绝该方法，中继返回 405。
