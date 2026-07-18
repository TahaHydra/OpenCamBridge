import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildProducerLaunchSpec,
  buildSettingsMutation,
  describeMutationRejection,
  shouldImportAuthoritativeState
} from './pipelineSyncPolicy.js';

test('409 conflict rolls back to authoritative state', () => {
  const authoritativeState = { revision: 21, width: 1280, height: 720, fps: 30 };
  const result = describeMutationRejection(409, { message: 'Revision conflict', authoritativeState });
  assert.equal(result.authoritativeState, authoritativeState);
  assert.equal(result.revision, 21);
  assert.match(result.message, /Revision conflict/);
});

test('422 exposes requested tuple and alternatives', () => {
  const result = describeMutationRejection(422, {
    message: 'Unsupported mode', requested: '1920x1080@60',
    alternatives: ['1920x1080@30', '1280x720@60'], authoritativeState: { revision: 8 }
  });
  assert.deepEqual(result.alternatives, ['1920x1080@30', '1280x720@60']);
  assert.match(result.message, /Requested: 1920x1080@60/);
  assert.match(result.message, /Alternatives: 1920x1080@30, 1280x720@60/);
});

test('SSE and poll converge without stale in-flight rollback', () => {
  assert.equal(shouldImportAuthoritativeState(null, 10, false), true);
  assert.equal(shouldImportAuthoritativeState(10, 10, true), false);
  assert.equal(shouldImportAuthoritativeState(10, 11, true), true);
  assert.equal(shouldImportAuthoritativeState(11, 10, false), false);
  assert.equal(shouldImportAuthoritativeState(11, 11, false), true);
});

test('producer launch uses selected source tuple and independent output canvas', () => {
  const spec = buildProducerLaunchSpec(
    { streamMode: 'h264', width: 1920, height: 1080, outputWidth: 1920, outputHeight: 1080, fps: 60 },
    { activeStreamMode: 'mjpeg', encodedWidth: 1920, encodedHeight: 1080, encodedFps: 30 },
    'http://127.0.0.1:8080/'
  );
  assert.equal(spec.source, 'mjpeg');
  assert.equal(spec.sourceFps, 30);
  assert.equal(spec.outputWidth, 1920);
  assert.equal(spec.targetUrl, 'http://127.0.0.1:8080/stream.mjpeg');
});

test('producer paces to the negotiated fps, not the transient cold-start encodedFps', () => {
  const spec = buildProducerLaunchSpec(
    { streamMode: 'mjpeg', width: 1920, height: 1080, outputWidth: 1920, outputHeight: 1080, fps: 30 },
    // encodedFps is the phone's low, still-ramping measurement right after a rebind.
    { activeStreamMode: 'mjpeg', encodedWidth: 1080, encodedHeight: 1920, selectedFps: 30, encodedFps: 9 },
    'http://127.0.0.1:8080/'
  );
  assert.equal(spec.sourceFps, 30);
});

test('missing selected/actual tuple blocks producer start', () => {
  assert.throws(
    () => buildProducerLaunchSpec({ streamMode: 'h264', width: 1920, height: 1080 }, null, 'http://phone'),
    /selected\/actual source tuple/
  );
});

test('cross-client mutation identities and base revisions remain distinct', () => {
  const web = buildSettingsMutation({ fps: 30 }, 12, 'web-request', 'web');
  const tauri = buildSettingsMutation({ width: 1920 }, 13, 'tauri-request', 'tauri');
  assert.deepEqual(web, { fps: 30, baseRevision: 12, requestId: 'web-request', clientType: 'web' });
  assert.deepEqual(tauri, { width: 1920, baseRevision: 13, requestId: 'tauri-request', clientType: 'tauri' });
  assert.notEqual(web.requestId, tauri.requestId);
});
