import { MetricRow, Switch } from '../../primitives';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { automaticBitrateMbps } from '../../../services/profilePolicy.js';
import { setPreferences, usePreferences } from '../../../state/preferences';
import type { SettingsContext } from '../context';

export default function PerformancePage({ controller }: SettingsContext) {
  const prefs = usePreferences();
  const m = controller.vcamState?.metrics;
  const am = controller.androidMetrics;
  const preview = controller.previewDiagnostics;
  const webCodecs = preview.renderer === 'webcodecs';
  const s = controller.settings;
  const bitrateMode = s.h264BitrateMode === 'manual'
    ? `Manual · ${Math.round(s.h264Bitrate / 1_000_000)} Mb/s`
    : `Automatic · ${automaticBitrateMbps(s.width, s.height, s.fps)} Mb/s target`;
  const hardware = m?.hardware_decoder == null ? (m?.d3d11_output ? 'D3D11 output (acceleration unconfirmed)' : '—') : m.hardware_decoder ? 'Hardware' : 'Software fallback';

  return (
    <SettingsPage title="Performance" description="What decodes and encodes the video, and how much work this window does.">
      <SettingsSection title="Desktop preview">
        <SettingsRow
          label="Show the live preview"
          description="Turning it off stops this window from decoding. The camera, OBS and other apps keep receiving video."
        >
          <Switch label="Show the live preview" checked={prefs.previewEnabled} onChange={previewEnabled => setPreferences({ previewEnabled })} />
        </SettingsRow>
        <div className="settings-metrics">
          <MetricRow label="Preview state" value={preview.ready ? 'Displaying' : 'Waiting for frames'} tone={preview.ready ? 'ok' : 'muted'} />
          <MetricRow
            label="Renderer"
            value={!preview.ready ? '—' : webCodecs ? 'WebCodecs · full resolution' : 'Compatibility · up to 960 px'}
            tone={preview.ready && !webCodecs ? 'warn' : undefined}
          />
          <MetricRow label="Preview size" value={preview.parsedWidth ? `${preview.parsedWidth}×${preview.parsedHeight}` : '—'} />
          <MetricRow
            label={webCodecs ? 'Decoded / displayed' : 'Displayed'}
            value={!preview.ready ? '—' : webCodecs
              ? `${preview.previewReceivedFps.toFixed(0)} / ${preview.previewDisplayedFps.toFixed(0)} fps`
              : `${preview.previewDisplayedFps} fps`}
          />
          {webCodecs && preview.ready && (
            <MetricRow label="Decode / draw" value={`${preview.decodeMs.toFixed(1)} / ${preview.previewUploadMs.toFixed(1)} ms`} />
          )}
          <MetricRow label="Colour" value={preview.colorMatrix ? `${preview.colorMatrix} · ${preview.colorRange} range` : '—'} />
        </div>
      </SettingsSection>

      <SettingsSection
        title="Virtual camera decoding"
        description={m ? undefined : 'Shown while the virtual camera or the compatibility preview is running.'}
      >
        <div className="settings-metrics">
          <MetricRow label="Decoder" value={m?.decoder_name || m?.decode_backend || '—'} />
          <MetricRow label="Acceleration" value={hardware} tone={m?.hardware_decoder === false ? 'warn' : m?.hardware_decoder ? 'ok' : undefined} />
          <MetricRow label="GPU output" value={m ? (m.d3d11_output ? 'D3D11' : 'System memory') : '—'} />
          <MetricRow label="Pixel format" value={m?.pixel_format || '—'} />
          <MetricRow label="Resize" value={m ? (m.source_width !== m.output_width ? `${m.resize_backend || 'GPU'} · ${m.source_width}×${m.source_height} → ${m.output_width}×${m.output_height}` : 'Not needed') : '—'} />
          <MetricRow label="Decode time" value={m ? `${m.decode_ms_avg} ms` : '—'} />
        </div>
      </SettingsSection>

      <SettingsSection title="Encoding on the phone">
        <div className="settings-metrics">
          <MetricRow label="Encoder" value={am?.encoderName || (am?.activeStreamMode === 'mjpeg' ? 'JPEG' : '—')} />
          <MetricRow label="Acceleration" value={am?.encoderName ? (am.hardwareEncoder ? 'Hardware' : 'Software fallback') : '—'} tone={am?.encoderName && !am.hardwareEncoder ? 'warn' : undefined} />
          <MetricRow label="Capture" value={am?.captureEngine === 'HIGH_SPEED_GPU_BRIDGE' ? 'High-speed GPU bridge' : am?.captureEngine ? 'Direct camera surface' : '—'} />
          {s.streamMode !== 'mjpeg' && <MetricRow label="Bitrate" value={bitrateMode} />}
          <MetricRow label="Measured bitrate" value={am?.encodedBitrate ? `${(am.encodedBitrate / 1_000_000).toFixed(1)} Mb/s` : '—'} />
        </div>
      </SettingsSection>
    </SettingsPage>
  );
}
