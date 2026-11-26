/**
 * Worker-side request relay. The Android shell serves the page from a loopback
 * origin and forwards `/__dsh_relay/<encoded URL>` requests natively, so model,
 * search, and fetch tools reach hosts that send no CORS headers.
 * @module @deepseek-ai/dsh-android/web/relay
 */

/** Path prefix the native server answers by forwarding to the decoded URL. */
export const RELAY_PREFIX = '/__dsh_relay/'

/**
 * The relay URL for a cross-origin HTTP(S) request.
 * @param target - Absolute or origin-relative request URL.
 * @param origin - The page's own origin.
 * @returns The same-origin relay URL, or undefined when the request needs no relay.
 */
export function relayUrl(target: string, origin: string): string | undefined {
  const url = new URL(target, origin)
  if (url.origin === origin) return undefined
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return undefined
  return `${origin}${RELAY_PREFIX}${encodeURIComponent(url.href)}`
}

/** The fetch-owning global the relay patches. */
export interface RelayScope {
  fetch: typeof fetch
  readonly location: { readonly origin: string }
}

/**
 * Route every cross-origin `fetch` in a scope through the relay. Request bodies
 * are buffered because Chromium cannot stream an upload over HTTP/1.1.
 * @param scope - The Worker global.
 */
export function installFetchRelay(scope: RelayScope): void {
  const original = scope.fetch.bind(scope)
  scope.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const request = new Request(input, init)
    const relayed = relayUrl(request.url, scope.location.origin)
    if (relayed === undefined) return await original(request)
    const body = request.method === 'GET' || request.method === 'HEAD' ? null : await request.arrayBuffer()
    return await original(new Request(relayed, {
      method: request.method,
      headers: request.headers,
      body,
      signal: request.signal,
      redirect: request.redirect,
      credentials: 'same-origin',
    }))
  }
}
