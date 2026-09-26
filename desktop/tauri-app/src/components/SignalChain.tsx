import { AlertTriangle, CheckCircle2, CircleSlash, Info } from 'lucide-react';
import { Lamp, type Health } from './ui';
import { describePipeline, healthyFloor, stageHealth } from '../services/signalChainPolicy.js';

interface SignalChainProps {
  targetFps: number;
  transport: 'USB' | 'LAN';
  androidRunning: boolean;
  androidMetrics: any;
  metrics: any;
  producerRunning: boolean;
  consumerAttached: boolean;
  streamMode: string;
}

type Stage = {
  key: string;
  name: string;
  rate: number | null;
  detail: string;
  health: Health;
  /** Rendered instead of the frame rate when a stage has no meaningful rate. */
  placeholder?: string;
};

const num = (value: any): number => (Number.isFinite(Number(value)) ? Number(value) : 0);

/** Encoder/decoder component names are long and vendor-prefixed. Keep the part
 *  a human can act on ("c2.qti.avc.encoder" -> "qti.avc"). */
function shortCodec(name?: string): string {
  if (!name) return '';
  const cleaned = name
    .replace(/^(OMX|c2|C2)\./i, '')
    .replace(/\.(encoder|decoder)$/i, '')
    .replace(/video\./i, '');
  return cleaned.length > 18 ? `${cleaned.slice(0, 17)}…` : cleaned;
}

function engineLabel(engine?: string): string {
  if (!engine) return '';
  if (engine === 'REGULAR_SURFACE') return 'direct surface';
  if (engine === 'HIGH_SPEED_GPU_BRIDGE') return 'GPU bridge';
  return engine.toLowerCase().replace(/_/g, ' ');
}

/**
 * The pipeline overview. Five measurable stages, each with the rate it is
 * actually sustaining, so a shortfall is attributed to the stage that caused
 * it instead of being read off a wall of counters. This replaces "is the
 * process alive?" with "where are frames being lost?".
 */
export default function SignalChain({
  targetFps,
  transport,
  androidRunning,
  androidMetrics,
  metrics,
  producerRunning,
  consumerAttached,
  streamMode,
}: SignalChainProps) {
  const target = targetFps > 0 ? targetFps : 30;
  const floor = healthyFloor(target);

  const capture = num(androidMetrics?.captureFps ?? androidMetrics?.actualFps);
  const encode = num(androidMetrics?.encodedFps);
  const transportFps = num(metrics?.transport_fps ?? metrics?.http_jpeg_fps);
  const decode = num(metrics?.decoded_unique_fps ?? metrics?.decoded_fps);
  const output = num(metrics?.virtual_camera_unique_fps ?? metrics?.written_fps);

  const rate = (value: number, live: boolean): Health => stageHealth(value, live, floor) as Health;

  const encodedMbps = num(androidMetrics?.encodedBitrate) / 1_000_000;
  const linkMbps = Number(metrics?.estimated_mbps);
  const decodeMs = num(metrics?.decode_ms_avg);
  const outWidth = num(metrics?.ring?.negotiated_width || metrics?.output_width);
  const outHeight = num(metrics?.ring?.negotiated_height || metrics?.output_height);

  const stages: Stage[] = [
    {
      key: 'lens',
      name: 'Lens',
      rate: androidRunning ? capture : null,
      placeholder: 'off',
      detail: androidRunning
        ? [
            `${num(androidMetrics?.encodedWidth)}×${num(androidMetrics?.encodedHeight)}`,
            engineLabel(androidMetrics?.captureEngine),
          ]
            .filter(Boolean)
            .join(' · ')
        : 'phone camera stopped',
      health: rate(capture, androidRunning),
    },
    {
      key: 'encode',
      name: 'Encode',
      rate: androidRunning ? encode : null,
      placeholder: 'off',
      detail: androidRunning
        ? [
            shortCodec(androidMetrics?.encoderName) || streamMode.toUpperCase(),
            androidMetrics?.hardwareEncoder === false ? 'software' : null,
            encodedMbps > 0 ? `${encodedMbps.toFixed(1)} Mb/s` : null,
          ]
            .filter(Boolean)
            .join(' · ')
        : 'on the phone',
      health: rate(encode, androidRunning),
    },
    {
      key: 'link',
      name: transport === 'USB' ? 'USB link' : 'Wi-Fi link',
      rate: producerRunning ? transportFps : null,
      placeholder: 'idle',
      detail: producerRunning
        ? [
            transport === 'USB' ? 'adb forward' : 'token auth',
            Number.isFinite(linkMbps) && linkMbps > 0 ? `${linkMbps.toFixed(1)} Mb/s` : null,
          ]
            .filter(Boolean)
            .join(' · ')
        : 'producer not running',
      health: rate(transportFps, producerRunning),
    },
    {
      key: 'decode',
      name: 'Decode',
      rate: producerRunning ? decode : null,
      placeholder: 'idle',
      detail: producerRunning
        ? [
            metrics?.decode_backend === 'media-foundation-d3d11'
              ? metrics?.d3d11_output
                ? 'MF · D3D11'
                : 'MF · system memory'
              : metrics?.decode_backend
                ? 'software'
                : 'starting',
            decodeMs > 0 ? `${decodeMs} ms` : null,
          ]
            .filter(Boolean)
            .join(' · ')
        : 'on this PC',
      health: rate(decode, producerRunning),
    },
    {
      key: 'output',
      name: 'Virtual cam',
      rate: consumerAttached ? output : null,
      placeholder: consumerAttached ? '0' : 'no app',
      detail: consumerAttached
        ? [outWidth && outHeight ? `${outWidth}×${outHeight}` : null, `${num(metrics?.repeated_samples)} repeats`]
            .filter(Boolean)
            .join(' · ')
        : producerRunning
          ? 'waiting for OBS / Teams'
          : 'not published',
      health: consumerAttached ? rate(output, true) : 'idle',
    },
  ];

  // A link carries frames when the stage upstream of it is producing and this
  // one is too; it is stalled when frames go in and nothing comes out.
  const rates = [capture, encode, transportFps, decode, output];
  const linkState = (index: number): string => {
    if (index === 0) return '';
    const upstream = rates[index - 1];
    const here = rates[index];
    if (upstream > 0 && here > 0) return ' is-flowing';
    if (upstream > 0 && here === 0) return ' is-stalled';
    return '';
  };

  const verdict = describePipeline({
    target,
    capture,
    encode,
    transportFps,
    decode,
    output,
    androidRunning,
    producerRunning,
    consumerAttached,
    softwareDecode: metrics?.decode_backend != null && metrics.decode_backend !== 'media-foundation-d3d11',
    softwareEncode: androidMetrics?.hardwareEncoder === false,
    fallbackReason: androidMetrics?.fallbackReason || metrics?.fallback_reason || '',
  });

  return (
    <>
      <div className="chain">
        {stages.map((stage, index) => (
          <div
            key={stage.key}
            className={`stage is-${stage.health === 'ok' ? 'ok' : stage.health === 'degraded' ? 'degraded' : stage.health === 'down' ? 'down' : 'idle'}${
              linkState(index)
            }${verdict.at === stage.key ? ' is-bottleneck' : ''}`}
          >
            <div className="stage__top">
              <Lamp state={stage.health} />
              <span className="stage__name">{stage.name}</span>
            </div>
            <div className="stage__value">
              {stage.rate === null ? stage.placeholder : stage.rate}
              <small>{stage.rate === null ? ' ' : 'fps'}</small>
            </div>
            <div className="stage__detail" title={stage.detail}>
              {stage.detail}
            </div>
          </div>
        ))}
      </div>

      <div className={`chain-verdict is-${verdict.kind}`}>
        {verdict.kind === 'ok' ? (
          <CheckCircle2 size={13} />
        ) : verdict.kind === 'fail' ? (
          <CircleSlash size={13} />
        ) : verdict.kind === 'warn' ? (
          <AlertTriangle size={13} />
        ) : (
          <Info size={13} />
        )}
        <span>{verdict.text}</span>
      </div>
    </>
  );
}
