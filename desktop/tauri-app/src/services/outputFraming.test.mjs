import test from 'node:test';
import assert from 'node:assert/strict';
import { centeredCrop, containRect, moveCrop, resizeCrop, normalizeFraming, resolveFraming, uprightSourceDimensions } from './outputFraming.js';

test('output source dimensions are upright and independent of preview pause or fallback', () => {
  const settings = { width: 1920, height: 1080, streamMode: 'h264' };
  const status = { encodedWidth: 1920, encodedHeight: 1080, activeStreamMode: 'h264', rotationDegrees: 90 };
  assert.deepEqual(uprightSourceDimensions(status, settings), { width: 1080, height: 1920 });
  assert.deepEqual(uprightSourceDimensions({ ...status, rotationDegrees: 270 }, settings), { width: 1080, height: 1920 });
  assert.deepEqual(uprightSourceDimensions({ ...status, rotationDegrees: 180 }, settings), { width: 1920, height: 1080 });
  // JPEG dimensions already describe its rotated pixels.
  assert.deepEqual(uprightSourceDimensions({ ...status, activeStreamMode: 'mjpeg', encodedWidth: 1080, encodedHeight: 1920 }, settings), { width: 1080, height: 1920 });
});

test('portrait crop on landscape source retains full height without stretching', () => {
  assert.deepEqual(centeredCrop(1920, 1080, 9 / 16), { x: 0.341796875, y: 0, width: 0.31640625, height: 1 });
});
test('landscape crop on upright portrait source retains full width', () => {
  assert.deepEqual(centeredCrop(1080, 1920, 16 / 9), { x: 0, y: 0.341796875, width: 1, height: 0.31640625 });
});
test('overlay bounds exclude preview letterboxing', () => {
  assert.deepEqual(containRect(800, 600, 1080, 1920), { x: 231.25, y: 0, width: 337.5, height: 600 });
});
test('drag clamps within source without changing crop size', () => {
  assert.deepEqual(moveCrop({ x: 0.2, y: 0.2, width: 0.5, height: 0.4 }, 4, -4), { x: 0.5, y: 0, width: 0.5, height: 0.4 });
});
test('resize keeps opposite corner fixed and never inverts crop', () => {
  assert.deepEqual(resizeCrop({ x: 0.25, y: 0.25, width: 0.5, height: 0.5 }, 'nw', -2, -2), { x: 0, y: 0, width: 0.75, height: 0.75 });
  const result = resizeCrop({ x: 0.25, y: 0.25, width: 0.5, height: 0.5 }, 'se', -2, -2);
  assert.ok(Math.abs(result.width - 0.05) < 1e-10);
  assert.ok(Math.abs(result.height - 0.05) < 1e-10);
  assert.equal(result.x, 0.25);
});
test('malformed persisted settings cannot produce empty or out-of-range native crop', () => {
  assert.deepEqual(normalizeFraming({ preset: 'custom', mode: 'custom', crop: { x: NaN, y: 9, width: -1, height: Infinity } }),
    { preset: 'custom', mode: 'custom', crop: { x: 0, y: 0, width: 1, height: 1 } });
  assert.equal(normalizeFraming({ preset: 'bad' }).mode, 'fit');
});
test('preset recomputes against upright source after phone rotates', () => {
  const framing = { preset: '1:1', mode: 'custom', crop: { x: 0, y: 0, width: 1, height: 1 } };
  assert.deepEqual(resolveFraming(framing, 1920, 1080).crop, { x: 0.21875, y: 0, width: 0.5625, height: 1 });
  assert.deepEqual(resolveFraming(framing, 1080, 1920).crop, { x: 0, y: 0.21875, width: 1, height: 0.5625 });
});
