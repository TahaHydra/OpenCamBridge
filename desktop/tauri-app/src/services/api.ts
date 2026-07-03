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

export function apiFetch(
  baseUrl: string,
  path: string,
  token?: string,
  init?: RequestInit
): Promise<Response> {
  const headers = new Headers(init?.headers || {});
  if (token) {
    headers.set('X-OpenCamBridge-Token', token);
  }
  const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
  return fetch(`${base}${path.startsWith('/') ? path : `/${path}`}`, {
    ...init,
    headers,
  });
}
