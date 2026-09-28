import test from 'node:test';
import assert from 'node:assert/strict';
import { manualBitrateWarning } from './bitrateWarning.js';
const settings = { streamMode: 'h264', h264BitrateMode: 'manual', h264Bitrate: 6_000_000, width: 1280, height: 720, fps: 30 };
test('warning follows the active 1080p60 mode rather than a stale 720p setting', () => {
  assert.deepEqual(manualBitrateWarning(settings, { encodedWidth: 1920, encodedHeight: 1080, selectedFps: 60 }), { recommendation: 16, width: 1920, height: 1080, fps: 60 });
});
test('manual bitrate warns strictly below seventy percent of Auto', () => {
  assert.equal(manualBitrateWarning({ ...settings, h264Bitrate: 7_000_000 }, { encodedWidth: 1920, encodedHeight: 1080, selectedFps: 30 }), null);
  assert.equal(manualBitrateWarning({ ...settings, h264Bitrate: 6_000_000 }, { encodedWidth: 1920, encodedHeight: 1080, selectedFps: 30 }).recommendation, 10);
});
test('Auto and MJPEG do not show a manual H264 warning', () => {
  assert.equal(manualBitrateWarning({ ...settings, h264BitrateMode: 'auto', h264Bitrate: 1 }), null);
  assert.equal(manualBitrateWarning({ ...settings, streamMode: 'mjpeg', h264Bitrate: 1 }), null);
});
test('portrait pixel count has the same recommendation and zero metrics fall back', () => {
  assert.equal(manualBitrateWarning({ ...settings, h264Bitrate: 1_000_000 }, { encodedWidth: 720, encodedHeight: 1280, selectedFps: 60 }).recommendation, 9);
  assert.equal(manualBitrateWarning({ ...settings, h264Bitrate: 1_000_000 }, { encodedWidth: 0, encodedHeight: 0, selectedFps: 0 }).recommendation, 6);
});
