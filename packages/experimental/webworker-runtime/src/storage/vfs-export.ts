/**
 * Export of the overlay-owned directories of a mounted VFS as one gzip-compressed
 * overlay archive, so an embedding page can persist Worker state and pass it back
 * as an ordered overlay on the next start.
 *
 * The page asks with a `vfs-export` frame carrying a transferred `MessagePort`;
 * the reply travels on that port only, so the tunnel's response stream never
 * carries a frame its client does not know.
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
