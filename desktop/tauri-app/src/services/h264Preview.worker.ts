/// <reference lib="webworker" />
// The same parser is exercised by Android/browser protocol conformance tests.
import '../../../../android/app/src/main/assets/ocb2-parser.js';
import { avcCodec, PreviewTimeline } from './h264PreviewPolicy.js';

const worker = self as unknown as DedicatedWorkerGlobalScope;
type Record = { type: number; flags: number; sequence: number; encoderTimestampUs: bigint; payload: Uint8Array };
type Info = { width: number; height: number; fpsNumerator: number; fpsDenominator: number; effectiveRotation: number; mirror: boolean; colorMatrix?: string; colorRange?: string; colorPrimaries?: string; colorTransfer?: string };
const Parser = (globalThis as unknown as { Ocb2Browser: { Parser: new () => { push(b: Uint8Array): void; next(): Record | null } } }).Ocb2Browser.Parser;
let parser = new Parser();
let canvas: OffscreenCanvas;
let context: OffscreenCanvasRenderingContext2D;
let info: Info | null = null;
let decoder: VideoDecoder | null = null;
let config = new Uint8Array(0);
let waitingForKey = true;
let failed = false;
let timeline = new PreviewTimeline();
let animation = 0;
let decoded = 0;
let displayed = 0;
let drawMs = 0;
let lastReport = 0;
let lastTimestamp = -1;
let lastOutputAt = 0;
let firstInputAt = 0;
let decodeMs = 0;
let decodeSamples = 0;
const submitted = new Map<number, number>();

function resetDecoder() {
  if (decoder && decoder.state !== 'closed') decoder.close();
  decoder = null;
  waitingForKey = true;
  lastTimestamp = -1;
  submitted.clear();
  timeline.reset();
}

function fail(message: string, unsupported = false) {
  if (failed) return;
  failed = true;
  resetDecoder();
  if (typeof worker.cancelAnimationFrame === 'function') worker.cancelAnimationFrame(animation);
  worker.postMessage({ type: 'error', message, unsupported });
}

function present(now: number) {
  if (failed) return;
  const frame = timeline.take(now * 1000);
  if (frame && info) {
    try {
      const rotation = info.effectiveRotation || 0;
      const w = frame.displayWidth, h = frame.displayHeight;
      const width = rotation % 180 ? h : w, height = rotation % 180 ? w : h;
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width; canvas.height = height;
      }
      const start = performance.now();
      context.setTransform(1, 0, 0, 1, 0, 0);
      context.translate(width / 2, height / 2);
      // Mirror is applied in the final, upright coordinate system (same as NV12).
      if (info.mirror) context.scale(-1, 1);
      context.rotate(rotation * Math.PI / 180);
      context.imageSmoothingEnabled = true;
      context.imageSmoothingQuality = 'high';
      context.drawImage(frame, -w / 2, -h / 2, w, h);
      drawMs += performance.now() - start;
      displayed++;
    } finally { frame.close(); }
  }
  if (now - lastReport >= 1000) {
    const seconds = Math.max(0.001, (now - lastReport) / 1000);
    worker.postMessage({ type: 'stats', decodedFps: decoded / seconds, displayedFps: displayed / seconds,
      displayed, width: canvas.width, height: canvas.height, sourceFps: info ? info.fpsNumerator / info.fpsDenominator : 0,
      drawMs: displayed ? drawMs / displayed : 0, decodeMs: decodeSamples ? decodeMs / decodeSamples : 0,
      queue: timeline.queue.length, decoderQueue: decoder?.decodeQueueSize || 0, skipped: timeline.dropped,
      bufferMs: timeline.delay / 1000, colorMatrix: info?.colorMatrix || '', colorRange: info?.colorRange || '' });
    decoded = 0; displayed = 0; drawMs = 0; decodeMs = 0; decodeSamples = 0; lastReport = now;
    if (firstInputAt && now - (lastOutputAt || firstInputAt) > 5000) {
      fail('Preview decoder stopped producing frames; reconnecting'); return;
    }
  }
  animation = worker.requestAnimationFrame(present);
}

async function configure(keyData: Uint8Array) {
  if (!info) throw new Error('Missing OCB2 stream information');
  // This is an SDR pipeline. Preserve explicit OCB2 color even on encoders that
  // omit SPS VUI. Unrepresentable HDR/wide-gamut descriptors use native fallback.
  if (info.colorMatrix === 'bt2020' || info.colorPrimaries === 'bt2020' ||
      (info.colorTransfer && info.colorTransfer !== 'bt709')) {
    fail('This stream requires native color handling', true); return;
  }
  const fallbackStandard = info.width >= 1280 ? 'bt709' : 'smpte170m';
  const colorSpace: VideoColorSpaceInit = {
    matrix: info.colorMatrix === 'bt601' ? 'smpte170m' : info.colorMatrix === 'bt709' ? 'bt709' : fallbackStandard,
    primaries: info.colorPrimaries === 'bt601' ? 'smpte170m' : info.colorPrimaries === 'bt709' ? 'bt709' : fallbackStandard,
    transfer: 'bt709', fullRange: info.colorRange === 'full',
  };
  const codec = avcCodec(config) || avcCodec(keyData);
  if (!codec) throw new Error('No H.264 SPS in codec configuration or keyframe');
  const base: VideoDecoderConfig = { codec, codedWidth: info.width, codedHeight: info.height,
    optimizeForLatency: true, hardwareAcceleration: 'prefer-hardware', colorSpace };
  let supported = await VideoDecoder.isConfigSupported(base);
  if (!supported.supported) supported = await VideoDecoder.isConfigSupported({ ...base, hardwareAcceleration: 'no-preference' });
  if (!supported.supported) { fail(`This WebView cannot decode ${codec}`, true); return; }
  if (failed) return;
  decoder = new VideoDecoder({
    output(frame) {
      const now = performance.now();
      const inputAt = submitted.get(frame.timestamp);
      if (inputAt !== undefined) { decodeMs += now - inputAt; decodeSamples++; submitted.delete(frame.timestamp); }
      lastOutputAt = now;
      decoded++;
      timeline.push(frame, now * 1000);
    },
    error(error) { fail(`H.264 preview: ${error.message}`); },
  });
  decoder.configure(supported.config!);
}

async function record(record: Record) {
  // Discontinuity belongs to the stream, including heartbeat-only recovery.
  if (record.flags & 4) resetDecoder();
  if (record.type === 1) {
    const next = JSON.parse(new TextDecoder().decode(record.payload)) as Info;
    if (!Number.isInteger(next.width) || !Number.isInteger(next.height) || next.width < 2 || next.height < 2 ||
      next.width > 4096 || next.height > 4096 || !Number.isFinite(next.fpsNumerator) ||
      next.fpsNumerator <= 0 || !Number.isFinite(next.fpsDenominator) || next.fpsDenominator <= 0 ||
      ![0, 90, 180, 270].includes(next.effectiveRotation || 0)) throw new Error('Invalid OCB2 video geometry');
    if (info && (next.width !== info.width || next.height !== info.height)) { resetDecoder(); config = new Uint8Array(0); }
    info = next;
    timeline.reset();
    timeline = new PreviewTimeline(next.fpsNumerator / next.fpsDenominator);
  } else if (record.type === 2) {
    resetDecoder();
    config = new Uint8Array(record.payload);
  } else if (record.type === 3 && info) {
    const key = Boolean(record.flags & 2);
    if (waitingForKey && !key) return;
    if (!decoder) await configure(record.payload);
    if (!decoder || failed) return;
    // Never discard a compressed delta and continue a broken reference chain.
    // Reconnect requests a fresh config/IDR without touching native consumers.
    if (decoder.decodeQueueSize > 8 || submitted.size > 16) throw new Error('Preview decoder is falling behind');
    const timestamp = Number(record.encoderTimestampUs);
    if (!Number.isSafeInteger(timestamp) || timestamp < 0 || timestamp <= lastTimestamp) {
      throw new Error('H.264 timestamp discontinuity');
    }
    let data = record.payload;
    if (waitingForKey && config.length) {
      data = new Uint8Array(config.length + data.length);
      data.set(config); data.set(record.payload, config.length);
    }
    waitingForKey = false;
    lastTimestamp = timestamp;
    const now = performance.now();
    if (!firstInputAt) firstInputAt = now;
    submitted.set(timestamp, now);
    decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp, data }));
  } else if (record.type === 5 || record.type === 6 || record.flags & 8) {
    throw new Error('Phone ended the preview stream');
  }
}

worker.onmessage = async ({ data }) => {
  try {
    if (data.type === 'init') {
      if (typeof VideoDecoder === 'undefined' || typeof worker.requestAnimationFrame !== 'function') {
        fail('WebCodecs video rendering is unavailable in this WebView', true); return;
      }
      canvas = data.canvas;
      const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
      if (!ctx) { fail('Offscreen video rendering is unavailable', true); return; }
      context = ctx;
      lastReport = performance.now();
      animation = worker.requestAnimationFrame(present);
      worker.postMessage({ type: 'ready' });
    } else if (data.type === 'chunk' && !failed) {
      parser.push(new Uint8Array(data.bytes));
      for (let item; (item = parser.next()) !== null;) await record(item);
      worker.postMessage({ type: 'consumed' });
    }
  } catch (error) { fail(error instanceof Error ? error.message : String(error)); }
};
