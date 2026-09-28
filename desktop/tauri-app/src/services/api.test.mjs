import test from 'node:test';
import assert from 'node:assert/strict';
import { apiFetch } from './api.ts';

test('phone request deadline aborts a transport that never responds', async () => {
  const original = globalThis.fetch;
  let signal;
  globalThis.fetch = async (_, init) => { signal = init.signal; return new Promise(() => {}); };
  try {
    const result = await Promise.race([
      apiFetch('http://127.0.0.1:8080', '/health', '', { timeoutMs: 20 }).catch(e => e),
      new Promise(resolve => setTimeout(() => resolve('still pending'), 150)),
    ]);
    assert.ok(result instanceof Error, 'request must reject at its own deadline');
    assert.equal(signal.aborted, true);
  } finally { globalThis.fetch = original; }
});

test('deadline includes stalled response body, not only response headers', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(new ReadableStream({ start() {} }));
  try {
    const result = await Promise.race([
      apiFetch('http://127.0.0.1:8080', '/api/camera/status', '', { timeoutMs: 20 }).then(r => r.text()).catch(e => e),
      new Promise(resolve => setTimeout(() => resolve('still pending'), 150)),
    ]);
    assert.ok(result instanceof Error, 'body must not escape request deadline');
  } finally { globalThis.fetch = original; }
});
