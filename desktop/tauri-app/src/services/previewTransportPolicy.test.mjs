import test from 'node:test';
import assert from 'node:assert/strict';
import { localPreviewRequest, previewNeedsFallback } from './previewTransportPolicy.js';

test('persistent decoder failures fall back while producer startup keeps retrying', () => {
  assert.equal(previewNeedsFallback(false, 30, false), false);
  assert.equal(previewNeedsFallback(false, 2, true), false);
  assert.equal(previewNeedsFallback(false, 3, true), true);
  assert.equal(previewNeedsFallback(true, 0, false), true);
});

test('compressed preview uses only the authenticated producer loopback endpoint', () => {
  const token = 'ab'.repeat(32);
  const request = localPreviewRequest({ url: 'http://127.0.0.1:32123/stream.ocb2', token });
  assert.equal(request.url, 'http://127.0.0.1:32123/stream.ocb2');
  assert.deepEqual(request.headers, { 'X-OpenCamBridge-Preview': token });
});

test('preview rejects phone endpoints, credential URLs and malformed credentials', () => {
  const token = 'ab'.repeat(32);
  for (const url of ['http://192.168.1.2:8080/stream.ocb2', 'http://localhost:8080/stream.ocb2',
    'http://127.0.0.1:8080/stream.ocb2?token=secret', 'http://user:pass@127.0.0.1:8080/stream.ocb2',
    'https://127.0.0.1:8080/stream.ocb2', 'http://127.0.0.1:8080/other']) {
    assert.throws(() => localPreviewRequest({ url, token }), /local preview/);
  }
  assert.throws(() => localPreviewRequest({ url: 'http://127.0.0.1:32123/stream.ocb2', token: 'phone-token' }), /credential/);
});
