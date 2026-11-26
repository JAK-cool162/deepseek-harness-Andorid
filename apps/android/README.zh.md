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
- **无法安装插件**：安装需要包管理器进程，而 Worker 无法启动进程。
- **不处理页面下载**：WebView 没有处理界面提供保存的文件的下载处理器。
- **无法中继 PATCH 方法**：Android 的 `HttpURLConnection` 拒绝该方法，中继返回 405。
