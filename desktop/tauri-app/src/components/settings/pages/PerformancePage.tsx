import { MetricRow, Switch } from '../../primitives';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { setPreferences, usePreferences } from '../../../state/preferences';
import type { SettingsContext } from '../context';

export default function PerformancePage({ controller }: SettingsContext) {
  const prefs = usePreferences();
  const m = controller.vcamState?.metrics;
  const am = controller.androidMetrics;
  const preview = controller.previewDiagnostics;
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
          <MetricRow label="Preview size" value={preview.parsedWidth ? `${preview.parsedWidth}×${preview.parsedHeight}` : '—'} />
          <MetricRow label="Displayed" value={preview.ready ? `${preview.previewDisplayedFps} fps` : '—'} />
          <MetricRow label="Colour" value={preview.colorMatrix ? `${preview.colorMatrix} · ${preview.colorRange} range` : '—'} />
        </div>
      </SettingsSection>

      <SettingsSection
        title="Decoding on this PC"
        description={m ? undefined : 'Shown once the decoder is running (H.264 preview or OpenCamBridge Camera).'}
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
          <MetricRow label="Measured bitrate" value={am?.encodedBitrate ? `${(am.encodedBitrate / 1_000_000).toFixed(1)} Mb/s` : '—'} />
        </div>
      </SettingsSection>
    </SettingsPage>
  );
}
