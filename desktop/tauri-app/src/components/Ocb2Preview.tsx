import { useEffect, useRef, useState } from 'react';
import { buildUrl } from '../services/api';

const HEADER_SIZE = 48;
const MAX_PAYLOAD = 16 * 1024 * 1024;
const TYPE_CODEC_CONFIG = 2;
const TYPE_VIDEO_ACCESS_UNIT = 3;
const FLAG_KEYFRAME = 1 << 1;
const FLAG_DISCONTINUITY = 1 << 2;

interface Props {
  baseUrl: string;
  token?: string;
  fitMode: string;
  mirror: boolean;
}

function codecString(config: Uint8Array): string {
  for (let i = 0; i + 8 < config.length; i++) {
    const four = config[i] === 0 && config[i + 1] === 0 && config[i + 2] === 0 && config[i + 3] === 1;
    const three = config[i] === 0 && config[i + 1] === 0 && config[i + 2] === 1;
    const nal = i + (four ? 4 : three ? 3 : 0);
    if ((four || three) && (config[nal] & 0x1f) === 7 && nal + 3 < config.length) {
      return `avc1.${[config[nal + 1], config[nal + 2], config[nal + 3]]
        .map(v => v.toString(16).padStart(2, '0')).join('')}`;
    }
  }
  return 'avc1.42e01f';
}

export default function Ocb2Preview({ baseUrl, token, fitMode, mirror }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const latestFrame = useRef<VideoFrame | null>(null);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!('VideoDecoder' in globalThis)) {
      setError('This Windows WebView does not expose hardware WebCodecs decoding.');
      return;
    }
    const abort = new AbortController();
    let stopped = false;
    let decoder: VideoDecoder | null = null;
    let codecConfig: Uint8Array | null = null;
    let waitingForKeyframe = true;
    let lastTimestamp = -1;
    let animation = 0;

    const draw = () => {
      const frame = latestFrame.current;
      const canvas = canvasRef.current;
      if (frame && canvas) {
        if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
          canvas.width = frame.displayWidth;
          canvas.height = frame.displayHeight;
        }
        const context = canvas.getContext('2d', { alpha: false, desynchronized: true });
        context?.drawImage(frame, 0, 0, canvas.width, canvas.height);
        frame.close();
        latestFrame.current = null;
        setReady(true);
      }
      animation = requestAnimationFrame(draw);
    };
    animation = requestAnimationFrame(draw);

    const configure = async () => {
      if (!codecConfig) return;
      const config: VideoDecoderConfig = {
        codec: codecString(codecConfig),
        optimizeForLatency: true,
        hardwareAcceleration: 'prefer-hardware',
      };
      const support = await VideoDecoder.isConfigSupported(config);
      if (!support.supported) throw new Error(`WebCodecs rejected ${config.codec}`);
      decoder?.close();
      decoder = new VideoDecoder({
        output: frame => {
          // Presentation output is intentionally latest-only. Encoded access
          // units are never skipped, so reference frames still reach decode.
          latestFrame.current?.close();
          latestFrame.current = frame;
        },
        error: failure => setError(`Desktop H.264 preview decoder: ${failure.message}`),
      });
      decoder.configure(config);
      waitingForKeyframe = true;
      lastTimestamp = -1;
    };

    const consume = async (record: Uint8Array) => {
      const view = new DataView(record.buffer, record.byteOffset, record.byteLength);
      const type = view.getUint16(8, true);
      const flags = view.getUint32(12, true);
      const payloadLength = view.getUint32(40, true);
      const payload = record.subarray(HEADER_SIZE, HEADER_SIZE + payloadLength);
      if (type === TYPE_CODEC_CONFIG) {
        codecConfig = payload.slice();
        await configure();
        return;
      }
      if (type !== TYPE_VIDEO_ACCESS_UNIT || !decoder || decoder.state !== 'configured') return;
      if (flags & FLAG_DISCONTINUITY) {
        decoder.reset();
        await configure();
      }
      const keyframe = (flags & FLAG_KEYFRAME) !== 0;
      if (waitingForKeyframe && !keyframe) return;
      if (keyframe) waitingForKeyframe = false;
      const encodedTimestamp = Number(view.getBigInt64(32, true));
      const timestamp = Math.max(lastTimestamp + 1, encodedTimestamp);
      lastTimestamp = timestamp;
      decoder.decode(new EncodedVideoChunk({
        type: keyframe ? 'key' : 'delta',
        timestamp,
        data: payload,
      }));
    };

    const connect = async () => {
      while (!stopped) {
        try {
          const response = await fetch(buildUrl(baseUrl, '/stream.ocb2', token), { signal: abort.signal });
          if (!response.ok || !response.body) throw new Error(`OCB2 HTTP ${response.status}`);
          setError('');
          const reader = response.body.getReader();
          let buffer = new Uint8Array(256 * 1024);
          let length = 0;
          while (!stopped) {
            const result = await reader.read();
            if (result.done) break;
            const chunk = result.value;
            if (length + chunk.length > buffer.length) {
              let capacity = buffer.length;
              while (capacity < length + chunk.length) capacity *= 2;
              if (capacity > MAX_PAYLOAD + HEADER_SIZE) throw new Error('OCB2 receive buffer exceeded protocol maximum');
              const grown = new Uint8Array(capacity);
              grown.set(buffer.subarray(0, length));
              buffer = grown;
            }
            buffer.set(chunk, length);
            length += chunk.length;
            let offset = 0;
            while (length - offset >= HEADER_SIZE) {
              if (buffer[offset] !== 0x4f || buffer[offset + 1] !== 0x43 || buffer[offset + 2] !== 0x42 || buffer[offset + 3] !== 0x32) {
                throw new Error('OCB2 preview received invalid magic');
              }
              const header = new DataView(buffer.buffer, offset, HEADER_SIZE);
              if (header.getUint16(4, true) !== 2 || header.getUint16(6, true) !== HEADER_SIZE) {
                throw new Error('OCB2 preview received an unsupported header');
              }
              const payloadLength = header.getUint32(40, true);
              if (payloadLength > MAX_PAYLOAD) throw new Error('OCB2 preview payload is too large');
              const total = HEADER_SIZE + payloadLength;
              if (length - offset < total) break;
              await consume(buffer.subarray(offset, offset + total));
              offset += total;
            }
            if (offset > 0) {
              buffer.copyWithin(0, offset, length);
              length -= offset;
            }
          }
        } catch (failure: any) {
          if (!stopped && failure?.name !== 'AbortError') setError(failure?.message || String(failure));
        }
        decoder?.close();
        decoder = null;
        codecConfig = null;
        waitingForKeyframe = true;
        if (!stopped) await new Promise(resolve => setTimeout(resolve, 500));
      }
    };
    void connect();
    return () => {
      stopped = true;
      abort.abort();
      cancelAnimationFrame(animation);
      latestFrame.current?.close();
      latestFrame.current = null;
      decoder?.close();
    };
  }, [baseUrl, token]);

  return (
    <>
      <canvas
        ref={canvasRef}
        className={`preview-img ${fitMode === 'fit' ? 'fit-contain' : 'fit-cover'}`}
        style={{ width: '100%', height: '100%', transform: `scaleX(${mirror ? -1 : 1})`, opacity: ready ? 1 : 0 }}
      />
      {(!ready || error) && <div className="preview-overlay">{error || 'Connecting desktop H.264 preview…'}</div>}
    </>
  );
}
