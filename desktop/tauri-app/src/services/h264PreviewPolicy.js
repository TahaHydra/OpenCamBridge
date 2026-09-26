// Pure presentation policy shared by the worker and its focused regression tests.
// All timestamps here are microseconds; the host clock is performance.now()*1000.
export function avcCodec(bytes) {
  for (let i = 0; i + 7 <= bytes.length; i++) {
    let nal = -1;
    if (bytes[i] === 0 && bytes[i + 1] === 0) {
      if (bytes[i + 2] === 1) nal = i + 3;
      else if (bytes[i + 2] === 0 && bytes[i + 3] === 1) nal = i + 4;
    }
    if (nal >= 0 && nal + 3 < bytes.length && (bytes[nal] & 31) === 7) {
      return 'avc1.' + Array.from(bytes.subarray(nal + 1, nal + 4), b => b.toString(16).padStart(2, '0')).join('');
    }
  }
  return null;
}

export class PreviewTimeline {
  constructor(fps = 30) {
    this.delay = Math.min(70000, 2 * 1000000 / Math.max(1, fps));
    this.queue = [];
    this.anchor = null;
    this.lastTimestamp = null;
    this.dropped = 0;
  }
  reset() {
    for (const frame of this.queue) frame.close();
    this.queue = [];
    this.anchor = null;
    this.lastTimestamp = null;
  }
  push(frame, now) {
    if (this.lastTimestamp !== null && frame.timestamp <= this.lastTimestamp) this.reset();
    this.lastTimestamp = frame.timestamp;
    if (this.anchor === null || Math.abs(now - (frame.timestamp + this.anchor)) > 250000) {
      this.reset();
      this.lastTimestamp = frame.timestamp;
      this.anchor = now + this.delay - frame.timestamp;
    }
    this.queue.push(frame);
    // Bounded GPU resources even when a window is occluded or display stops.
    while (this.queue.length > 8) { this.queue.shift().close(); this.dropped++; }
  }
  take(now) {
    let selected = null;
    while (this.queue.length && this.queue[0].timestamp + this.anchor <= now) {
      if (selected) { selected.close(); this.dropped++; }
      selected = this.queue.shift();
    }
    return selected;
  }
}
