/**
 * Embedder access to the overlay-owned directories of a mounted VFS: a
 * `vfs-export` frame returns them as one gzip-compressed overlay archive, so an
 * embedding page can persist Worker state and pass it back as an ordered overlay
 * on the next start; a `vfs-read` frame returns one file under them.
 *
 * Each frame carries a transferred `MessagePort`; the reply travels on that port
 * only, so the tunnel's response stream never carries a frame its client does
 * not know.
 * @module @deepseek-ai/dsh-experimental-webworker-runtime/src/storage/vfs-export
 */
import { IMAGE_OVERLAY_DIRECTORIES } from '../image-layout.ts'
import { join } from '../module-system/posix-path.ts'
import type { MemoryVfs } from './memory.ts'
import { packTar } from './tar.ts'

/** Page request for an overlay archive of the live VFS. */
export interface VfsExportFrame {
  readonly t: 'vfs-export'
  /** Port that receives exactly one {@link VfsExportReply}. */
  readonly port: MessagePort
}

/** The single message posted on a {@link VfsExportFrame} port. */
export type VfsExportReply =
  | { readonly ok: true; readonly archive: Uint8Array }
  | { readonly ok: false; readonly error: string }

/**
 * Whether a Worker message is a {@link VfsExportFrame}.
 * @param data - Raw message data.
 * @returns True for a `vfs-export` frame whose port is a `MessagePort`.
 */
export function isVfsExportFrame(data: unknown): data is VfsExportFrame {
  if (data === null || typeof data !== 'object') return false
  const frame = data as { t?: unknown; port?: unknown }
  return frame.t === 'vfs-export' && typeof MessagePort !== 'undefined' && frame.port instanceof MessagePort
}

/** Overlay entries in tar order with their permission bits. */
export interface OverlayEntries {
  /** Entry name to bytes; directory names end in a slash. */
  readonly files: Record<string, Uint8Array>
  /** Entry name to permission bits, so owner-only files such as credentials stay owner-only. */
  readonly modes: Record<string, number>
}

/**
 * Collect every entry under the overlay directories into tar entry order:
 * each directory before its children, names relative to the root.
 * @param vfs - Mounted filesystem.
 * @param root - Virtual root the image mounted under.
 * @returns The entries and their modes.
 */
export function collectOverlayEntries(vfs: MemoryVfs, root: string): OverlayEntries {
  const entries: Record<string, Uint8Array> = {}
  const modes: Record<string, number> = {}
  const empty = new Uint8Array(0)
  const walk = (relative: string): void => {
    const absolute = join(root, relative)
    for (const name of [...vfs.readdirSync(absolute)].sort()) {
      const childRelative = `${relative}/${name}`
      const child = join(root, childRelative)
      const stats = vfs.statSync(child)
      if (stats.isDirectory()) {
        entries[`${childRelative}/`] = empty
        modes[`${childRelative}/`] = Number(stats.mode) & 0o777
        walk(childRelative)
      } else {
        entries[childRelative] = vfs.readFileSync(child) as Uint8Array
        modes[childRelative] = Number(stats.mode) & 0o777
      }
    }
  }
  for (const directory of IMAGE_OVERLAY_DIRECTORIES) {
    if (!vfs.existsSync(join(root, directory))) continue
    entries[`${directory}/`] = empty
    walk(directory)
  }
  return { files: entries, modes }
}

/**
 * Pack the overlay directories into a gzip-compressed ustar archive that
 * `loadVfsOverlay` accepts after inflation.
 * @param vfs - Mounted filesystem.
 * @param root - Virtual root the image mounted under.
 * @returns The compressed archive bytes.
 */
export async function exportOverlayArchive(vfs: MemoryVfs, root: string): Promise<Uint8Array> {
  const { files, modes } = collectOverlayEntries(vfs, root)
  const tar = packTar(files, modes)
  const compressed = new Blob([tar as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new CompressionStream('gzip'))
  return new Uint8Array(await new Response(compressed).arrayBuffer())
}

/**
 * Answer one export request on its port, then close the port.
 * @param frame - The request.
 * @param vfs - Mounted filesystem, or undefined before the image mounted.
 * @param root - Virtual root the image mounted under.
 * @returns Resolves once the reply is posted.
 */
export async function answerVfsExport(frame: VfsExportFrame, vfs: MemoryVfs | undefined, root: string): Promise<void> {
  let reply: VfsExportReply
  if (vfs === undefined) {
    reply = { ok: false, error: 'webworker vfs export: the image is not mounted yet' }
  } else {
    try {
      reply = { ok: true, archive: await exportOverlayArchive(vfs, root) }
    } catch (error) {
      reply = { ok: false, error: error instanceof Error ? error.message : String(error) }
    }
  }
  if (reply.ok) frame.port.postMessage(reply, [reply.archive.buffer])
  else frame.port.postMessage(reply)
  frame.port.close()
}

/** Page request for one file under an overlay directory. */
export interface VfsReadFrame {
  readonly t: 'vfs-read'
  /** Path relative to the virtual root, starting with an overlay directory, such as `home/theme.css`. */
  readonly path: string
  /** Port that receives exactly one {@link VfsReadReply}. */
  readonly port: MessagePort
}

/** The single message posted on a {@link VfsReadFrame} port; `bytes` is absent when no file exists there. */
export type VfsReadReply =
  | { readonly ok: true; readonly bytes?: Uint8Array }
  | { readonly ok: false; readonly error: string }

/**
 * Whether a Worker message is a {@link VfsReadFrame}.
 * @param data - Raw message data.
 * @returns True for a `vfs-read` frame with a string path and a `MessagePort`.
 */
export function isVfsReadFrame(data: unknown): data is VfsReadFrame {
  if (data === null || typeof data !== 'object') return false
  const frame = data as { t?: unknown; path?: unknown; port?: unknown }
  return frame.t === 'vfs-read' && typeof frame.path === 'string'
    && typeof MessagePort !== 'undefined' && frame.port instanceof MessagePort
}

/**
 * Read one file under an overlay directory.
 * @param vfs - Mounted filesystem.
 * @param root - Virtual root the image mounted under.
 * @param path - Root-relative path.
 * @returns The file bytes, or undefined when no regular file exists there.
 * @throws When the path leaves the overlay directories.
 */
export function readOverlayFile(vfs: MemoryVfs, root: string, path: string): Uint8Array | undefined {
  const segments = path.split('/')
  if (segments.some(segment => segment === '' || segment === '.' || segment === '..')
    || !IMAGE_OVERLAY_DIRECTORIES.includes(segments[0] ?? '')) {
    throw new Error(`webworker vfs read: path must stay under ${IMAGE_OVERLAY_DIRECTORIES.join('/ or ')}, received "${path}"`)
  }
  const absolute = join(root, path)
  if (!vfs.existsSync(absolute) || vfs.statSync(absolute).isDirectory()) return undefined
  return vfs.readFileSync(absolute) as Uint8Array
}

/**
 * Answer one read request on its port, then close the port.
 * @param frame - The request.
 * @param vfs - Mounted filesystem, or undefined before the image mounted.
 * @param root - Virtual root the image mounted under.
 */
export function answerVfsRead(frame: VfsReadFrame, vfs: MemoryVfs | undefined, root: string): void {
  let reply: VfsReadReply
  if (vfs === undefined) {
    reply = { ok: false, error: 'webworker vfs read: the image is not mounted yet' }
  } else {
    try {
      const bytes = readOverlayFile(vfs, root, frame.path)
      reply = bytes === undefined ? { ok: true } : { ok: true, bytes: bytes.slice() }
    } catch (error) {
      reply = { ok: false, error: error instanceof Error ? error.message : String(error) }
    }
  }
  if (reply.ok && reply.bytes !== undefined) frame.port.postMessage(reply, [reply.bytes.buffer])
  else frame.port.postMessage(reply)
  frame.port.close()
}
