import { useState, type ReactNode } from 'react';
import {
  Activity,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Clapperboard,
  Maximize2,
  Settings2,
  ShieldAlert,
  Webcam,
} from 'lucide-react';
import { Button, Callout, IconButton, MetricRow, StatusDot } from '../primitives';
import { VIRTUAL_CAMERA_NAME } from '../../services/obs';
import { VIRTUAL_CAMERA_LABEL, VIRTUAL_CAMERA_TONE, type Tone } from '../../state/status';
import type { CameraController } from '../../state/useCameraController';
import type { ObsIntegration } from '../../state/useObs';

/**
 * Outputs: where the picture goes. OpenCamBridge Camera and OBS are the
 * product's primary workflows, so they sit on the main screen rather than
 * under diagnostics.
 */
export default function OutputsRail({
  controller,
  obs,
  advanced,
  onEnterCleanFeed,
  onOpenIntegrations,
  onOpenDiagnostics,
}: {
  controller: CameraController;
  obs: ObsIntegration;
  advanced: boolean;
  onEnterCleanFeed: () => void;
  onOpenIntegrations: () => void;
  onOpenDiagnostics: () => void;
}) {
  return (
    <aside className="rail rail--right" aria-label="Outputs">
      <div className="rail__heading">Outputs</div>
      <VirtualCameraCard controller={controller} onOpenDiagnostics={onOpenDiagnostics} />
      <ObsCard controller={controller} obs={obs} onOpenIntegrations={onOpenIntegrations} />
      <OutputCard
        icon={<Maximize2 size={17} />}
        title="Clean feed"
        description="A full-window picture without controls, for OBS window capture or screen sharing."
      >
        <Button block onClick={onEnterCleanFeed}>Open clean feed</Button>
      </OutputCard>
      {advanced && <PerformanceCard controller={controller} onOpenDiagnostics={onOpenDiagnostics} />}
    </aside>
  );
}

function OutputCard({
  icon,
  title,
  status,
  description,
  children,
  aside,
}: {
  icon: ReactNode;
  title: string;
  status?: { tone: Tone; label: string };
  description?: ReactNode;
  children?: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <section className="output-card">
      <header className="output-card__head">
        <span className="output-card__icon">{icon}</span>
        <div className="output-card__heading">
          <h3 className="output-card__title" title={title}>{title}</h3>
          {status && (
            <div className={`output-card__status is-${status.tone}`}>
              <StatusDot tone={status.tone} />
              {status.label}
            </div>
          )}
        </div>
        {aside}
      </header>
      {description && <p className="output-card__description">{description}</p>}
      {children && <div className="output-card__body">{children}</div>}
    </section>
  );
}

function VirtualCameraCard({ controller, onOpenDiagnostics }: { controller: CameraController; onOpenDiagnostics: () => void }) {
  const { vcamState, virtualCamera } = controller;
  const [busy, setBusy] = useState(false);
  const ring = vcamState?.metrics?.ring;
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    try { await action(); } finally { setBusy(false); }
  };

  const description =
    virtualCamera === 'live' ? 'An app is using the camera right now.'
      : virtualCamera === 'ready' ? `Select “${VIRTUAL_CAMERA_NAME}” in Zoom, Teams, Discord, OBS or your browser.`
        : virtualCamera === 'starting' ? 'Starting the Windows camera…'
          : virtualCamera === 'not-installed' ? 'The Windows camera driver has to be registered once before apps can see it.'
            : `Makes your phone available as “${VIRTUAL_CAMERA_NAME}” in Zoom, Teams, Discord, OBS and browsers.`;

  return (
    <OutputCard
      icon={<Webcam size={17} />}
      title={VIRTUAL_CAMERA_NAME}
      status={{ tone: VIRTUAL_CAMERA_TONE[virtualCamera], label: VIRTUAL_CAMERA_LABEL[virtualCamera] }}
      description={description}
    >
      {(virtualCamera === 'ready' || virtualCamera === 'live') && ring && (
        <div className="output-card__format tabular">
          {ring.negotiated_width}×{ring.negotiated_height} · {Math.round(ring.negotiated_fps_num / Math.max(1, ring.negotiated_fps_den))} FPS
        </div>
      )}

      {virtualCamera === 'not-installed' ? (
        <Button
          variant="primary"
          block
          icon={<ShieldAlert size={15} />}
          loading={controller.isVcamRegistering}
          onClick={() => void controller.registerVirtualCamera()}
          title="Requires an administrator prompt"
        >
          Install camera driver
        </Button>
      ) : vcamState?.host_running ? (
        <Button block variant="secondary" loading={busy} onClick={() => void run(controller.stopVirtualCamera)}>
          Stop camera
        </Button>
      ) : (
        <Button
          block
          variant="primary"
          loading={busy || virtualCamera === 'starting'}
          disabled={virtualCamera === 'checking' || controller.phoneState === 'offline'}
          onClick={() => void run(controller.startVirtualCamera)}
        >
          Start camera
        </Button>
      )}

      {controller.binariesBlocked && vcamState?.binary_identity && (
        <Callout
          tone="danger"
          icon={<AlertTriangle size={14} />}
          title="Camera files are out of date"
          action={<Button size="sm" variant="ghost" onClick={onOpenDiagnostics}>Details</Button>}
        >
          {vcamState.binary_identity.error || 'The installed camera DLL does not match this build.'}
        </Callout>
      )}
      {controller.activeError && !controller.binariesBlocked && (
        <Callout
          tone="danger"
          icon={<AlertTriangle size={14} />}
          title="Camera problem"
          action={<Button size="sm" variant="ghost" onClick={onOpenDiagnostics}>Details</Button>}
        >
          {controller.activeError}
        </Callout>
      )}
    </OutputCard>
  );
}

function ObsCard({
  controller,
  obs,
  onOpenIntegrations,
}: {
  controller: CameraController;
  obs: ObsIntegration;
  onOpenIntegrations: () => void;
}) {
  const [guideOpen, setGuideOpen] = useState(false);
  const { state } = obs;
  const tone: Tone = state.phase === 'connected' ? 'ok' : state.phase === 'error' ? 'danger' : state.phase === 'disconnected' ? 'idle' : 'busy';
  const label = state.phase === 'connected' ? 'Connected'
    : state.phase === 'error' ? state.message
      : state.phase === 'disconnected' ? 'Not connected'
        : state.phase === 'working' ? 'Setting up…' : 'Connecting…';
  const cameraMode = obs.mode === 'camera';
  const needsCamera = cameraMode && controller.virtualCamera !== 'ready' && controller.virtualCamera !== 'live';

  return (
    <OutputCard
      icon={<Clapperboard size={17} />}
      title="OBS Studio"
      status={{ tone, label }}
      aside={<IconButton size="sm" label="OBS settings" icon={<Settings2 size={15} />} onClick={onOpenIntegrations} />}
      description={state.sourceReady
        ? `${state.message}. It's in the “OpenCamBridge” scene.`
        : cameraMode
          ? `Adds “${VIRTUAL_CAMERA_NAME}” to an OpenCamBridge scene in OBS automatically.`
          : 'Adds a fallback source to an OpenCamBridge scene in OBS automatically.'}
    >
      {state.phase === 'error' && state.error && (
        <Callout tone="danger" icon={<AlertTriangle size={14} />}>{state.error}</Callout>
      )}
      {needsCamera && state.phase !== 'error' && (
        <p className="output-card__note">Start {VIRTUAL_CAMERA_NAME} first so OBS can find it.</p>
      )}
      <Button
        block
        variant={state.sourceReady ? 'secondary' : 'primary'}
        loading={state.phase === 'connecting' || state.phase === 'working'}
        disabled={needsCamera}
        onClick={() => void obs.setup()}
      >
        {state.sourceReady ? 'Set up again' : 'Add to OBS'}
      </Button>

      <button type="button" className="output-card__disclosure" onClick={() => setGuideOpen(value => !value)} aria-expanded={guideOpen}>
        {guideOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />} Set up manually
      </button>
      {guideOpen && (
        <ol className="output-card__steps">
          <li>Start {VIRTUAL_CAMERA_NAME} above.</li>
          <li>In OBS, add a <b>Video Capture Device</b> source.</li>
          <li>Pick <b>{VIRTUAL_CAMERA_NAME}</b> as the device.</li>
          <li>Set Resolution/FPS Type to <b>Custom</b> and match {controller.settings.width}×{controller.settings.height} at {controller.settings.fps} FPS.</li>
        </ol>
      )}
    </OutputCard>
  );
}

/** Advanced only: every measured rate along the chain, in plain names. */
function PerformanceCard({ controller, onOpenDiagnostics }: { controller: CameraController; onOpenDiagnostics: () => void }) {
  const am = controller.androidMetrics;
  const m = controller.vcamState?.metrics;
  const preview = controller.previewDiagnostics;
  const target = Number(am?.selectedFps || controller.settings.fps || 0);
  const tone = (value: number | undefined, live: boolean): 'ok' | 'warn' | 'danger' | 'muted' => {
    if (!live || value == null) return 'muted';
    if (!(value > 0)) return 'danger';
    return target > 0 && value < target * 0.85 ? 'warn' : 'ok';
  };
  const streaming = controller.phoneState === 'streaming';
  const producing = controller.producerRunning && !!m;
  const decoder = m
    ? `${m.decoder_name || m.decode_backend || (m.source === 'mjpeg' ? 'MJPEG' : '—')}${m.hardware_decoder === false ? ' · software' : m.hardware_decoder ? ' · hardware' : m.d3d11_output ? ' · D3D11' : ''}`
    : '—';
  const colour = preview.colorMatrix ? `${preview.colorMatrix} ${preview.colorRange}` : '—';
  const bandwidth = m?.transport_bandwidth_mbps ?? m?.estimated_mbps ?? am?.estimatedMbps;
  const vcamFps = controller.consumerAttached ? (m?.virtual_camera_unique_fps ?? m?.written_fps) : undefined;

  return (
    <section className="output-card output-card--metrics">
      <header className="output-card__head">
        <span className="output-card__icon"><Activity size={17} /></span>
        <div className="output-card__heading">
          <h3 className="output-card__title">Performance</h3>
          <div className="output-card__status">Live measurements</div>
        </div>
      </header>
      <div className="output-card__metrics">
        <MetricRow label="Camera capture" value={streaming ? am?.actualFps ?? '—' : '—'} unit="fps" tone={tone(am?.actualFps, streaming)} />
        <MetricRow label="Encoded" value={streaming ? am?.encodedFps ?? '—' : '—'} unit="fps" tone={tone(am?.encodedFps, streaming)} />
        <MetricRow label="Transport" value={producing ? m?.transport_received_fps ?? m?.transport_fps ?? '—' : '—'} unit="fps" tone={tone(m?.transport_received_fps ?? m?.transport_fps, producing)} />
        <MetricRow label="Ring" value={producing ? m?.ring_written_fps ?? m?.written_fps ?? '—' : '—'} unit="fps" tone={tone(m?.ring_written_fps ?? m?.written_fps, producing)} />
        <MetricRow label="Desktop preview" value={preview.ready ? preview.previewDisplayedFps : '—'} unit="fps" tone={tone(preview.previewDisplayedFps, preview.ready)} />
        <MetricRow label="Virtual camera" value={vcamFps ?? '—'} unit="fps" tone={tone(vcamFps, controller.consumerAttached)} />
        <MetricRow label="Bandwidth" value={bandwidth ?? '—'} unit="Mb/s" />
        <MetricRow label="Producer processing" value={m ? m.producer_processing_ms ?? m.total_pipeline_ms : '—'} unit="ms" />
        <MetricRow label="Preview IPC / upload" value={preview.ready ? `${preview.ipcTransferMs.toFixed(1)} / ${preview.previewUploadMs.toFixed(1)}` : '—'} unit="ms" />
        <MetricRow label="Decoder" value={decoder} tone={m?.hardware_decoder === false ? 'warn' : undefined} />
        <MetricRow label="Colour" value={colour} />
      </div>
      <Button size="sm" variant="ghost" block icon={<Activity size={14} />} onClick={onOpenDiagnostics}>
        Open diagnostics
      </Button>
    </section>
  );
}
