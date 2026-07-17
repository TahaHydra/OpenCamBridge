/* OpenCamBridge OCB2 browser parser. The shared corpus is protocol/conformance/ocb2-corpus.json. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.Ocb2Browser = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const MAGIC = 0x4f434232;
  const VERSION = 2;
  const HEADER_SIZE = 48;
  const MAX_PAYLOAD = 16 * 1024 * 1024;

  class ParseError extends Error {
    constructor(code, message) {
      super(message);
      this.name = 'Ocb2ParseError';
      this.code = code;
    }
  }

  class Parser {
    constructor() {
      this.buffer = new Uint8Array(0);
    }

    reset() {
      this.buffer = new Uint8Array(0);
    }

    push(input) {
      const incoming = input instanceof Uint8Array ? input : new Uint8Array(input);
      if (!incoming.length) return;
      const joined = new Uint8Array(this.buffer.length + incoming.length);
      joined.set(this.buffer);
      joined.set(incoming, this.buffer.length);
      this.buffer = joined;
    }

    next() {
      if (this.buffer.length < HEADER_SIZE) return null;
      const view = new DataView(this.buffer.buffer, this.buffer.byteOffset, this.buffer.byteLength);
      if (view.getUint32(0, false) !== MAGIC) throw new ParseError('BAD_MAGIC', 'OCB2 magic mismatch');
      const version = view.getUint16(4, true);
      if (version !== VERSION) throw new ParseError('UNSUPPORTED_VERSION', 'Unsupported OCB2 version ' + version);
      const headerSize = view.getUint16(6, true);
      if (headerSize !== HEADER_SIZE) throw new ParseError('INVALID_HEADER_SIZE', 'Invalid OCB2 header size ' + headerSize);
      const type = view.getUint16(8, true);
      if (type < 1 || type > 6) throw new ParseError('INVALID_RECORD_TYPE', 'Invalid OCB2 record type ' + type);
      const payloadLength = view.getUint32(40, true);
      if (payloadLength > MAX_PAYLOAD) throw new ParseError('PAYLOAD_TOO_LARGE', 'OCB2 payload exceeds canonical limit');
      const total = HEADER_SIZE + payloadLength;
      if (this.buffer.length < total) return null;
      const record = {
        type,
        flags: view.getUint32(12, true),
        sequence: Number(view.getBigUint64(16, true)),
        captureTimestampNs: view.getBigUint64(24, true),
        encoderTimestampUs: view.getBigInt64(32, true),
        payload: this.buffer.slice(HEADER_SIZE, total)
      };
      this.buffer = this.buffer.slice(total);
      return record;
    }
  }

  return { Parser, ParseError, MAGIC, VERSION, HEADER_SIZE, MAX_PAYLOAD };
}));
