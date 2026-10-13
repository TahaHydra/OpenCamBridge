import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import ts from 'typescript';
import * as policy from './h264PreviewPolicy.js';

const require = createRequire(import.meta.url);
const parser = require('../../../../android/app/src/main/assets/ocb2-parser.js');
const code = ts.transpileModule(fs.readFileSync(new URL('./h264Preview.worker.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;

function runtime(supported = true, drainAsynchronously = false) {
  const messages = [], configurations = [], decoders = [];
  class Decoder {
    static async isConfigSupported(config) { return { supported: true, config }; }
    constructor(callbacks) { this.callbacks = callbacks; this.state = 'unconfigured'; this.decodeQueueSize = 0; this.maxQueue = 0; this.chunks = []; this.listeners = new Set(); decoders.push(this); }
    configure(config) { configurations.push(config); this.state = 'configured'; }
    decode(chunk) {
      this.chunks.push(chunk);
      if (!drainAsynchronously) return;
      this.decodeQueueSize++;
      this.maxQueue = Math.max(this.maxQueue, this.decodeQueueSize);
      setTimeout(() => {
        if (this.state !== 'configured') return;
        this.decodeQueueSize--;
        this.callbacks.output({ timestamp: chunk.timestamp, displayWidth: 16, displayHeight: 16, close() {} });
        for (const listener of this.listeners) listener();
      }, 1);
    }
    addEventListener(type, listener) { if (type === 'dequeue') this.listeners.add(listener); }
    close() { assert.notEqual(this.state, 'closed'); this.state = 'closed'; }
  }
  const scope = { exports: {}, performance, Uint8Array, DataView, TextDecoder, setTimeout, clearTimeout,
    Ocb2Browser: parser, postMessage: message => messages.push(message),
    require: name => name.endsWith('h264PreviewPolicy.js') ? policy : parser,
    EncodedVideoChunk: class { constructor(options) { Object.assign(this, options); } },
    ...(supported ? { VideoDecoder: Decoder, requestAnimationFrame: () => 1, cancelAnimationFrame: () => {} } : {}),
  };
  scope.self = scope;
  const context = vm.createContext(scope);
  vm.runInContext(code, context);
  return { messages, configurations, decoders, send: data => context.onmessage({ data }) };
}
function record(type, flags, payload = new Uint8Array(), timestamp = 1000n) {
  const bytes = new Uint8Array(48 + payload.length);
  const view = new DataView(bytes.buffer);
  view.setUint32(0, 0x4f434232); view.setUint16(4, 2, true); view.setUint16(6, 48, true);
  view.setUint16(8, type, true); view.setUint32(12, flags, true); view.setBigInt64(32, timestamp, true);
  view.setUint32(40, payload.length, true); bytes.set(payload, 48);
  return { type: 'chunk', bytes: bytes.buffer };
}
test('worker without video/animation APIs reports immediate compatibility fallback', async () => {
  const r = runtime(false);
  await r.send({ type: 'init', canvas: {} });
  assert.equal(r.messages.length, 1);
  assert.equal(r.messages[0].type, 'error');
  assert.equal(r.messages[0].unsupported, true);
});

test('late-join cached GOP drains through bounded decoder backpressure without reconnecting', async () => {
  const r = runtime(true, true);
  await r.send({ type: 'init', canvas: { getContext: () => ({}) } });
  const info = { width: 16, height: 16, fpsNumerator: 30, fpsDenominator: 1, effectiveRotation: 0 };
  const packets = [record(1, 4, new TextEncoder().encode(JSON.stringify(info))),
    record(2, 1, Uint8Array.of(0, 0, 0, 1, 0x67, 100, 0, 42))];
  for (let i = 0; i < 40; i++) packets.push(record(3, i === 0 ? 2 : 0,
    Uint8Array.of(0, 0, 1, i === 0 ? 0x65 : 0x61), BigInt((i + 1) * 33333)));
  const bytes = new Uint8Array(packets.reduce((size, packet) => size + packet.bytes.byteLength, 0));
  let offset = 0;
  for (const packet of packets) { bytes.set(new Uint8Array(packet.bytes), offset); offset += packet.bytes.byteLength; }
  await r.send({ type: 'chunk', bytes: bytes.buffer });
  assert.deepEqual(r.messages.filter(message => message.type === 'error'), []);
  assert.equal(r.messages.filter(message => message.type === 'consumed').length, 1);
  assert.equal(r.decoders[0].chunks.length, 40);
  assert.ok(r.decoders[0].maxQueue <= 8, `peak decoder queue ${r.decoders[0].maxQueue}`);
  assert.equal(r.decoders[0].chunks[0].type, 'key');
  assert.ok(r.decoders[0].chunks.slice(1).every(chunk => chunk.type === 'delta'));
});
test('actual worker configures OCB2 colors, waits for IDR after heartbeat discontinuity and handles closed decoder errors', async () => {
  const r = runtime();
  await r.send({ type: 'init', canvas: { getContext: () => ({}) } });
  const info = { width: 1920, height: 1080, fpsNumerator: 30, fpsDenominator: 1,
    effectiveRotation: 90, mirror: true, colorMatrix: 'bt709', colorPrimaries: 'bt709', colorRange: 'full', colorTransfer: 'bt709' };
  await r.send(record(1, 4, new TextEncoder().encode(JSON.stringify(info))));
  await r.send(record(2, 1, Uint8Array.of(0, 0, 0, 1, 0x67, 100, 0, 42)));
  await r.send(record(3, 0, Uint8Array.of(0, 0, 1, 0x61)));
  assert.equal(r.configurations.length, 0);
  await r.send(record(3, 2, Uint8Array.of(0, 0, 1, 0x65)));
  assert.equal(r.configurations[0].codec, 'avc1.64002a');
  assert.equal(r.configurations[0].colorSpace.fullRange, true);
  assert.equal(r.configurations[0].colorSpace.primaries, 'bt709');
  await r.send(record(4, 4));
  assert.equal(r.decoders[0].state, 'closed');
  await r.send(record(3, 0, Uint8Array.of(0, 0, 1, 0x61), 2000n));
  assert.equal(r.configurations.length, 1);
  await r.send(record(3, 2, Uint8Array.of(0, 0, 1, 0x65), 3000n));
  assert.equal(r.configurations.length, 2);
  r.decoders[1].state = 'closed'; // WebCodecs closes before invoking its error callback.
  r.decoders[1].callbacks.error(new Error('device lost'));
  assert.equal(r.messages.at(-1).type, 'error');
});
