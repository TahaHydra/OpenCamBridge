import { fetch as nativeFetch } from '@tauri-apps/plugin-http';
import { isTauriRuntime } from './desktopBridge.ts';

const pending = new Map<string, Set<() => void>>();
export function cancelPhoneRequests(baseUrl: string) {
  for (const cancel of pending.get(baseUrl) || []) cancel();
}

/**
 * Small helpers so every request to the Android server carries the LAN access
 * token when one is configured. In USB mode (adb forward to 127.0.0.1) the
 * token is not required and may be empty.
 */

export function buildUrl(
  baseUrl: string,
  path: string,
  token?: string,
  extraParams?: Record<string, string>
): string {
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  const url = new URL(`${base}${path.startsWith('/') ? path : `/${path}`}`);
  if (token) {
    url.searchParams.set('token', token);
  }
  if (extraParams) {
    for (const [k, v] of Object.entries(extraParams)) {
      url.searchParams.set(k, v);
    }
  }
  return url.toString();
}

/** Streaming callers own cancellation and an idle watchdog. No browser CSP/CORS
 * dependency in packaged Tauri; never forward a phone token across redirects. */
export function phoneStreamFetch(url: string, token?: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (token) headers.set('X-OpenCamBridge-Token', token);
  return isTauriRuntime()
    ? nativeFetch(url, { ...init, headers, maxRedirections: 0, connectTimeout: 4000 })
    : fetch(url, { ...init, headers, redirect: 'error' });
}

export async function apiFetch(
  baseUrl: string,
  path: string,
  token?: string,
  init: RequestInit & { timeoutMs?: number } = {}
): Promise<Response> {
  const { timeoutMs = 8000, signal, ...request } = init;
  const abort = new AbortController();
  let cancelBody: (() => void) | undefined;
  let rejectCancelled: (reason: Error) => void = () => {};
  const cancelled = new Promise<never>((_, reject) => { rejectCancelled = reject; });
  const cancel = (reason: Error) => {
    abort.abort();
    cancelBody?.();
    rejectCancelled(reason);
  };
  const onAbort = () => cancel(new Error('Phone request cancelled'));
  const requests = pending.get(baseUrl) || new Set<() => void>();
  pending.set(baseUrl, requests);
  requests.add(onAbort);
  const timer = setTimeout(() => cancel(new Error('Phone request timed out')), timeoutMs);
  signal?.addEventListener('abort', onAbort, { once: true });
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  try {
    if (signal?.aborted) onAbort();
    return await Promise.race([cancelled, (async () => {
      const response = await phoneStreamFetch(`${base}${path.startsWith('/') ? path : `/${path}`}`, token,
        { ...request, signal: abort.signal });
      if (abort.signal.aborted) { void response.body?.cancel().catch(() => {}); throw new Error('Phone request cancelled'); }
      // Control responses are small. Consume the body INSIDE the deadline; a
      // socket that sends headers and then stalls must not hang res.json().
      const reader = response.body?.getReader();
      cancelBody = () => { void reader?.cancel().catch(() => {}); };
      const parts: Uint8Array[] = [];
      let length = 0;
      if (reader) for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        length += value.byteLength;
        if (length > 4 * 1024 * 1024) { cancelBody(); throw new Error('Phone control response too large'); }
        parts.push(value);
      }
      if (abort.signal.aborted) throw new Error('Phone request cancelled');
      const bytes = new Uint8Array(length);
      let offset = 0;
      for (const part of parts) { bytes.set(part, offset); offset += part.length; }
      return new Response([101, 103, 204, 205, 304].includes(response.status) ? null : bytes,
        { status: response.status, statusText: response.statusText, headers: response.headers });
    })()]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
    requests.delete(onAbort);
    if (!requests.size) pending.delete(baseUrl);
  }
}
