/**
 * Worker fetch relay: cross-origin HTTP(S) requests leave through the loopback
 * relay path with their method, headers, and body; same-origin and non-HTTP
 * requests pass through unchanged.
 */
import { describe, expect, it } from 'vitest'
import { installFetchRelay, RELAY_PREFIX, relayUrl, type RelayScope } from '../web/relay.ts'

const ORIGIN = 'http://127.0.0.1:47321'

/** A Worker-like scope whose original fetch records each request it receives. */
const scope = (): { scope: RelayScope; seen: Request[] } => {
  const seen: Request[] = []
  const subject: RelayScope = {
    location: { origin: ORIGIN },
    fetch: (input: RequestInfo | URL): Promise<Response> => {
      seen.push(input as Request)
      return Promise.resolve(new Response('ok'))
    },
  }
  return { scope: subject, seen }
}

describe('relayUrl', () => {
  it('encodes a cross-origin HTTPS target under the relay prefix', () => {
    expect(relayUrl('https://api.deepseek.com/anthropic/v1/messages?beta=1', ORIGIN))
      .toBe(`${ORIGIN}${RELAY_PREFIX}${encodeURIComponent('https://api.deepseek.com/anthropic/v1/messages?beta=1')}`)
  })

  it('leaves same-origin and relative requests alone', () => {
    expect(relayUrl(`${ORIGIN}/preview/vfs-image.tar.gz`, ORIGIN)).toBeUndefined()
    expect(relayUrl('/__dsh_state', ORIGIN)).toBeUndefined()
  })

  it('leaves non-HTTP schemes alone', () => {
    expect(relayUrl('data:text/plain,hi', ORIGIN)).toBeUndefined()
    expect(relayUrl('blob:http://127.0.0.1:47321/abc', ORIGIN)).toBeUndefined()
  })
})

describe('installFetchRelay', () => {
  it('sends a cross-origin POST through the relay with headers and body', async () => {
    const { scope: subject, seen } = scope()
    installFetchRelay(subject)
    await subject.fetch('https://api.deepseek.com/v1/messages', {
      method: 'POST', headers: { 'x-api-key': 'sk-test' }, body: '{"q":1}',
    })
    expect(seen).toHaveLength(1)
    expect(seen[0]?.url).toBe(relayUrl('https://api.deepseek.com/v1/messages', ORIGIN))
    expect(seen[0]?.method).toBe('POST')
    expect(seen[0]?.headers.get('x-api-key')).toBe('sk-test')
    expect(await seen[0]?.text()).toBe('{"q":1}')
  })

  it('passes a same-origin request through unchanged', async () => {
    const { scope: subject, seen } = scope()
    installFetchRelay(subject)
    await subject.fetch(`${ORIGIN}/preview/vfs-image.tar.gz`)
    expect(seen[0]?.url).toBe(`${ORIGIN}/preview/vfs-image.tar.gz`)
  })
})
