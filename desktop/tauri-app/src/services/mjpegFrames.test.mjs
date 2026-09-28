import test from 'node:test';
import assert from 'node:assert/strict';
import { MjpegFrames } from './mjpegFrames.ts';
test('multipart headers and fragmented JPEG markers yield only complete newest image', () => {
  const parser = new MjpegFrames();
  assert.equal(parser.push(new Uint8Array([65, 13, 10, 255])), null);
  assert.equal(parser.push(new Uint8Array([216, 12, 255])), null);
  assert.deepEqual([...parser.push(new Uint8Array([217, 13, 10, 255,216,42,255,217]))], [255,216,42,255,217]);
});
test('broken multipart data cannot grow retained memory without bound', () => {
  const parser = new MjpegFrames();
  assert.throws(() => parser.push(new Uint8Array(8 * 1024 * 1024 + 1)), /large/);
});
