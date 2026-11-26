/**
 * Fetch the prebuilt Android `proot` the built-in Linux engine runs, and place
 * it in `android/app/src/main/jniLibs/arm64-v8a/`, where Android extracts it as
 * executable native code.
 *
 * The binaries come from Termux's package repository, pinned by SHA-256. The
 * Android installer only extracts files named `lib*.so`, so each file is
 * renamed, and the library names inside the ELF dynamic string tables are
 * patched to match (`libtalloc.so.2` becomes `libtalloc.so`); the patch writes
 * a shorter NUL-terminated string in place, so no offsets move.
 *
 * Requires `ar` and `tar` with xz support on the build machine.
 * @module @deepseek-ai/dsh-android/scripts/fetch-proot
 */
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const REPOSITORY = 'https://packages.termux.dev/apt/termux-main/'
const PREFIX = 'data/data/com.termux/files/usr/'

/** One pinned Termux package and the files taken from it, by path under the Termux prefix. */
interface Pinned {
  readonly file: string
  readonly sha256: string
  readonly take: Readonly<Record<string, string>>
}

const PACKAGES: readonly Pinned[] = [
  {
    file: 'pool/main/p/proot/proot_5.1.107.102_aarch64.deb',
    sha256: '10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030',
    take: { 'bin/proot': 'libproot.so', 'libexec/proot/loader': 'libproot-loader.so' },
  },
  {
    file: 'pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb',
    sha256: '556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7',
    take: { 'lib/libtalloc.so.2.5.0': 'libtalloc.so' },
  },
  {
    file: 'pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb',
    sha256: '0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6',
    take: { 'lib/libandroid-shmem.so': 'libandroid-shmem.so' },
  },
]

/** Library names rewritten inside every taken ELF file. */
const RENAMES: Readonly<Record<string, string>> = { 'libtalloc.so.2': 'libtalloc.so' }

const out = fileURLToPath(new URL('../android/app/src/main/jniLibs/arm64-v8a/', import.meta.url))

/**
 * Replace a NUL-terminated string inside a binary with a shorter one, padding with NULs.
 * @param bytes - File contents, modified in place.
 * @param from - Exact string to replace, without its terminator.
 * @param to - Replacement no longer than `from`.
 * @returns How many occurrences were replaced.
 */
export function patchCString(bytes: Uint8Array, from: string, to: string): number {
  if (to.length > from.length) throw new Error(`fetch-proot: "${to}" is longer than "${from}"`)
  const needle = new TextEncoder().encode(`${from}\0`)
  const replacement = new Uint8Array(needle.length)
  replacement.set(new TextEncoder().encode(to))
  let count = 0
  for (let index = 0; index <= bytes.length - needle.length; index += 1) {
    let match = index === 0 || bytes[index - 1] === 0
    for (let offset = 0; match && offset < needle.length; offset += 1) match = bytes[index + offset] === needle[offset]
    if (!match) continue
    bytes.set(replacement, index)
    count += 1
  }
  return count
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const work = await mkdtemp(join(tmpdir(), 'dsh-proot-'))
  try {
    await mkdir(out, { recursive: true })
    for (const pinned of PACKAGES) {
      const response = await fetch(`${REPOSITORY}${pinned.file}`)
      if (!response.ok) throw new Error(`fetch-proot: ${pinned.file} answered ${String(response.status)}`)
      const deb = new Uint8Array(await response.arrayBuffer())
      const digest = createHash('sha256').update(deb).digest('hex')
      if (digest !== pinned.sha256) throw new Error(`fetch-proot: ${pinned.file} has SHA-256 ${digest}, expected ${pinned.sha256}`)
      const unpack = join(work, pinned.sha256)
      await mkdir(unpack)
      await writeFile(join(unpack, 'package.deb'), deb)
      execFileSync('ar', ['x', 'package.deb', 'data.tar.xz'], { cwd: unpack })
      execFileSync('tar', ['-xJf', 'data.tar.xz'], { cwd: unpack })
      for (const [source, name] of Object.entries(pinned.take)) {
        const path = join(unpack, PREFIX, source)
        if (!existsSync(path)) throw new Error(`fetch-proot: ${pinned.file} has no ${source}`)
        const bytes = new Uint8Array(await readFile(path))
        for (const [from, to] of Object.entries(RENAMES)) patchCString(bytes, from, to)
        await writeFile(join(out, name), bytes, { mode: 0o755 })
      }
    }
    console.log(`fetch-proot: wrote ${out}`)
  } finally {
    await rm(work, { recursive: true, force: true })
  }
}
