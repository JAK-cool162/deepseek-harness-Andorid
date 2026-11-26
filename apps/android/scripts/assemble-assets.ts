/**
 * Join the `apps/web` preview build and the Android bootstrap build into the
 * APK asset directory `android/app/src/main/assets/www/`.
 *
 * The preview's own bootstrap, Worker, and fixture overlays are left out: the
 * Android bootstrap replaces the source chooser, and its Worker wraps the same
 * runtime bundle. `android.html` is the built index page with the Android
 * bootstrap tag spliced ahead of its module entry, the same splice
 * `apps/web` performs for `preview.html`.
 *
 * The Android asset pipeline strips a `.gz` suffix and inflates the file, so
 * every gzip archive is stored under `<name>.gz.raw`; the native asset source
 * maps each `.gz` request to that name.
 * @module @deepseek-ai/dsh-android/scripts/assemble-assets
 */
import { cp, mkdir, readdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const path = (rel: string): string => fileURLToPath(new URL(rel, import.meta.url))
const webDist = path('../../web/dist/')
const androidDist = path('../dist/')
const out = path('../android/app/src/main/assets/www/')
/** Suffix that keeps gzip archives byte-identical through the Android asset pipeline; `LocalServer.storedAssetName` matches it. */
const RAW_SUFFIX = '.raw'

/** Files of the web build that the APK does not ship. */
const EXCLUDED = (relative: string): boolean => relative === 'preview.html'
  || relative.endsWith('.map')
  || /^preview\/(bootstrap|worker)-[^/]+\.js$/.test(relative)
  || relative === 'preview/fixtures.json'
  || relative === 'preview/fixtures' || relative.startsWith('preview/fixtures/')

for (const [dir, hint] of [[webDist, 'pnpm --filter @deepseek-ai/dsh-web-frontend run build:preview'], [androidDist, 'pnpm --filter @deepseek-ai/dsh-android run build:web']] as const) {
  if (!existsSync(dir)) throw new Error(`assemble-assets: ${dir} is missing; run \`${hint}\` first`)
}
if (!existsSync(`${webDist}preview/vfs-image.tar.gz`)) {
  throw new Error('assemble-assets: the web build has no VFS image; run its build:preview script, not build')
}

await rm(out, { recursive: true, force: true })
await mkdir(out, { recursive: true })
await cp(webDist, out, {
  recursive: true,
  filter: (source) => {
    const relative = source.slice(webDist.length)
    return relative === '' || !EXCLUDED(relative)
  },
})
await cp(androidDist, out, { recursive: true, filter: source => !source.endsWith('.map') && !source.endsWith('.html') })

const bootstrap = (await readdir(`${androidDist}android`)).find(name => name.startsWith('bootstrap-') && name.endsWith('.js'))
if (bootstrap === undefined) throw new Error('assemble-assets: the Android build emitted no bootstrap entry')
const page = await readFile(`${webDist}index.html`, 'utf8')
const anchor = page.indexOf('<script type="module"')
if (anchor === -1) throw new Error('assemble-assets: the built index.html lost its module entry tag')
const tag = `<script type="module" crossorigin src="./android/${bootstrap}"></script>`
await writeFile(`${out}android.html`, `${page.slice(0, anchor)}${tag}${page.slice(anchor)}`)
for (const entry of await readdir(out, { recursive: true })) {
  if (entry.endsWith('.gz')) await rename(`${out}${entry}`, `${out}${entry}${RAW_SUFFIX}`)
}
console.log(`assemble-assets: wrote ${out}`)
