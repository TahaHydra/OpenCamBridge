/** JPEG marker framing also tolerates multipart headers and split HTTP chunks. */
export class MjpegFrames {
  private retained = new Uint8Array(0);
  push(bytes: Uint8Array): Uint8Array | null {
    if (this.retained.length + bytes.length > 8 * 1024 * 1024) throw new Error('MJPEG frame too large');
    const data = new Uint8Array(this.retained.length + bytes.length);
    data.set(this.retained); data.set(bytes, this.retained.length);
    let start = -1, consumed = 0, latest: Uint8Array | null = null;
    for (let i = 0; i + 1 < data.length; i++) {
      if (data[i] !== 255) continue;
      if (start < 0 && data[i + 1] === 216) { start = i; i++; }
      else if (start >= 0 && data[i + 1] === 217) {
        latest = data.slice(start, i + 2); consumed = i + 2; start = -1; i++;
      }
    }
    this.retained = data.slice(start >= 0 ? start : Math.max(consumed, data.length - 1));
    return latest;
  }
}
