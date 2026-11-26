/**
 * Overlay export: the live `home/` and `workspace/` trees round-trip through the
 * archive an embedding page persists and passes back as an overlay, while base
 * image content outside those directories never enters the archive.
 */
import { describe, expect, it } from 'vitest'
import { inflateImage } from '../../src/storage/image-gzip.ts'
import { loadVfsImage, loadVfsOverlay, MemoryVfs } from '../../src/storage/memory.ts'
import { packTar } from '../../src/storage/tar.ts'
import {
  answerVfsExport, answerVfsRead, collectOverlayEntries, exportOverlayArchive, isVfsExportFrame, isVfsReadFrame,
  readOverlayFile, type VfsExportReply, type VfsReadReply,
} from '../../src/storage/vfs-export.ts'

const encoder = new TextEncoder()

/** A mounted image whose session and workspace files changed after boot. */
const mounted = (): MemoryVfs => {
  const vfs = loadVfsImage(packTar({
    'config/vfs-manifest.json': encoder.encode('{}'),
    'home/': new Uint8Array(0),
    'workspace/': new Uint8Array(0),
  }), '/dsh')
  vfs.seed('/dsh/home/profiles/web/cordis.patch.yml', 'answer: 42\n')
  vfs.seed('/dsh/workspace/notes/todo.md', '# todo\n')
  vfs.seed('/dsh/home/.credentials.yaml', 'secret: 1\n', { mode: 0o600 })
  return vfs
}

describe('vfs export', () => {
  it('collects only the overlay directories, parents before children', () => {
    expect(Object.keys(collectOverlayEntries(mounted(), '/dsh').files)).toEqual([
      'home/', 'home/.credentials.yaml', 'home/profiles/', 'home/profiles/web/', 'home/profiles/web/cordis.patch.yml',
      'workspace/', 'workspace/notes/', 'workspace/notes/todo.md',
    ])
  })

  it('produces an archive that restores the same files as an overlay', async () => {
    const archive = await exportOverlayArchive(mounted(), '/dsh')
    const fresh = loadVfsImage(packTar({ 'config/vfs-manifest.json': encoder.encode('{}') }), '/dsh')
    loadVfsOverlay(await inflateImage(archive, 'export'), '/dsh', fresh)
    expect(fresh.readFileSync('/dsh/home/profiles/web/cordis.patch.yml', 'utf8')).toBe('answer: 42\n')
    expect(fresh.readFileSync('/dsh/workspace/notes/todo.md', 'utf8')).toBe('# todo\n')
  })

  it('keeps owner-only permission bits through the round trip', async () => {
    const archive = await exportOverlayArchive(mounted(), '/dsh')
    const fresh = loadVfsImage(packTar({ 'config/vfs-manifest.json': encoder.encode('{}') }), '/dsh')
    loadVfsOverlay(await inflateImage(archive, 'export'), '/dsh', fresh)
    expect(Number(fresh.statSync('/dsh/home/.credentials.yaml').mode) & 0o777).toBe(0o600)
  })

  it('recognizes only frames that carry a MessagePort', () => {
    const { port1, port2 } = new MessageChannel()
    expect(isVfsExportFrame({ t: 'vfs-export', port: port1 })).toBe(true)
    expect(isVfsExportFrame({ t: 'vfs-export' })).toBe(false)
    expect(isVfsExportFrame({ t: 'init', port: port1 })).toBe(false)
    expect(isVfsExportFrame(null)).toBe(false)
    port1.close()
    port2.close()
  })

  it('replies with a refusal before the image mounts', async () => {
    const { port1, port2 } = new MessageChannel()
    const reply = new Promise<VfsExportReply>((resolve) => {
      port2.onmessage = (event: MessageEvent<VfsExportReply>): void => { resolve(event.data) }
    })
    await answerVfsExport({ t: 'vfs-export', port: port1 }, undefined, '/dsh')
    expect(await reply).toEqual({ ok: false, error: 'webworker vfs export: the image is not mounted yet' })
    port2.close()
  })

  it('replies with the archive once mounted', async () => {
    const { port1, port2 } = new MessageChannel()
    const reply = new Promise<VfsExportReply>((resolve) => {
      port2.onmessage = (event: MessageEvent<VfsExportReply>): void => { resolve(event.data) }
    })
    await answerVfsExport({ t: 'vfs-export', port: port1 }, mounted(), '/dsh')
    const result = await reply
    expect(result.ok).toBe(true)
    if (result.ok) expect(Array.from(result.archive.subarray(0, 2))).toEqual([0x1f, 0x8b])
    port2.close()
  })
})

describe('vfs read', () => {
  it('returns a file under an overlay directory and nothing for a missing one', () => {
    const vfs = mounted()
    expect(new TextDecoder().decode(readOverlayFile(vfs, '/dsh', 'workspace/notes/todo.md'))).toBe('# todo\n')
    expect(readOverlayFile(vfs, '/dsh', 'home/absent.css')).toBeUndefined()
    expect(readOverlayFile(vfs, '/dsh', 'workspace/notes')).toBeUndefined()
  })

  it('refuses paths outside the overlay directories', () => {
    expect(() => readOverlayFile(mounted(), '/dsh', 'config/vfs-manifest.json')).toThrow(/must stay under/)
    expect(() => readOverlayFile(mounted(), '/dsh', 'home/../config/vfs-manifest.json')).toThrow(/must stay under/)
  })

  it('answers on the request port', async () => {
    const { port1, port2 } = new MessageChannel()
    const reply = new Promise<VfsReadReply>((resolve) => {
      port2.onmessage = (event: MessageEvent<VfsReadReply>): void => { resolve(event.data) }
    })
    expect(isVfsReadFrame({ t: 'vfs-read', path: 'home/x', port: port1 })).toBe(true)
    answerVfsRead({ t: 'vfs-read', path: 'workspace/notes/todo.md', port: port1 }, mounted(), '/dsh')
    const result = await reply
    expect(result.ok && result.bytes !== undefined && new TextDecoder().decode(result.bytes)).toBe('# todo\n')
    port2.close()
  })
})
