import { useState, type ReactNode } from 'react';
import {
  Activity,
  AlertTriangle,
  Copy,
  Cpu,
  FileText,
  FolderOpen,
  Gauge,
  Layers,
  Monitor,
  Play,
  RefreshCw,
  Smartphone,
  Square,
  Terminal,
  Trash2,
  Wrench,
} from 'lucide-react';
import SignalChain from '../SignalChain';
import { Lamp, Notice, Tel } from '../ui';
import { Button, Modal, ModalClose } from '../primitives';
import { openLogsFolder } from '../../services/logging';
import type { CameraController } from '../../state/useCameraController';

type TabId = 'overview' | 'throughput' | 'preview' | 'phone' | 'windows' | 'events' | 'developer';

const TABS: { id: TabId; label: string; icon: ReactNode }[] = [
  { id: 'overview', label: 'Overview', icon: <Activity size={16} /> },
  { id: 'throughput', label: 'Throughput', icon: <Gauge size={16} /> },
  { id: 'preview', label: 'Desktop preview', icon: <Monitor size={16} /> },
  { id: 'phone', label: 'Phone pipeline', icon: <Smartphone size={16} /> },
  { id: 'windows', label: 'Virtual camera', icon: <Layers size={16} /> },
  { id: 'events', label: 'Event log', icon: <Terminal size={16} /> },
  { id: 'developer', label: 'Developer', icon: <Wrench size={16} /> },
];

/**
 * Every raw counter the pipeline exposes, one click away from — but never on —
 * the main screen. Tabs group them by where in the chain they are measured.
 */
export default function DiagnosticsModal({
  open,
  onClose,
  controller,
  onOpenLogs,
}: {
  open: boolean;
  onClose: () => void;
  controller: CameraController;
  onOpenLogs: () => void;
}) {
  const [tab, setTab] = useState<TabId>('overview');
  return (
    <Modal open={open} onClose={onClose} title="Diagnostics" size="xl" className="diagnostics-modal">
      <nav className="settings-nav" aria-label="Diagnostics sections">
        <div className="settings-nav__title"><Activity size={15} /> Diagnostics</div>
        {TABS.map(item => (
          <button
            key={item.id}
            type="button"
            className={`settings-nav__item${item.id === tab ? ' is-active' : ''}`}
            onClick={() => setTab(item.id)}
          >
            {item.icon}
            {item.label}
            {item.id === 'overview' && controller.diagAlert && (
              <span className={`settings-nav__dot settings-nav__dot--${controller.diagAlert}`} />
            )}
          </button>
        ))}
        <div className="settings-nav__footer">
          <Button size="sm" block icon={<Copy size={13} />} onClick={() => void controller.copyDiagnostics()}>
            Copy report
          </Button>
        </div>
      </nav>
      <div className="settings-content diagnostics">
        <ModalClose onClose={onClose} />
        <h2 className="diagnostics__title">{TABS.find(item => item.id === tab)?.label}</h2>
        {tab === 'overview' && <OverviewTab controller={controller} />}
        {tab === 'throughput' && <ThroughputTab controller={controller} />}
        {tab === 'preview' && <PreviewTab controller={controller} />}
        {tab === 'phone' && <PhoneTab controller={controller} />}
        {tab === 'windows' && <WindowsTab controller={controller} />}
        {tab === 'events' && <EventsTab controller={controller} onOpenLogs={onOpenLogs} />}
        {tab === 'developer' && <DeveloperTab controller={controller} />}
      </div>
    </Modal>
  );
}

function Block({ title, icon, children }: { title: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <section className="diag-block">
      <h3 className="diag-block__title">{icon}{title}</h3>
      {children}
    </section>
  );
}

function OverviewTab({ controller }: { controller: CameraController }) {
  const { androidMetrics, settings, vcamState } = controller;
  const metrics = vcamState?.metrics;
  const warnings: { kind: 'warn' | 'fail'; title?: string; text: string }[] = [];
  if (androidMetrics?.fallbackUsed) warnings.push({ kind: 'warn', text: `Fallback resolution in use: ${androidMetrics.resolutionPolicy}` });
  if (androidMetrics?.fallbackReason || metrics?.fallback_reason) warnings.push({ kind: 'warn', title: 'Active fallback', text: androidMetrics?.fallbackReason || metrics?.fallback_reason || '' });
  if (androidMetrics?.selectedEffectiveWidth > settings.width * 1.5) {
    warnings.push({ kind: 'fail', title: 'Source too large', text: `The profile degraded and selected ${androidMetrics.selectedRawWidth}×${androidMetrics.selectedRawHeight} instead of ${settings.width}×${settings.height}.` });
  }
  if (metrics && metrics.decoded_fps < settings.fps - 5) warnings.push({ kind: 'warn', text: `Decoded rate is below target: ${metrics.decoded_fps} of ${settings.fps} fps.` });
  if (metrics && metrics.source_width !== metrics.output_width) warnings.push({ kind: 'warn', text: `Resize in the path: ${metrics.source_width}×${metrics.source_height} → ${metrics.output_width}×${metrics.output_height}.` });
  if (controller.activeError) warnings.push({ kind: 'fail', title: 'Last error', text: controller.activeError });

  return (
    <>
      <Block title="Signal chain" icon={<Activity size={14} />}>
        <SignalChain
          targetFps={Number(androidMetrics?.selectedFps || androidMetrics?.encodedFps || settings.fps)}
          transport={controller.transport}
          androidRunning={controller.androidRunning}
          androidMetrics={androidMetrics}
          metrics={metrics}
          producerRunning={controller.producerRunning}
          consumerAttached={controller.consumerAttached}
          streamMode={androidMetrics?.activeStreamMode || settings.streamMode}
        />
      </Block>

      <Block title="Processes" icon={<Cpu size={14} />}>
        <div className="diag-processes">
          <ProcessRow on={controller.androidRunning} label="Android control server" state={controller.androidRunning ? 'running' : 'stopped'} />
          <ProcessRow on={controller.producerRunning} label="Frame producer" state={vcamState?.producer_state || (controller.producerRunning ? 'running' : 'stopped')} />
          <ProcessRow on={!!vcamState?.host_running} label="Virtual camera host" state={vcamState?.host_running ? (vcamState.host_activated ? 'activated' : 'running') : 'stopped'} />
          <div className="row">
            <Lamp state={controller.consumerAttached ? 'live' : 'idle'} />
            <span className="grow">Virtual camera consumer</span>
            <span className="micro">{controller.consumerAttached ? 'reading' : 'not attached'}</span>
          </div>
        </div>
      </Block>

      <Block title="Warnings" icon={<AlertTriangle size={14} />}>
        {warnings.length === 0 ? (
          <p className="hint">No degradation reported on the active path.</p>
        ) : (
          <div className="stack stack--8">
            {warnings.map((warning, index) => (
              <Notice key={index} kind={warning.kind} icon={<AlertTriangle size={13} />} title={warning.title}>{warning.text}</Notice>
            ))}
          </div>
        )}
      </Block>
    </>
  );
}

function ProcessRow({ on, label, state }: { on: boolean; label: string; state: string }) {
  return (
    <div className="row">
      <Lamp state={on ? 'ok' : 'down'} />
      <span className="grow">{label}</span>
      <span className="micro">{state}</span>
    </div>
  );
}

function ThroughputTab({ controller }: { controller: CameraController }) {
  const metrics = controller.vcamState?.metrics;
  const ring = metrics?.ring;
  const { settings, androidMetrics } = controller;
  if (!metrics) return <p className="hint">Waiting for producer metrics — they appear while the virtual camera or the compatibility preview runs.</p>;
  return (
    <>
      <Block title="Rates" icon={<Gauge size={14} />}>
        <div className="tel-grid">
          <Tel k="Configured source fps" v={metrics.source_fps ?? metrics.fps_target} />
          <Tel k="Transport received fps" v={metrics.transport_received_fps ?? metrics.transport_fps ?? metrics.http_jpeg_fps} />
          <Tel k="Producer decoded fps" v={metrics.producer_decoded_fps ?? metrics.decoded_unique_fps ?? metrics.decoded_fps} />
          <Tel k="Ring written fps" v={metrics.ring_written_fps ?? metrics.written_fps} />
          <Tel k="Virtual cam requested fps" v={metrics.virtual_camera_requested_fps ?? 0} />
          <Tel k="Virtual cam unique" v={metrics.virtual_camera_unique_fps ?? metrics.written_fps} />
          <Tel k="Virtual cam repeated fps" v={metrics.virtual_camera_repeated_fps ?? metrics.repeated_samples ?? 0} tone={(metrics.repeated_samples ?? 0) > 0 ? 'warn' : undefined} />
          <Tel k="Producer replaced frames" v={metrics.replaced_frames ?? 0} />
          {metrics.source === 'mjpeg' && <Tel k="JPEG dropped / queue" v={`${metrics.dropped_jpegs} / ${metrics.jpeg_queue_len}`} />}
          <Tel k="Transport bandwidth" v={`${metrics.transport_bandwidth_mbps ?? metrics.estimated_mbps} Mb/s`} />
          <Tel k="Decode time" v={`${metrics.decode_ms_avg} ms`} />
          <Tel k="Producer processing" v={`${metrics.producer_processing_ms ?? metrics.total_pipeline_ms} ms`} />
          {metrics.source === 'ocb2-h264' && <Tel k="Phone→ring estimate (lower bound)" v={`${metrics.phone_to_ring_latency_ms ?? metrics.latency_ms ?? 0} ms`} />}
        </div>
      </Block>
      <Block title="Decode & playout" icon={<Cpu size={14} />}>
        <Tel
          k="Decoder"
          v={`${metrics.decoder_name || metrics.decode_backend || 'MJPEG'} · ${
            metrics.hardware_decoder == null
              ? `hardware unknown, D3D11 ${metrics.d3d11_output ? 'active' : 'inactive'}`
              : metrics.hardware_decoder ? 'hardware' : 'software fallback'
          }`}
          tone={metrics.hardware_decoder === false ? 'warn' : undefined}
        />
        <Tel k="Stage times (decode / rotate / resize / write)" v={`${metrics.decode_ms_avg} / ${metrics.rotate_ms_avg} / ${metrics.resize_ms_avg} / ${metrics.write_ms_avg} ms`} />
        <Tel k="Pixel format" v={metrics.pixel_format} tone="muted" />
        {metrics.source_width !== metrics.output_width && (
          <Tel k="Resizing" v={`${metrics.source_width}×${metrics.source_height} → ${metrics.output_width}×${metrics.output_height}`} tone="warn" />
        )}
        {ring && (
          <>
            <Tel k="Playout buffer / target" v={`${ring.playout_buffer_depth_ms} / ${ring.playout_target_delay_ms} ms`} />
            <Tel k="Output underruns" v={ring.playout_underruns} tone={ring.playout_underruns ? 'warn' : 'ready'} />
            <Tel k="Scheduler resets / clock drift" v={`${ring.playout_scheduler_resets} / ${ring.playout_clock_ppm} ppm`} />
            <Tel k="Max output gap" v={`${ring.playout_max_output_gap_ms} ms`} />
          </>
        )}
        {controller.vcamState?.last_metrics_time && (
          <p className="hint">Last update {Math.max(0, Math.floor(controller.now - controller.vcamState.last_metrics_time))}s ago</p>
        )}
        {settings.fps >= 60 && settings.width >= 1920 && (
          <Notice kind="info" icon={<Gauge size={13} />} title="1080p60 truth metrics">
            Target 60 fps, actual {metrics.written_fps} fps —{' '}
            {metrics.written_fps >= 55 ? 'viable' : metrics.written_fps >= 45 ? 'degraded' : 'not viable'}.
            Phone capture {androidMetrics?.actualFps || 0} fps via {androidMetrics?.captureEngine || 'unknown'};
            decode {metrics.decode_ms_avg} ms; IPC write {metrics.write_ms_avg} ms.
          </Notice>
        )}
      </Block>
    </>
  );
}

function PreviewTab({ controller }: { controller: CameraController }) {
  const previewDiagnostics = controller.previewDiagnostics;
  const producerRunning = controller.producerRunning;
  const webCodecs = previewDiagnostics.renderer === 'webcodecs';
  return (
    <Block title="Desktop preview stages" icon={<Monitor size={14} />}>
      <div className="tel-grid">
        <Tel k="Preview state" v={previewDiagnostics.ready ? 'READY — frame displayed' : 'WAITING — no displayed frame'} tone={previewDiagnostics.ready ? 'ready' : 'warn'} />
        <Tel k="Preview renderer" v={webCodecs ? 'WebCodecs · full source resolution' : 'Native NV12 compatibility · max 960px'} tone={webCodecs ? undefined : 'warn'} />
        {webCodecs ? <>
          <Tel k="Decoded / presented fps" v={`${previewDiagnostics.previewReceivedFps.toFixed(1)} / ${previewDiagnostics.previewDisplayedFps.toFixed(1)}`} tone={previewDiagnostics.ready && previewDiagnostics.previewDisplayedFps > 0 ? 'ready' : 'warn'} />
          <Tel k="Rendered source size" v={`${previewDiagnostics.parsedWidth || '—'}×${previewDiagnostics.parsedHeight || '—'}`} />
          <Tel k="Compressed bytes transferred" v={previewDiagnostics.ipcPayloadBytes} />
          <Tel k="Decode callback / draw submission" v={`${previewDiagnostics.decodeMs.toFixed(2)} / ${previewDiagnostics.previewUploadMs.toFixed(2)} ms`} />
          <Tel k="Decoded / decoder queue" v={`${previewDiagnostics.queuedFrames} / ${previewDiagnostics.decoderQueue}`} />
          <Tel k="Initial presentation cushion" v={`${previewDiagnostics.bufferMs.toFixed(1)} ms`} />
          <Tel k="Presentation skips" v={previewDiagnostics.previewSkippedSequences} tone={previewDiagnostics.previewSkippedSequences > 0 ? 'warn' : undefined} />
          <Tel k="Source fps / colour" v={`${previewDiagnostics.sourceFps || '—'} / ${previewDiagnostics.colorMatrix || '—'} ${previewDiagnostics.colorRange || ''}`} />
          <Tel k="Last preview error" v={previewDiagnostics.lastError || 'none'} tone={previewDiagnostics.lastError ? 'fail' : 'muted'} />
        </> : <>
        <Tel k="Producer / ring" v={`${producerRunning ? 'running' : 'stopped'} / ${previewDiagnostics.ringAlive ? 'alive' : 'unavailable'}`} tone={producerRunning && previewDiagnostics.ringAlive ? 'ready' : 'warn'} />
        <Tel k="Ring write sequence / generation" v={`${previewDiagnostics.ringWriteSequence} / ${previewDiagnostics.streamGeneration}`} />
        <Tel k="Preview command calls" v={previewDiagnostics.previewCommandCalls} />
        <Tel k="Non-empty / empty responses" v={`${previewDiagnostics.nonEmptyResponses} / ${previewDiagnostics.emptyResponses}`} />
        <Tel k="Last returned sequence" v={previewDiagnostics.lastReturnedSequence || 'none'} />
        <Tel k="IPC payload bytes" v={previewDiagnostics.ipcPayloadBytes} />
        <Tel k="Header / parsed geometry" v={`${previewDiagnostics.frameHeaderValid ? 'valid' : 'waiting'} / ${previewDiagnostics.parsedWidth || '—'}×${previewDiagnostics.parsedHeight || '—'}`} tone={previewDiagnostics.frameHeaderValid ? 'ready' : 'warn'} />
        <Tel k="Renderer uploads / displays" v={`${previewDiagnostics.rendererUploadCount} / ${previewDiagnostics.rendererDisplayCount}`} tone={previewDiagnostics.rendererDisplayCount > 0 ? 'ready' : 'warn'} />
        <Tel k="Last displayed sequence" v={previewDiagnostics.lastDisplayedSequence || 'none'} />
        <Tel k="Preview received / displayed fps" v={`${previewDiagnostics.previewReceivedFps} / ${previewDiagnostics.previewDisplayedFps}`} tone={previewDiagnostics.ready && previewDiagnostics.previewDisplayedFps > 0 ? 'ready' : 'warn'} />
        <Tel k="Preview skipped sequences" v={previewDiagnostics.previewSkippedSequences} tone={previewDiagnostics.previewSkippedSequences > 0 ? 'warn' : undefined} />
        <Tel k="IPC transfer / WebGL upload" v={`${previewDiagnostics.ipcTransferMs.toFixed(2)} / ${previewDiagnostics.previewUploadMs.toFixed(2)} ms`} />
        <Tel k="Source fps / colour" v={`${previewDiagnostics.sourceFps || '—'} / ${previewDiagnostics.colorMatrix || '—'} ${previewDiagnostics.colorRange || ''}`} />
        <Tel k="Primaries / transfer" v={`${previewDiagnostics.colorPrimaries || '—'} / ${previewDiagnostics.colorTransfer || '—'}`} />
        <Tel k="Torn slots rejected" v={previewDiagnostics.tornSlotsRejected} tone={previewDiagnostics.tornSlotsRejected > 0 ? 'warn' : undefined} />
        <Tel k="Last preview error" v={previewDiagnostics.lastError || 'none'} tone={previewDiagnostics.lastError ? 'fail' : 'muted'} />
        </>}
      </div>
      {!webCodecs && previewDiagnostics.consumerStalled && (
        <Notice kind="fail" icon={<AlertTriangle size={13} />}>
          Preview consumer is not releasing frames; producer ring is healthy.
        </Notice>
      )}
    </Block>
  );
}

function PhoneTab({ controller }: { controller: CameraController }) {
  const { androidMetrics, settings, activeCam } = controller;
  const dash = (value: any, suffix = '') => (value === null || value === undefined || value === '' ? '—' : `${value}${suffix}`);
  return (
    <>
      <Block title="Capture & encode" icon={<Smartphone size={14} />}>
        {androidMetrics ? (
          <>
            <Tel k="Aspect requested / selected" v={`${androidMetrics.requestedAspectRatio} / ${androidMetrics.selectedAspectRatio}`}
              tone={androidMetrics.aspectRatioMatch ? undefined : 'warn'} />
            <Tel k="Resolution desired / selected / actual"
              v={`${settings.width}×${settings.height} / ${androidMetrics.selectedEffectiveWidth || 0}×${androidMetrics.selectedEffectiveHeight || 0} / ${androidMetrics.encodedWidth}×${androidMetrics.encodedHeight}`} />
            <Tel k="Rate desired / selected / encoded"
              v={`${settings.fps} / ${androidMetrics.selectedFps || 0} / ${androidMetrics.encodedFps || 0}`}
              tone={(androidMetrics.encodedFps || 0) >= settings.fps - 5 ? 'ready' : 'warn'} />
            <Tel k="Mode desired / active" v={`${settings.streamMode.toUpperCase()} / ${(androidMetrics.activeStreamMode || settings.streamMode).toUpperCase()}`} />
            <Tel k="Rotation resize" v={androidMetrics.resizeNeeded ? 'required' : 'native match'} tone={androidMetrics.resizeNeeded ? 'warn' : 'ready'} />
            {androidMetrics.capture && (
              <Tel k="Capture engine / session fps"
                v={`${androidMetrics.captureEngine || 'unknown'} / ${androidMetrics.cameraSessionFps || 0}${androidMetrics.gpuBridgeFps != null ? ` → GPU ${androidMetrics.gpuBridgeFps}` : ''}`} />
            )}
            <Tel
              k="Phone preview"
              v={!androidMetrics.phonePreviewRequested ? 'not requested'
                : androidMetrics.phonePreviewActive ? 'active'
                : `inactive: ${androidMetrics.phonePreviewFailureReason || 'waiting for target'}`}
              tone={androidMetrics.phonePreviewRequested && !androidMetrics.phonePreviewActive ? 'warn' : 'ready'} />
            {androidMetrics.h264 && (
              <>
                <Tel k="Encoder" v={`${androidMetrics.encoderName} · ${androidMetrics.hardwareEncoder ? 'hardware' : 'software fallback'}`}
                  tone={androidMetrics.hardwareEncoder ? undefined : 'warn'} />
                <Tel k="Encoded fps / bitrate" v={`${androidMetrics.encodedFps || 0} / ${((androidMetrics.encodedBitrate || 0) / 1_000_000).toFixed(2)} Mb/s`} />
                {androidMetrics.h264?.rejectedCapturePaths && <Tel k="Rejected capture paths" v={androidMetrics.h264.rejectedCapturePaths} tone="warn" />}
              </>
            )}
            {androidMetrics.mjpeg && (
              <>
                <Tel k="MJPEG phone processing" v={`${Number(androidMetrics.androidEncodeMsAvg || 0).toFixed(1)} ms`} />
                <Tel k="MJPEG measured capacity" v={`${androidMetrics.mjpegProcessingCapacityFps || 0} fps at Q${settings.jpegQuality}`}
                  tone={(androidMetrics.mjpegProcessingCapacityFps || 0) >= settings.fps ? 'ready' : 'warn'} />
              </>
            )}
          </>
        ) : (
          <p className="hint">Waiting for phone metrics…</p>
        )}
      </Block>
      <Block title="Selected lens" icon={<Cpu size={14} />}>
        <Tel k="Lens" v={activeCam ? `${activeCam.label} (${activeCam.id}, ${activeCam.facing}${activeCam.lensType ? `, ${activeCam.lensType}` : ''})` : settings.cameraId} />
        <Tel k="Torch / zoom range" v={`${activeCam?.hasTorch ? 'yes' : 'no'} / ${activeCam?.zoomRatioMin ?? 1}–${activeCam?.zoomRatioMax ?? 1}×`} />
        {activeCam?.supportsHighSpeed && (
          <Tel k="Constrained high-speed" v={Array.isArray(activeCam.highSpeedFpsRanges) && activeCam.highSpeedFpsRanges.length > 0
            ? `up to ${Math.max(...activeCam.highSpeedFpsRanges.map(r => r.max))} fps` : 'advertised'} />
        )}
        <Tel k="Source generation" v={dash(androidMetrics?.generation)} tone="muted" />
        <Tel k="Lifecycle" v={dash(androidMetrics?.lifecycleState)} tone="muted" />
        <Tel k="Phone capture policy" v={settings.profile} tone="muted" />
      </Block>
    </>
  );
}

function WindowsTab({ controller }: { controller: CameraController }) {
  const { vcamState } = controller;
  const metrics = vcamState?.metrics;
  const ring = metrics?.ring;
  return (
    <>
      <Block title="Producer process" icon={<Cpu size={14} />}>
        <Tel k="Producer PID" v={vcamState?.producer_pid || 'none'} />
        <Tel k="Producer binary present" v={vcamState?.producer_exists ? 'yes' : 'no'} tone={vcamState?.producer_exists ? 'ready' : 'fail'} />
        <Tel
          k="Executable"
          v={vcamState?.producer_path ? vcamState.producer_path.split('\\').pop() : 'unknown'}
          tone="link"
          title={vcamState?.producer_path ? `${vcamState.producer_path} — click to copy` : 'unknown'}
          onClick={() => vcamState?.producer_path && navigator.clipboard.writeText(vcamState.producer_path)}
        />
        <Tel k="Registered / host running / activated" v={`${vcamState?.registered ? 'yes' : 'no'} / ${vcamState?.host_running ? 'yes' : 'no'} / ${vcamState?.host_activated ? 'yes' : 'no'}`} />
        <Tel k="Last error" v={vcamState?.last_error || 'none'} tone={vcamState?.last_error ? 'fail' : 'muted'} />
        {vcamState?.last_event && <Tel k="Last producer event" v={vcamState.last_event} tone="muted" />}
      </Block>

      {controller.binariesBlocked && vcamState?.binary_identity && (
        <Notice kind="fail" icon={<AlertTriangle size={14} />} title="Stale or mismatched camera binaries">
          {vcamState.binary_identity.error}
          <code>{vcamState.binary_identity.remediation}</code>
        </Notice>
      )}

      {ring ? (
        <Block title="Ring & binaries" icon={<Layers size={14} />}>
          <Tel k="Commits / reads / requests" v={`${metrics?.ring_frames_committed ?? 0} / ${ring.ring_read_successes} / ${ring.sample_requests}`} />
          <Tel
            k="Validation / copy failures"
            v={`${ring.ring_validation_failures} / ${ring.sample_copy_failures} (0x${(ring.last_ring_error >>> 0).toString(16)})`}
            tone={ring.ring_validation_failures || ring.sample_copy_failures ? 'fail' : 'ready'}
          />
          <Tel k="Negotiated media type" v={`${ring.negotiated_width}×${ring.negotiated_height} @ ${ring.negotiated_fps_num}/${ring.negotiated_fps_den}`} />
          <Tel
            k="Resize backend / source fps"
            v={`${ring.resize_backend || 'unknown'} (${ring.resize_failures} GPU failures) / ${ring.source_fps_num}/${ring.source_fps_den}`}
            tone={ring.resize_backend === 'cpu-fallback' ? 'warn' : 'ready'}
          />
          <Tel k="Write sequence / generation / overwritten" v={`${ring.ring_write_sequence} / ${ring.stream_generation} / ${ring.ring_frames_overwritten}`} />
          <Tel
            k="Producer / DLL hash"
            v={`${ring.producer_build_hash.slice(0, 12) || 'unknown'} / ${ring.installed_dll_build_hash.slice(0, 12) || 'unknown'}`}
            title={`${ring.producer_build_hash} / ${ring.installed_dll_build_hash}`}
            tone="muted"
          />
          {vcamState?.binary_identity && (
            <Tel
              k="Built / installed / registered / loaded"
              v={`${vcamState.binary_identity.built_dll_hash.slice(0, 8) || 'n/a'} / ${vcamState.binary_identity.installed_dll_hash.slice(0, 8) || 'n/a'} / ${vcamState.binary_identity.registered_dll_hash.slice(0, 8) || 'n/a'} / ${vcamState.binary_identity.loaded_dll_current ? (vcamState.binary_identity.loaded_dll_hash.slice(0, 8) || 'n/a') : 'not active'}`}
              title={`${vcamState.binary_identity.built_dll_hash} / ${vcamState.binary_identity.installed_dll_hash} / ${vcamState.binary_identity.registered_dll_hash} / ${vcamState.binary_identity.loaded_dll_hash}`}
              tone={vcamState.binary_identity.ready ? 'ready' : 'fail'}
            />
          )}
          <Tel k="Ring ABI hash" v={`0x${(ring.ring_abi_hash >>> 0).toString(16)}`} tone="muted" />
          <Tel k="Consumer PID / heartbeat" v={`${ring.consumer_pid || '—'} / ${ring.consumer_heartbeat_qpc || '—'}`} tone="muted" />
          <p className="hint">
            A mismatch between built, installed, registered and loaded hashes means Windows is running a different DLL than the
            one just built. Re-run <strong>dev-build-vcam.ps1</strong>.
          </p>
        </Block>
      ) : (
        <p className="hint">Ring diagnostics appear once the producer is writing frames.</p>
      )}
    </>
  );
}

function EventsTab({ controller, onOpenLogs }: { controller: CameraController; onOpenLogs: () => void }) {
  return (
    <Block title="Session events" icon={<Terminal size={14} />}>
      <div className="settings-actions settings-actions--wrap">
        <Button size="sm" icon={<Copy size={13} />} onClick={() => void controller.copyDiagnostics()}>Copy report</Button>
        <Button size="sm" icon={<Trash2 size={13} />} onClick={controller.clearDiagLog}>Clear</Button>
        <Button size="sm" icon={<FileText size={13} />} onClick={onOpenLogs}>Session log file</Button>
        <Button size="sm" icon={<FolderOpen size={13} />} onClick={() => void openLogsFolder()}>Logs folder</Button>
      </div>
      <div className="log log--tall">
        {controller.diagLog.length === 0
          ? <span className="log__empty">No events yet.</span>
          : controller.diagLog.slice().reverse().map((line, index) => <div className="log__line" key={index}>{line}</div>)}
      </div>
    </Block>
  );
}

function DeveloperTab({ controller }: { controller: CameraController }) {
  const { vcamState } = controller;
  return (
    <>
      <Notice kind="warn" icon={<AlertTriangle size={14} />} title="Granular pipeline controls">
        These drive single layers of the pipeline for debugging. Normal use goes through the Virtual camera card in Outputs.
      </Notice>
      <Block title="Phone feed only" icon={<Smartphone size={14} />}>
        <p className="hint">Phone stream plus the decoded producer, without publishing the virtual camera.</p>
        <div className="settings-actions">
          <Button size="sm" icon={<Play size={12} />} onClick={() => void controller.startFeedOnly()} disabled={vcamState?.process_running}>Start</Button>
          <Button size="sm" icon={<Square size={12} />} onClick={() => void controller.stopFeedOnly()} disabled={!vcamState?.process_running}>Stop</Button>
        </div>
      </Block>
      <Block title="Virtual camera host" icon={<Layers size={14} />}>
        <div className="settings-actions">
          <Button size="sm" icon={<Play size={12} />} onClick={() => void controller.startHostOnly()} disabled={vcamState?.host_running}>Start</Button>
          <Button size="sm" icon={<Square size={12} />} onClick={() => void controller.stopHostOnly()} disabled={!vcamState?.host_running}>Stop</Button>
        </div>
      </Block>
      <Block title="Preview" icon={<Monitor size={14} />}>
        <div className="settings-actions">
          <Button size="sm" icon={<RefreshCw size={12} />} onClick={() => window.dispatchEvent(new CustomEvent('reload-preview'))}>Reload preview</Button>
        </div>
      </Block>
      <Block title="Registration" icon={<Wrench size={14} />}>
        <div className="settings-actions">
          <Button
            size="sm"
            variant="danger"
            onClick={() => void controller.unregisterVirtualCamera()}
            disabled={controller.isVcamRegistering || !!vcamState?.process_running || !!vcamState?.host_running}
          >
            Remove virtual camera registration
          </Button>
        </div>
      </Block>
    </>
  );
}
