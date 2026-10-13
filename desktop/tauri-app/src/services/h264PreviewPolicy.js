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
  constructor(fps = 30, profile = 'balanced') {
    this.delay = profile === 'low' ? 55000 : profile === 'smooth' ? 140000 : 90000;
    this.maxFrames = Math.min(16, Math.max(8, Math.ceil(this.delay * Math.max(1, fps) / 1000000) + 3));
    this.queue = [];
    this.anchor = null;
    this.lastTimestamp = null;
    this.dropped = 0;
    this.clockWindowAt = null;
    this.minimumOffset = Infinity;
  }
  reset() {
    for (const frame of this.queue) frame.close();
    this.queue = [];
    this.anchor = null;
    this.lastTimestamp = null;
    this.clockWindowAt = null;
    this.minimumOffset = Infinity;
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
    // Estimate clock phase from the lowest transit time in a window. One late
    // decode must not add delay. Slew at most 2ms/second, enough for clock drift
    // while preserving the presentation cushion through ordinary arrival bursts.
    this.minimumOffset = Math.min(this.minimumOffset, now - frame.timestamp);
    if (this.clockWindowAt === null) this.clockWindowAt = now;
    if (now - this.clockWindowAt >= 1000000) {
      const error = this.minimumOffset + this.delay - this.anchor;
      this.anchor += Math.max(-2000, Math.min(2000, error));
      this.clockWindowAt = now;
      this.minimumOffset = Infinity;
    }
    // Bounded GPU resources even when a window is occluded or display stops.
    while (this.queue.length > this.maxFrames) { this.queue.shift().close(); this.dropped++; }
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
