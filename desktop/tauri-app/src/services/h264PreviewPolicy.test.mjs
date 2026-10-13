import test from 'node:test';
import assert from 'node:assert/strict';
import { avcCodec, PreviewTimeline } from './h264PreviewPolicy.js';

test('codec comes from actual SPS including High profile and both Annex-B prefixes', () => {
  assert.equal(avcCodec(Uint8Array.of(0, 0, 0, 1, 0x67, 100, 0, 42)), 'avc1.64002a');
  assert.equal(avcCodec(Uint8Array.of(0, 0, 1, 0x67, 66, 0xe0, 31)), 'avc1.42e01f');
  assert.equal(avcCodec(Uint8Array.of(0, 0, 1, 0x65)), null);
});
const frame = timestamp => ({ timestamp, closed: 0, close() { this.closed++; } });
test('shared latency profiles have time targets independent of capture FPS', () => {
  for (const fps of [24, 25, 30, 50, 60]) {
    assert.equal(new PreviewTimeline(fps, 'low').delay, 55000);
    assert.equal(new PreviewTimeline(fps, 'balanced').delay, 90000);
    assert.equal(new PreviewTimeline(fps, 'smooth').delay, 140000);
    assert.ok(new PreviewTimeline(fps, 'smooth').maxFrames >= Math.ceil(0.14 * fps) + 2);
  }
});
test('one hour of clock mismatch keeps preview queue and freshness bounded', () => {
  for (const ppm of [-1000, 1000]) {
    const timeline = new PreviewTimeline(60, 'balanced');
    let displayed = 0;
    for (let n = 0; n < 60 * 3600; n++) {
      const now = n * 1000000 / 60;
      timeline.push(frame(now * (1 + ppm / 1000000)), now);
      const output = timeline.take(now);
      if (output) { displayed++; output.close(); }
      assert.ok(timeline.queue.length <= timeline.maxFrames);
    }
    assert.ok(displayed >= 60 * 3600 * 0.99);
    timeline.reset();
  }
});
test('bursty 30fps arrivals present 30 distinct frames on a 60Hz display without draining the cushion', () => {
  const timeline = new PreviewTimeline(30);
  let supplied = 0;
  const released = [];
  for (let tick = 0; tick < 130; tick++) {
    const now = tick * 1000000 / 60;
    // Two frames arrive together every other source interval.
    while (supplied < 60 && Math.floor(supplied / 2) * 2000000 / 30 <= now) {
      timeline.push(frame(supplied++ * 1000000 / 30), now);
    }
    const output = timeline.take(now + 0.001);
    if (output) { released.push(output.timestamp); output.close(); }
  }
  assert.equal(released.length, 60);
  assert.equal(timeline.dropped, 0);
});
test('suspension bounds GPU frames and discontinuity closes retained resources', () => {
  const timeline = new PreviewTimeline(60);
  const frames = Array.from({ length: 20 }, (_, i) => frame(i * 16667));
  frames.forEach((f, i) => timeline.push(f, i * 16667));
  assert.equal(timeline.queue.length, timeline.maxFrames);
  timeline.reset();
  assert.ok(frames.every(f => f.closed === 1));
  const restarted = frame(0);
  timeline.push(restarted, 1000000);
  assert.equal(timeline.take(1000000), null);
  assert.equal(timeline.take(1090000), restarted);
});
