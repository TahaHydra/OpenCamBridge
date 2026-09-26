import type { ReactNode } from 'react';
import {
  Expand,
  FlipHorizontal2,
  Flashlight,
  Minus,
  Plus,
  RotateCw,
  Shrink,
} from 'lucide-react';
import DevicePicker from './DevicePicker';
import CommitSlider from './CommitSlider';
import { BITRATE_HINT, BitrateModeSwitch, ManualBitrateSlider, automaticBitrateLabel } from './BitrateControl';
import { Field, Group, IconButton, MetricRow, Segmented, Select, Slider, SwitchRow } from '../primitives';
import { codecLabel, heightLabel, matchProfile } from '../../services/profilePolicy.js';
import { useProfiles } from '../../state/profiles';
import type { CameraController } from '../../state/useCameraController';
import type { AdbDevice, ConnectionInfo } from '../../state/types';

/**
 * The left inspector: the camera, not the pipeline. Everything here is
 * something a person recognises — the phone, the lens, 1080p, 30 FPS, H.264,
 * the torch — and every choice offered is one the phone reported it can do.
 */
export default function CameraRail({
  controller,
  connection,
  advanced,
  fitMode,
  onFitModeChange,
  mirrorPreview,
  onMirrorPreviewChange,
  onSwitchDevice,
  onDisconnect,
}: {
  controller: CameraController;
  connection: ConnectionInfo;
  advanced: boolean;
  fitMode: 'fit' | 'fill';
  onFitModeChange: (mode: 'fit' | 'fill') => void;
  mirrorPreview: boolean;
  onMirrorPreviewChange: (value: boolean) => void;
  onSwitchDevice: (device: AdbDevice) => void;
  onDisconnect: () => void;
}) {
  const { settings, cameras, activeCam, phoneState, isSyncing } = controller;
  const reachable = phoneState !== 'offline' && phoneState !== 'connecting';
  // Capture changes wait for the one in flight; runtime controls never do.
  const captureLocked = !reachable || isSyncing;
  const hasH264 = (activeCam?.h264Modes?.length ?? 0) > 0;
  const hasMjpeg = (activeCam?.mjpegModes?.length ?? 0) > 0;
  const zoomRatioMax = Number(activeCam?.zoomRatioMax ?? 1);
  const zoomSupported = !activeCam || zoomRatioMax > 1;
  const zoomRatio = Number(controller.serverStatus?.zoomRatio ?? 0);
  const rotation = parseInt(settings.displayRotation, 10) || 0;
  // The profile menu and these controls are one system: say which profile the
  // settings below amount to, or that they are Custom.
  const profiles = useProfiles(activeCam);
  const matchedProfile = matchProfile(profiles, settings);

  return (
    <aside className="rail rail--left" aria-label="Camera controls">
      <Group title="Device">
        <DevicePicker
          controller={controller}
          connection={connection}
          onSwitchDevice={onSwitchDevice}
          onDisconnect={onDisconnect}
        />
        <Field label="Camera">
          <Select
            label="Camera lens"
            value={settings.cameraId}
            disabled={captureLocked}
            placeholder={cameras.length ? 'Select a camera' : 'Waiting for cameras…'}
            options={cameras.map(camera => ({
              value: camera.id,
              label: camera.label || `${camera.facing} camera ${camera.id}`,
            }))}
            onChange={id => void controller.updateSetting('cameraId', id)}
          />
        </Field>
      </Group>

      <Group
        title="Video"
        aside={reachable ? (
          <span
            className={`rail__profile-tag${matchedProfile ? '' : ' is-custom'}`}
            title={matchedProfile
              ? `These settings are the ${matchedProfile.name} profile.`
              : 'These settings match no profile. Pick one from the profile menu at the top, or save these as a new one.'}
          >
            {matchedProfile ? matchedProfile.name : 'Custom'}
          </span>
        ) : undefined}
      >
        <Field label="Resolution">
          <Select
            label="Resolution"
            value={`${settings.width}x${settings.height}`}
            disabled={captureLocked}
            placeholder="No modes on this camera"
            options={controller.resolutionChoices.map(choice => ({
              value: `${choice.width}x${choice.height}`,
              label: `${heightLabel(choice.height)} · ${choice.width}×${choice.height}`,
            }))}
            onChange={value => {
              const [w, h] = value.split('x').map(Number);
              void controller.updateResolution(w, h);
            }}
          />
        </Field>

        <Field
          label="Frame rate"
          hint={controller.fpsChoices.length === 1 && activeCam
            ? `The only rate ${activeCam.label} offers at ${heightLabel(settings.height)}.`
            : undefined}
        >
          {controller.fpsChoices.length === 1 ? (
            <div className="ui-static tabular">{controller.fpsChoices[0]} FPS</div>
          ) : controller.fpsChoices.length > 1 && controller.fpsChoices.length <= 3 ? (
            <Segmented
              label="Frame rate"
              value={settings.fps}
              disabled={captureLocked}
              options={controller.fpsChoices.map(fps => ({ value: fps, label: `${fps} FPS` }))}
              onChange={fps => void controller.updateFps(fps)}
            />
          ) : (
            <Select
              label="Frame rate"
              value={settings.fps}
              disabled={captureLocked}
              placeholder="No rate at this resolution"
              options={controller.fpsChoices.map(fps => ({ value: fps, label: `${fps} FPS` }))}
              onChange={fps => void controller.updateFps(fps)}
            />
          )}
        </Field>

        <Field label="Format">
          <Segmented
            label="Video format"
            value={settings.streamMode === 'mjpeg' ? 'mjpeg' : 'h264'}
            disabled={captureLocked}
            options={[
              { value: 'h264', label: 'H.264', disabled: !!activeCam && !hasH264, title: hasH264 || !activeCam ? 'Hardware H.264 — recommended' : 'This camera offers no H.264 mode' },
              { value: 'mjpeg', label: 'MJPEG', disabled: !!activeCam && !hasMjpeg, title: hasMjpeg || !activeCam ? 'Compatibility mode' : 'This camera offers no MJPEG mode' },
            ]}
            onChange={mode => void controller.updateSetting('streamMode', mode)}
          />
        </Field>
      </Group>

      <Group title="Adjust">
        <div className="tool-grid">
          <ToolTile
            icon={<Flashlight size={17} />}
            label="Torch"
            active={settings.torchEnabled}
            disabled={!reachable || !controller.torchSupported}
            title={controller.torchSupported ? 'Turn the phone light on or off' : 'This camera has no light'}
            onClick={() => void controller.updateSetting('torchEnabled', !settings.torchEnabled)}
          />
          <ToolTile
            icon={<RotateCw size={17} />}
            label={rotation ? `Rotate · ${rotation}°` : 'Rotate'}
            active={rotation !== 0}
            disabled={captureLocked}
            title="Turn the picture a further 90°"
            onClick={() => void controller.rotateOutput()}
          />
          <ToolTile
            icon={<FlipHorizontal2 size={17} />}
            label="Mirror output"
            active={settings.mirror}
            disabled={captureLocked}
            title="Flips the video apps receive"
            onClick={() => void controller.updateSetting('mirror', !settings.mirror)}
          />
        </div>

        <Field
          label="Zoom"
          trailing={zoomRatio > 0 ? `${zoomRatio.toFixed(1)}×` : `${Math.round(settings.linearZoom * 100)}%`}
          hint={!zoomSupported ? 'This camera does not zoom.' : undefined}
        >
          <div className="zoom-row">
            <IconButton
              size="sm"
              label="Zoom out"
              icon={<Minus size={14} />}
              disabled={!reachable || !zoomSupported || settings.linearZoom <= 0}
              onClick={() => controller.setZoom(settings.linearZoom - 0.1)}
            />
            <Slider
              label="Zoom"
              value={Math.round(settings.linearZoom * 100)}
              min={0}
              max={100}
              disabled={!reachable || !zoomSupported}
              onChange={value => controller.setZoom(value / 100)}
            />
            <IconButton
              size="sm"
              label="Zoom in"
              icon={<Plus size={14} />}
              disabled={!reachable || !zoomSupported || settings.linearZoom >= 1}
              onClick={() => controller.setZoom(settings.linearZoom + 0.1)}
            />
          </div>
        </Field>
      </Group>

      <Group title="Preview">
        <Field label="Preview framing" hint="Affects only this preview.">
          <Segmented
            label="Preview framing"
            value={fitMode}
            options={[
              { value: 'fit', label: 'Fit', icon: <Shrink size={14} />, title: 'Show the whole picture in this preview' },
              { value: 'fill', label: 'Fill', icon: <Expand size={14} />, title: 'Fill this preview, cropping the edges' },
            ]}
            onChange={onFitModeChange}
          />
        </Field>
        <SwitchRow
          label="Mirror preview"
          description="Flips only this preview, like a mirror."
          checked={mirrorPreview}
          onChange={onMirrorPreviewChange}
        />
        {advanced && (
          <Field label="Preview layout" hint="Shapes only this preview. Choosing one also resets Rotate.">
            <Segmented
              size="sm"
              label="Preview layout"
              value={controller.orientationMode}
              disabled={captureLocked}
              options={[
                { value: 'auto', label: 'Auto' },
                { value: '16:9', label: 'Landscape' },
                { value: '9:16', label: 'Portrait' },
              ]}
              onChange={mode => void controller.updateOrientationMode(mode)}
            />
          </Field>
        )}
      </Group>

      {advanced && <EncodingGroup controller={controller} disabled={captureLocked} />}
    </aside>
  );
}

function ToolTile({
  icon,
  label,
  active,
  disabled,
  title,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  active?: boolean;
  disabled?: boolean;
  title?: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`tool-tile${active ? ' is-active' : ''}`}
      aria-pressed={!!active}
      disabled={disabled}
      title={title}
      onClick={onClick}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

/**
 * Advanced only: quality knobs and what the selected lens can really do.
 * Rarely needed encoder internals (the keyframe safety interval) live in
 * Settings › Advanced › Developer options instead.
 */
function EncodingGroup({ controller, disabled }: { controller: CameraController; disabled: boolean }) {
  const { settings, activeCam, androidMetrics } = controller;
  const h264 = settings.streamMode !== 'mjpeg';
  const autoBitrate = settings.h264BitrateMode !== 'manual';
  const summarize = (modes?: { width: number; height: number; fps: number }[]) =>
    modes && modes.length
      ? Array.from(new Set(modes.map(m => `${heightLabel(m.height)}${m.fps}`))).join(' · ')
      : 'none';

  return (
    <Group title="Quality" aside={<span className="rail__advanced-tag">Advanced</span>}>
      {h264 ? (
        <Field label="Bitrate" hint={autoBitrate ? BITRATE_HINT.auto : BITRATE_HINT.manual}>
          <BitrateModeSwitch controller={controller} disabled={disabled} />
          {autoBitrate
            ? <div className="ui-static tabular">{automaticBitrateLabel(settings)}</div>
            : <ManualBitrateSlider controller={controller} disabled={disabled} />}
        </Field>
      ) : (
        <>
          <Field label="JPEG quality">
            <CommitSlider
              label="JPEG quality"
              value={settings.jpegQuality}
              min={40}
              max={95}
              disabled={disabled}
              format={value => `${value}%`}
              onCommit={value => void controller.updateSetting('jpegQuality', value)}
            />
          </Field>
          <Field label="Target bandwidth" hint="Adjusts JPEG quality automatically. 0 turns it off.">
            <CommitSlider
              label="Target bandwidth"
              value={settings.targetBandwidthMbps}
              min={0}
              max={50}
              disabled={disabled}
              format={value => (value === 0 ? 'Off' : `${value} Mb/s`)}
              onCommit={value => void controller.updateSetting('targetBandwidthMbps', value)}
            />
          </Field>
        </>
      )}
      <div className="rail__metrics">
        <MetricRow label="H.264 modes" value={summarize(activeCam?.h264Modes)} />
        <MetricRow label="MJPEG modes" value={summarize(activeCam?.mjpegModes)} />
        <MetricRow label="Capture path" value={captureEngineLabel(androidMetrics?.captureEngine)} tone="muted" />
        <MetricRow label="Active format" value={codecLabel(androidMetrics?.activeStreamMode || settings.streamMode)} tone="muted" />
      </div>
    </Group>
  );
}

function captureEngineLabel(engine?: string): string {
  if (!engine) return '—';
  if (engine === 'REGULAR_SURFACE') return 'Direct surface';
  if (engine === 'HIGH_SPEED_GPU_BRIDGE') return 'High-speed GPU bridge';
  return engine.toLowerCase().replace(/_/g, ' ');
}
