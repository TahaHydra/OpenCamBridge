import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Play, Square, Settings2, Sliders, RefreshCw, RotateCw, ZoomIn, ZoomOut, Monitor, Video, ShieldAlert,
  Activity, Aperture, Gauge, Radio, Copy, Trash2, AlertTriangle, Cable, Layers, Cpu, Terminal, Flashlight,
} from 'lucide-react';
import SignalChain from './SignalChain';
import { Lamp, Notice, Section, Tel, ToggleRow, Well } from './ui';
import { connectAndSetupObs, ObsStatus } from '../services/obs';
import { apiFetch, buildUrl } from '../services/api';
import { desktopInvoke as invoke } from '../services/desktopBridge';
import { logEvent, logError, logTestMarker } from '../services/logging';
import {
  EMPTY_PREVIEW_DIAGNOSTICS,
  PREVIEW_DIAGNOSTICS_EVENT,
  type PreviewStageDiagnostics,
} from '../services/previewDiagnostics';
import {
  buildProducerLaunchSpec,
  buildSettingsMutation,
  describeMutationRejection,
  selectPipelineRestartScope,
  shouldStartH264PreviewProducer,
  shouldImportAuthoritativeState
} from '../services/pipelineSyncPolicy.js';

interface VirtualCamMetrics {
  type?: string;
  producer_state?: string;
  ring_frames_committed?: number;
  source: string;
  profile: string;
  source_width: number;
  source_height: number;
  output_width: number;
  output_height: number;
  fps_target: number;
  source_fps?: number;
  http_jpeg_fps: number;
  decoded_fps: number;
  written_fps: number;
  transport_fps?: number;
  transport_received_fps?: number;
  decoded_unique_fps?: number;
  producer_decoded_fps?: number;
  ring_written_fps?: number;
  virtual_camera_unique_fps?: number;
  repeated_samples?: number;
  virtual_camera_requested_fps?: number;
  virtual_camera_repeated_fps?: number;
  dropped_jpegs: number;
  replaced_frames?: number;
  jpeg_queue_len: number;
  decode_ms_avg: number;
  rotate_ms_avg: number;
  resize_ms_avg: number;
  write_ms_avg: number;
  total_pipeline_ms: number;
  producer_processing_ms?: number;
  latency_ms?: number;
  phone_to_ring_latency_ms?: number;
  bytes_per_sec: number;
  estimated_mbps: string;
  transport_bandwidth_mbps?: string;
  pixel_format: string;
  decode_backend?: string;
  resize_backend?: string;
  decoder_name?: string;
  d3d11_output?: boolean;
  hardware_decoder?: boolean | null;
  encoder_name?: string;
  hardware_encoder?: boolean;
  camera_id?: string;
  fallback_reason?: string;
  rotation?: number;
  last_error: string | null;
  virtual_camera_ready?: boolean;
  ring?: RingDiagnostics;
}

interface RingDiagnostics {
  consumer_attached: boolean;
  consumer_pid: number;
  consumer_heartbeat_qpc: number;
  sample_requests: number;
  ring_read_attempts: number;
  ring_read_successes: number;
  ring_validation_failures: number;
  sample_copy_failures: number;
  last_ring_error: number;
  last_accepted_sequence: number;
  negotiated_subtype: number;
  negotiated_width: number;
  negotiated_height: number;
  negotiated_fps_num: number;
  negotiated_fps_den: number;
  source_fps_num: number;
  source_fps_den: number;
  resize_backend: string;
  resize_failures: number;
  installed_dll_build_hash: string;
  producer_build_hash: string;
  ring_abi_hash: number;
  ring_write_sequence: number;
  stream_generation: number;
  ring_frames_overwritten: number;
  playout_buffer_depth_ms: number;
  playout_target_delay_ms: number;
  playout_late_dropped: number;
  playout_underruns: number;
  playout_scheduler_resets: number;
  playout_clock_ppm: number;
  playout_max_output_gap_ms: number;
}

interface VirtualCamState {
  running: boolean;
  process_running?: boolean;
  pipeline_ready?: boolean;
  producer_ready?: boolean;
  virtual_camera_ready?: boolean;
  producer_state?: string;
  host_running: boolean;
  host_activated: boolean;
  registered: boolean;
  metrics: VirtualCamMetrics | null;
  producer_path?: string;
  producer_exists?: boolean;
  producer_pid?: number;
  last_error?: string;
  last_metrics_time?: number;
  last_event?: string;
  binary_identity: {
    ready: boolean;
    producer_path: string;
    producer_file_hash: string;
    producer_runtime_hash: string;
    built_dll_path: string;
    built_dll_hash: string;
    installed_dll_path: string;
    installed_dll_hash: string;
    registered_dll_path: string;
    registered_dll_hash: string;
    loaded_dll_hash: string;
    loaded_dll_current: boolean;
    error?: string;
    remediation: string;
  };
}

interface ControlPanelProps {
  baseUrl: string;
  token?: string;
  fitMode: string;
  setFitMode: (mode: string) => void;
  onEnterObsMode?: () => void;
  previewOff: boolean;
  setPreviewOff: (val: boolean) => void;
}

type ProducerPurpose = 'preview' | 'feed' | 'webcam';

function normalizeAndroidMetrics(raw: any): any {
  if (!raw || !('capture' in raw)) return raw;
  const capture = raw.capture;
  const h264 = raw.h264;
  const mjpeg = raw.mjpeg;
  const selection = raw.selection || {};
  const fallback = raw.fallback;
  return {
    ...raw,
    ...selection,
    fps: capture?.actualFps ?? 0,
    requestedFps: capture?.requestedFps ?? 0,
    actualFps: capture?.actualFps ?? 0,
    captureFps: capture?.actualFps ?? 0,
    cameraCaptureFps: capture?.actualFps ?? 0,
    selectedFps: capture?.selectedFps ?? 0,
    cameraSessionFps: capture?.cameraSessionFps ?? 0,
    gpuBridgeFps: capture?.gpuBridgeFps,
    captureEngine: capture?.engine,
    encodedWidth: capture?.actualWidth ?? 0,
    encodedHeight: capture?.actualHeight ?? 0,
    encodedFps: h264?.encodedFps ?? mjpeg?.encodedFps ?? 0,
    phoneEncodedFps: h264?.encodedFps ?? mjpeg?.encodedFps ?? 0,
    encodedBitrate: h264?.bitrate ?? 0,
    encoderName: h264?.encoderName,
    hardwareEncoder: h264?.hardwareEncoder ?? false,
    androidEncodeMsAvg: mjpeg?.encodeMs,
    phoneEncodeMs: mjpeg?.encodeMs ?? 0,
    yuvMsAvg: mjpeg?.yuvMs,
    jpegMsAvg: mjpeg?.jpegMs,
    rotateMsAvg: mjpeg?.rotateMs,
    mjpegProcessingCapacityFps: mjpeg?.processingCapacityFps ?? 0,
    latestFrameRevision: mjpeg?.latestFrameRevision ?? 0,
    estimatedMbps: raw.transport?.estimatedMbps ?? '0.0',
    targetBandwidthMbps: raw.transport?.targetBandwidthMbps ?? 0,
    fallbackUsed: fallback?.active ?? false,
    fallbackReason: fallback?.reason ?? '',
  };
}

export default function ControlPanel({ baseUrl, token, fitMode, onEnterObsMode, previewOff, setPreviewOff }: ControlPanelProps) {
  const [cameras, setCameras] = useState<any[]>([]);
  const [settings, setSettings] = useState({
    cameraId: '0',
    profile: 'adaptive',
    width: 1920,
    height: 1080,
    outputWidth: 1920,
    outputHeight: 1080,
    // Default to 30 fps: on many phones (e.g. OnePlus 9) 60 fps at 1080p/720p is
    // only reachable via a constrained high-speed session that crops the sensor
    // FOV (looks like a "lens switch") and AE-limits to ~15 fps in low light.
    // 30 fps uses the full-FOV regular session; 60 remains an explicit preset.
    fps: 30,
    jpegQuality: 75,
    displayRotation: '0',
    aspectRatio: '16:9',
    mirror: false,
    torchEnabled: false,
    linearZoom: 0.0,
    streamMode: 'h264',
    targetBandwidthMbps: 0,
    h264Bitrate: 4000000,
    h264KeyframeInterval: 5
  });

  const PROFILE_PRESETS: Record<string, any> = {
    'low-latency': {
      profile: 'low-latency',
      width: 1280,
      height: 720,
      outputWidth: 1280,
      outputHeight: 720,
      fps: 30,
      jpegQuality: 70,
      aspectRatio: '16:9'
    },
    balanced: {
      profile: 'balanced',
      width: 1280,
      height: 720,
      outputWidth: 1280,
      outputHeight: 720,
      fps: 30,
      jpegQuality: 85,
      aspectRatio: '16:9'
    },
    'balanced-720p60': {
      profile: 'balanced-720p60',
      width: 1280,
      height: 720,
      outputWidth: 1280,
      outputHeight: 720,
      fps: 60,
      jpegQuality: 80,
      aspectRatio: '16:9'
    },
    quality: {
      profile: 'quality',
      width: 1920,
      height: 1080,
      outputWidth: 1920,
      outputHeight: 1080,
      fps: 30,
      jpegQuality: 75,
      aspectRatio: '16:9'
    },
    'experimental-1080p60': {
      profile: 'experimental-1080p60',
      width: 1920,
      height: 1080,
      outputWidth: 1920,
      outputHeight: 1080,
      fps: 60,
      jpegQuality: 85,
      aspectRatio: '16:9'
    }
  };

  const settingsRef = useRef(settings);
  const authoritativeRevisionRef = useRef<number | null>(null);
  const settingsHydratedRef = useRef(false);
  const producerPurposeRef = useRef<ProducerPurpose | null>(null);
  const previewProducerStartInFlightRef = useRef(false);
  const previewProducerRetryAfterRef = useRef(0);
  const previewAutoStartSuppressedRef = useRef(false);

  useEffect(() => {
    settingsRef.current = settings;
  }, [settings]);

  const [isSyncing, setIsSyncing] = useState(false);
  // Mirror of isSyncing readable from the status-poll callback without adding it
  // as a dependency. While a local apply is in flight we must not let a stale
  // poll overwrite the user's just-made selection with the pre-change server
  // value (which would make the desktop controls appear to "snap back").
  const isSyncingRef = useRef(false);

  const [obsPassword, setObsPassword] = useState('');
  const [obsMode, setObsMode] = useState<'browser' | 'window'>('browser');
  const [obsStatus, setObsStatus] = useState<ObsStatus | null>(null);
  const [isObsConnecting, setIsObsConnecting] = useState(false);

  const [vcamState, setVcamState] = useState<VirtualCamState | null>(null);
  const [isVcamRegistering, setIsVcamRegistering] = useState(false);
  const [vcamMessage, setVcamMessage] = useState('');
  const [androidStreamStatus, setAndroidStreamStatus] = useState('unknown');
  const [androidMetrics, setAndroidMetrics] = useState<any>(null);
  const [phoneInfo, setPhoneInfo] = useState<any>(null);
  const [now, setNow] = useState(Date.now() / 1000);

  // The rail is paged rather than one endless scroll: SIGNAL answers "is it
  // working", the rest are the things a user changes, and DIAG keeps every raw
  // counter reachable without making it the first thing anyone reads.
  const [tab, setTab] = useState<'signal' | 'image' | 'output' | 'diag'>('signal');

  // Developer mode only controls verbose diagnostics and presets. Codec choice
  // is a normal product setting because hardware H.264 is the V2 primary path.
  const [devMode, setDevMode] = useState<boolean>(() => localStorage.getItem('ocb.devMode') === '1');
  useEffect(() => { localStorage.setItem('ocb.devMode', devMode ? '1' : '0'); }, [devMode]);

  // Rolling diagnostics log surfaced in-app so runtime problems can be copied
  // without digging through the terminal. Capped to the most recent entries.
  const [diagLog, setDiagLog] = useState<string[]>([]);
  const [previewDiagnostics, setPreviewDiagnostics] = useState<PreviewStageDiagnostics>(
    () => ({ ...EMPTY_PREVIEW_DIAGNOSTICS }),
  );
  const previewDiagnosticsRef = useRef<PreviewStageDiagnostics>({ ...EMPTY_PREVIEW_DIAGNOSTICS });
  const lastDiagRef = useRef<Record<string, string>>({});
  // Refs so the periodic metrics sampler can read current state without being a
  // dependency (avoids re-creating the interval every poll).
  const vcamStateRef = useRef<VirtualCamState | null>(null);
  const androidMetricsRef = useRef<any>(null);
  const addDiag = useCallback((key: string, message: string) => {
    // De-duplicate consecutive identical messages per key so a repeating error
    // does not flood the log every poll.
    if (lastDiagRef.current[key] === message) return;
    lastDiagRef.current[key] = message;
    const ts = new Date().toLocaleTimeString();
    setDiagLog(prev => [...prev.slice(-199), `[${ts}] ${message}`]);
    // Mirror to the persistent session log file. Errors are tagged so they
    // stand out; everything else is an INFO event.
    const isErr = /fail|error|unreachable|below target/i.test(message);
    (isErr ? logError : logEvent)(key, message);
  }, []);

  useEffect(() => {
    const onPreviewDiagnostics = (event: Event) => {
      const next = (event as CustomEvent<PreviewStageDiagnostics>).detail;
      if (!next) return;
      const previous = previewDiagnosticsRef.current;
      previewDiagnosticsRef.current = next;
      setPreviewDiagnostics(next);
      if (!previous.ready && next.ready) {
        addDiag('h264Preview', `Desktop preview READY: renderer displayed sequence ${next.lastDisplayedSequence}`);
      }
      if (!previous.consumerStalled && next.consumerStalled) {
        addDiag('h264Preview', 'Preview consumer is not releasing frames; producer ring is healthy.');
      }
      if (previous.lastError !== next.lastError && next.lastError) {
        addDiag('h264Preview', `Preview error: ${next.lastError}`);
      }
    };
    window.addEventListener(PREVIEW_DIAGNOSTICS_EVENT, onPreviewDiagnostics);
    return () => window.removeEventListener(PREVIEW_DIAGNOSTICS_EVENT, onPreviewDiagnostics);
  }, [addDiag]);

  // Capabilities of the currently selected lens, reported honestly by Android.
  const activeCam: any = cameras.find(c => c.id === settings.cameraId);
  // Torch: hide only when the active lens explicitly reports no flash. If the
  // field is absent (older phone build / capability unknown) keep it visible so
  // version skew never hides a working torch.
  const torchSupported = activeCam ? activeCam.hasTorch !== false : false;
  const h264Modes: any[] = Array.isArray(activeCam?.h264Modes) ? activeCam.h264Modes : [];
  const mjpegModes: any[] = Array.isArray(activeCam?.mjpegModes) ? activeCam.mjpegModes : [];
  const activeModes = settings.streamMode === 'h264' ? h264Modes : mjpegModes;
  const fpsCapFor = (w: number, h: number): number => {
    if (settings.streamMode === 'h264') {
      return Math.max(0, ...h264Modes.filter((m: any) => m.width === w && m.height === h).map((m: any) => m.fps));
    }
    return Math.max(0, ...mjpegModes.filter((m: any) => m.width === w && m.height === h).map((m: any) => m.fps));
  };
  const maxFpsHere = fpsCapFor(settings.width, settings.height);
  const fpsChoices = Array.from(new Set(activeModes
    .filter((m: any) => m.width === settings.width && m.height === settings.height)
    .map((m: any) => Number(m.fps)))).sort((a, b) => a - b);
  const resolutionChoices = Array.from(new Map(activeModes.map((m: any) =>
    [`${m.width}x${m.height}`, { width: m.width, height: m.height }])).values()) as any[];

  const handleStartObs = async () => {
    if (obsMode === 'window' && onEnterObsMode) {
      onEnterObsMode();
      setIsObsConnecting(true);
      setObsStatus({ connected: false, message: 'Waiting for Clean Feed transition...' });
      await new Promise(resolve => setTimeout(resolve, 500));
    } else {
      setIsObsConnecting(true);
      setObsStatus(null);
    }

    // The phone already rotates the /stream.mjpeg frames, so the /obs page must
    // not rotate again.
    const obsUrl = buildUrl(baseUrl, '/obs', token, {
      fit: fitMode === 'fill' ? 'cover' : 'contain',
    });

    const success = await connectAndSetupObs(obsPassword, obsUrl, obsMode, (status) => setObsStatus(status));
    setIsObsConnecting(false);
    if (obsMode === 'browser' && success && onEnterObsMode) {
      onEnterObsMode();
    }
  };

  const importAuthoritativeState = useCallback((raw: any, force = false) => {
    const status = raw?.status || raw;
    if (!status) return;
    if (!force && !shouldImportAuthoritativeState(
      authoritativeRevisionRef.current, status.revision, isSyncingRef.current
    )) return;
    authoritativeRevisionRef.current = Number(status.revision ?? 0);
    settingsHydratedRef.current = true;
    const current = settingsRef.current;
    const next: any = {
      ...current,
      cameraId: status.cameraId ?? current.cameraId,
      profile: status.profile ?? current.profile,
      width: status.width ?? current.width,
      height: status.height ?? current.height,
      outputWidth: status.outputWidth ?? current.outputWidth,
      outputHeight: status.outputHeight ?? current.outputHeight,
      fps: status.fps ?? current.fps,
      jpegQuality: status.jpegQuality ?? current.jpegQuality,
      streamMode: status.streamMode ?? current.streamMode,
      displayRotation: status.displayRotation ?? current.displayRotation,
      aspectRatio: status.aspectRatio ?? current.aspectRatio,
      mirror: status.mirror ?? current.mirror,
      torchEnabled: status.torchEnabled ?? current.torchEnabled,
      linearZoom: status.linearZoom ?? current.linearZoom,
      targetBandwidthMbps: status.targetBandwidthMbps ?? current.targetBandwidthMbps,
      h264Bitrate: status.h264Bitrate ?? current.h264Bitrate,
      // v2 fixes this at one second. Normalize legacy phone state here so an
      // old persisted value cannot poison unrelated desktop controls.
      h264KeyframeInterval: 5
    };
    settingsRef.current = next;
    setSettings(next);
  }, []);

  const fetchStatus = useCallback(() => {
    apiFetch(baseUrl, '/api/camera/status', token)
      .then(res => res.json())
      .then(data => {
        const status = data.status || data;
        if (status) {
          importAuthoritativeState(status);
        }
      })
      .catch(console.error);
  }, [baseUrl, token, importAuthoritativeState]);

  useEffect(() => {
    const events = new EventSource(buildUrl(baseUrl, '/api/state/events', token));
    const onState = () => fetchStatus();
    events.addEventListener('state', onState);
    events.onerror = () => {
      // The existing one-second poll remains a reconnect/version-skew fallback.
    };
    return () => {
      events.removeEventListener('state', onState);
      events.close();
    };
  }, [baseUrl, token, fetchStatus]);

  useEffect(() => {
    apiFetch(baseUrl, '/api/device/info', token)
      .then(res => res.json())
      .then(setPhoneInfo)
      .catch(() => setPhoneInfo(null));

    apiFetch(baseUrl, '/api/pipeline/capabilities', token)
      .then(res => res.json())
      .then(data => {
        const list = Array.isArray(data) ? data : data.cameras || [];
        setCameras(list);
        // Prefer main/back-wide as the default lens (not telephoto/ultrawide),
        // but only when the current selection is not a real camera yet — never
        // override an explicit phone/user choice.
        const haveActive = list.some((c: any) => c.id === settingsRef.current.cameraId);
        if (!haveActive && list.length) {
          const preferred =
            list.find((c: any) => c.facing === 'back' && c.lensType === 'wide') ||
            list.find((c: any) => c.facing === 'back') ||
            list[0];
          if (preferred) {
            setSettings(prev => ({ ...prev, cameraId: preferred.id }));
            addDiag('lens', `Default lens: ${preferred.label} (${preferred.id})`);
          }
        }
      })
      .catch(e => addDiag('cameraList', `Camera list fetch failed: ${e}`));

    fetchStatus();

    const interval = setInterval(() => {
      setNow(Date.now() / 1000);
      // Pull phone-side setting changes into the desktop every tick. Without
      // this, /api/camera/status was only read on mount and after the desktop's
      // own edits, so changing quality/fps/rotation/torch/etc. on the PHONE
      // never propagated to the desktop UI. fetchStatus() is guarded by
      // isSyncingRef so it won't clobber an in-flight desktop change.
      fetchStatus();

      invoke<VirtualCamState>('get_virtual_camera_status')
        .then(setVcamState)
        .catch(console.error);

      apiFetch(baseUrl, '/health', token)
        .then(res => {
          if (res.ok) {
            setAndroidStreamStatus('running');
          } else {
            setAndroidStreamStatus('error');
          }
        })
        .catch(() => setAndroidStreamStatus('error'));

      apiFetch(baseUrl, '/api/stream/metrics', token)
        .then(res => res.json())
        .then(data => setAndroidMetrics(normalizeAndroidMetrics(data)))
        .catch(() => setAndroidMetrics(null));

    }, 1000);

    return () => clearInterval(interval);
  }, [baseUrl, token, fetchStatus]);

  // --- Diagnostics capture (reactive, so log entries are deduped per source) ---
  const fpsLowSinceRef = useRef(0);
  useEffect(() => {
    if (vcamState?.last_error) addDiag('producerErr', `Producer: ${vcamState.last_error}`);
    const m = vcamState?.metrics;
    if (m?.last_error) addDiag('decodeErr', `Producer decode: ${m.last_error}`);
    if (m && m.fps_target > 0) {
      const out = m.written_fps || 0;
      const ratio = out / m.fps_target;
      // Small FPS drift is normal (camera AE/lighting, phone scheduling) and is
      // NOT an error. Only flag it — as an informational note, never ERROR —
      // when it stays well below target (<70%) for 5s+. Wording deliberately
      // avoids "error/fail/below target" so it logs as INFO, not ERROR.
      if (ratio < 0.7) {
        if (fpsLowSinceRef.current === 0) fpsLowSinceRef.current = Date.now();
        if (Date.now() - fpsLowSinceRef.current > 5000) {
          const androidFps = androidMetricsRef.current?.actualFps ?? 0;
          const cause = androidFps >= m.fps_target * 0.8
            ? 'desktop decode/processing limited'
            : 'phone capture/encode limited';
          addDiag('fpsDrift', `FPS running low: ${out}/${m.fps_target} (${cause})`);
        }
      } else {
        fpsLowSinceRef.current = 0;
        addDiag('fpsDrift', `FPS ok: ${out}/${m.fps_target}`);
      }
    }
  }, [vcamState, addDiag]);

  useEffect(() => {
    if (androidStreamStatus === 'error') addDiag('androidConn', 'Android control server unreachable');
    else if (androidStreamStatus === 'running') addDiag('androidConn', 'Android control server reachable');
  }, [androidStreamStatus, addDiag]);

  useEffect(() => {
    androidMetricsRef.current = androidMetrics;
    if (androidMetrics?.actualFps != null) {
      addDiag('androidFps', `Android capture: ${androidMetrics.actualFps}/${settingsRef.current.fps} fps at ${androidMetrics.encodedWidth}x${androidMetrics.encodedHeight}`);
    }
    if (androidMetrics?.fallbackUsed) {
      addDiag('resFallback', `Resolution fallback: ${androidMetrics.resolutionPolicy} (${androidMetrics.selectedRawWidth}x${androidMetrics.selectedRawHeight})`);
    }
  }, [androidMetrics, addDiag]);

  useEffect(() => { vcamStateRef.current = vcamState; }, [vcamState]);

  // Sampled metrics summary to the persistent log every 10s — a readable
  // one-liner, not per-second spam.
  useEffect(() => {
    const id = setInterval(() => {
      const s = settingsRef.current;
      const m = vcamStateRef.current?.metrics;
      const am = androidMetricsRef.current;
      if (!m && !am) return;
      const parts = [
        `${s.streamMode} ${s.width}x${s.height}@${s.fps} q${s.jpegQuality}`,
        `androidFps=${am?.actualFps ?? '?'}`,
        // Android per-stage profiling (ms): YUV->NV21, rotate, JPEG encode, total.
        am ? `android[yuv=${(am.yuvMsAvg ?? 0).toFixed?.(1) ?? am.yuvMsAvg} rot=${(am.rotateMsAvg ?? 0).toFixed?.(1) ?? am.rotateMsAvg} jpeg=${(am.jpegMsAvg ?? 0).toFixed?.(1) ?? am.jpegMsAvg} enc=${(am.androidEncodeMsAvg ?? 0).toFixed?.(1) ?? am.androidEncodeMsAvg}]` : '',
        // transport = complete JPEGs or H.264 access units received; written =
        // distinct decoded frames published to the ring. A large gap identifies
        // the desktop decode/processing stage rather than the phone transport.
        m ? `transportReceivedFps=${m.transport_received_fps ?? m.transport_fps ?? m.http_jpeg_fps} producerDecodedFps=${m.producer_decoded_fps ?? m.decoded_fps} ringWrittenFps=${m.ring_written_fps ?? m.written_fps} configuredSourceFps=${m.source_fps ?? m.fps_target}` : 'prod=off (producer not started — OBS is not receiving frames)',
        // Producer per-stage profiling (ms) + which optimized paths ran.
        m ? `prod[decode=${m.decode_ms_avg}(${m.decode_backend ?? '?'}) rot=${m.rotate_ms_avg} resize=${m.resize_ms_avg}(${m.resize_backend ?? '?'}) write=${m.write_ms_avg}]` : '',
        m ? `transportMbps=${m.transport_bandwidth_mbps ?? m.estimated_mbps} producerProcessingMs=${m.producer_processing_ms ?? m.total_pipeline_ms}${m.source === 'mjpeg' ? ` jpegDrop=${m.dropped_jpegs} jpegQ=${m.jpeg_queue_len}` : ''}` : '',
        (vcamStateRef.current?.last_error || m?.last_error) ? `err=${vcamStateRef.current?.last_error || m?.last_error}` : '',
      ].filter(Boolean);
      logEvent('metrics', parts.join('  '));
    }, 10000);
    return () => clearInterval(id);
  }, []);

  const copyDiagnostics = async () => {
    const s = settingsRef.current;
    const m = vcamState?.metrics;
    const snapshot = [
      '=== OpenCamBridge diagnostics snapshot ===',
      `Connection: ${token ? 'LAN (token)' : 'USB'}  base=${baseUrl}`,
      `Android control server: ${androidStreamStatus}`,
      `Lens: ${activeCam ? `${activeCam.label} (${activeCam.id})` : s.cameraId}  torch=${torchSupported}`,
      `Resolution: ${s.width}x${s.height}  requested fps: ${s.fps}  maxFps@res: ${maxFpsHere || 'unknown'}`,
      `Codec: ${s.streamMode}  jpegQuality: ${s.jpegQuality}  targetBandwidth: ${s.targetBandwidthMbps || 'off'}`,
      `Rotation: ${s.displayRotation}  mirror: ${s.mirror}`,
      `Android FPS actual: ${androidMetrics?.actualFps ?? '?'}  encoded: ${androidMetrics?.encodedWidth}x${androidMetrics?.encodedHeight}`,
      m ? `Producer: transport ${m.transport_received_fps ?? m.transport_fps} / decoded ${m.producer_decoded_fps ?? m.decoded_fps} / ring ${m.ring_written_fps ?? m.written_fps} fps, configured source ${m.source_fps ?? m.fps_target}, ${m.transport_bandwidth_mbps ?? m.estimated_mbps} Mbps, processing ${m.producer_processing_ms ?? m.total_pipeline_ms}ms${m.source === 'mjpeg' ? `, JPEG dropped ${m.dropped_jpegs}, queue ${m.jpeg_queue_len}` : ''}` : 'Producer: not running',
      `Producer last error: ${vcamState?.last_error || m?.last_error || 'none'}`,
      `Desktop preview: ready=${previewDiagnostics.ready} ring=${previewDiagnostics.ringAlive} generation=${previewDiagnostics.streamGeneration} ringWrite=${previewDiagnostics.ringWriteSequence} receivedFps=${previewDiagnostics.previewReceivedFps} displayedFps=${previewDiagnostics.previewDisplayedFps} skipped=${previewDiagnostics.previewSkippedSequences} ipcMs=${previewDiagnostics.ipcTransferMs.toFixed(2)} uploadMs=${previewDiagnostics.previewUploadMs.toFixed(2)} colour=${previewDiagnostics.colorMatrix}/${previewDiagnostics.colorRange} parsed=${previewDiagnostics.parsedWidth}x${previewDiagnostics.parsedHeight} displayed=${previewDiagnostics.lastDisplayedSequence} error=${previewDiagnostics.lastError || 'none'}`,
      '=== event log ===',
      ...diagLog,
    ].join('\n');
    try {
      await navigator.clipboard.writeText(snapshot);
      addDiag('copy', 'Diagnostics copied to clipboard');
    } catch (e) {
      addDiag('copy', `Copy failed: ${e}`);
    }
  };

  const handleRegisterVcam = async () => {
    setIsVcamRegistering(true);
    setVcamMessage('Registering...');
    try {
      const msg = await invoke<string>('register_virtual_camera_backend');
      const details = await invoke<string>('get_virtual_camera_backend_details');
      setVcamMessage(`${msg} ${details.split('\n')[0]}`);
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      setVcamMessage(e.toString());
    }
    setIsVcamRegistering(false);
  };

  const pipelineCommand = async (path: string) => {
    const response = await apiFetch(baseUrl, path, token, { method: 'POST' });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.success === false) throw new Error(body.message || `HTTP ${response.status}`);
    if (body.revision != null) authoritativeRevisionRef.current = Number(body.revision);
    return body;
  };

  const handleUnregisterVcam = async () => {
    setIsVcamRegistering(true);
    setVcamMessage('Removing OpenCamBridge virtual camera...');
    try {
      const msg = await invoke<string>('unregister_virtual_camera_backend');
      setVcamMessage(msg);
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      setVcamMessage(e.toString());
    }
    setIsVcamRegistering(false);
  };
  const startStream = () => pipelineCommand('/api/stream/start');
  const stopStream = () => pipelineCommand('/api/stream/stop');

  const handleStartProducer = async (
    s: any,
    actual?: any,
    purpose: ProducerPurpose = 'feed'
  ) => {
    const { source, targetUrl, sourceWidth, sourceHeight, sourceFps, outputWidth, outputHeight } =
      buildProducerLaunchSpec(s, actual, baseUrl);
    // jpegQuality is applied on the Android side; the producer no longer takes it.
    console.log('[Tauri UI] Calling start_virtual_camera_feeder with', {
      url: targetUrl, source,
      sourceWidth, sourceHeight, sourceFps,
      outputWidth, outputHeight, profile: s.profile
    });
    await invoke('start_virtual_camera_feeder', {
      url: targetUrl,
      source,
      width: outputWidth,
      height: outputHeight,
      fps: sourceFps,
      sourceWidth,
      sourceHeight,
      sourceFps,
      profile: s.profile,
      token: token || undefined
    });
    producerPurposeRef.current = purpose;
    console.log('[Tauri UI] start_virtual_camera_feeder completed');
  };

  // The H.264 preview reads decoded NV12 frames from the producer's shared
  // ring. Keep that decoder/feed independent from the Media Foundation camera
  // host: opening the desktop app starts preview decoding, but it does not
  // activate the webcam for OBS until the user presses Start Webcam.
  useEffect(() => {
    if (androidMetrics?.lifecycleState !== 'STREAMING') {
      // An explicit Stop suppresses the preview producer while the local
      // metrics sample is still stale. Once Android reports the stopped state,
      // a later genuine stream start may auto-start H.264 preview again.
      previewAutoStartSuppressedRef.current = false;
    }
    const sourceFps = Number(
      androidMetrics?.selectedFps || androidMetrics?.encodedFps || settings.fps || 0
    );
    const shouldStart = shouldStartH264PreviewProducer({
      previewEnabled: !previewOff,
      settingsHydrated: settingsHydratedRef.current,
      lifecycleState: androidMetrics?.lifecycleState,
      activeStreamMode: androidMetrics?.activeStreamMode || settings.streamMode,
      producerRunning: !!vcamState?.process_running,
      sourceWidth: androidMetrics?.encodedWidth,
      sourceHeight: androidMetrics?.encodedHeight,
      sourceFps
    });
    if (!shouldStart || previewAutoStartSuppressedRef.current || isSyncingRef.current || previewProducerStartInFlightRef.current) return;
    if (Date.now() < previewProducerRetryAfterRef.current) return;

    previewProducerStartInFlightRef.current = true;
    addDiag('h264Preview', 'Starting the desktop H.264 preview decoder');
    void handleStartProducer(settingsRef.current, androidMetrics, 'preview')
      .then(async () => {
        previewProducerRetryAfterRef.current = 0;
        setVcamMessage('');
        const state = await invoke<VirtualCamState>('get_virtual_camera_status');
        vcamStateRef.current = state;
        setVcamState(state);
        addDiag('h264Preview', 'Desktop H.264 producer is running; waiting for the renderer to display a preview frame');
        window.dispatchEvent(new CustomEvent('reload-preview'));
      })
      .catch((error: any) => {
        previewProducerRetryAfterRef.current = Date.now() + 5000;
        const message = `H.264 preview decoder failed: ${String(error)}`;
        setVcamMessage(message);
        addDiag('h264Preview', message);
      })
      .finally(() => {
        previewProducerStartInFlightRef.current = false;
      });
  }, [androidMetrics, previewOff, settings.fps, settings.streamMode, vcamState?.process_running, addDiag]);

  const handleStartNativeCamera = async () => {
    const s = settingsRef.current;

    setVcamMessage('Starting pipeline...');
    try {
      previewAutoStartSuppressedRef.current = false;
      await waitForPreviewProducerIdle();
      if (!vcamState?.host_running || !vcamState?.host_activated) {
        await invoke('start_virtual_camera_host');
      }

      await restartFullPipelineWithSettings(s, []);

      setVcamMessage('');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      console.error('[Tauri UI] handleStartNativeCamera failed:', e);
      setVcamMessage(`Error: ${e.toString()}`);
      // A producer that is alive but failed readiness must not leave the Start
      // button disabled. Keep the visible error and return to a retryable state.
      try { await invoke('stop_virtual_camera_feeder'); producerPurposeRef.current = null; } catch {}
      try { setVcamState(await invoke<VirtualCamState>('get_virtual_camera_status')); } catch {}
    }
  };

  const handleStopNativeCamera = async () => {
    try {
      previewAutoStartSuppressedRef.current = true;
      await waitForPreviewProducerIdle();
      await invoke('stop_virtual_camera_feeder');
      producerPurposeRef.current = null;
      await invoke('stop_virtual_camera_host');
      await stopStream();
      setVcamMessage('Stopped native pipeline.');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      previewAutoStartSuppressedRef.current = false;
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const handleStartFeedOnly = async () => {
    const s = settingsRef.current;

    setVcamMessage('Starting feed...');
    try {
      previewAutoStartSuppressedRef.current = false;
      await waitForPreviewProducerIdle();
      const actual = await restartAndroidStreamWithSettings(s, []);
      await handleStartProducer(s, actual, 'feed');

      setVcamMessage('');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);

      if (!previewOff) {
        setTimeout(() => window.dispatchEvent(new CustomEvent('reload-preview')), 1000);
      }
    } catch (e: any) {
      console.error('[Tauri UI] handleStartFeedOnly failed:', e);
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const handleStopFeedOnly = async () => {
    try {
      previewAutoStartSuppressedRef.current = true;
      await waitForPreviewProducerIdle();
      await invoke('stop_virtual_camera_feeder');
      producerPurposeRef.current = null;
      await stopStream();
      setVcamMessage('Stopped feed.');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      previewAutoStartSuppressedRef.current = false;
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

  const waitForPreviewProducerIdle = async () => {
    const deadline = Date.now() + 22_000;
    while (previewProducerStartInFlightRef.current && Date.now() < deadline) {
      await sleep(50);
    }
    if (previewProducerStartInFlightRef.current) {
      throw new Error('Timed out waiting for H.264 preview decoder startup');
    }
  };

  const postSettingsToAndroid = async (s: any, keysChanged: string[]) => {
    if (keysChanged.length === 0) return null;
    if (!settingsHydratedRef.current || authoritativeRevisionRef.current == null) {
      throw new Error('Phone settings have not been loaded; refusing to post local defaults');
    }
    const patch: any = {};
    const directKeys = [
      'profile', 'width', 'height', 'outputWidth', 'outputHeight', 'fps', 'jpegQuality',
      'cameraId', 'aspectRatio', 'displayRotation', 'mirror', 'streamMode',
      'targetBandwidthMbps', 'h264Bitrate', 'h264KeyframeInterval'
    ];
    for (const key of directKeys) {
      if (keysChanged.includes(key)) patch[key] = s[key];
    }
    // Keep the interval inside the range the phone accepts, so a stale or
    // out-of-range persisted value cannot make an unrelated edit (an MJPEG
    // resolution change, say) fail validation on merge. This clamps rather than
    // pinning: the interval is a real setting now that keyframes are requested
    // on demand, and overwriting it here would silently undo the user's choice.
    patch.h264KeyframeInterval = Math.min(10, Math.max(1, Number(s.h264KeyframeInterval) || 5));
    if (keysChanged.includes('width') || keysChanged.includes('height')) {
      patch.outputWidth = s.outputWidth;
      patch.outputHeight = s.outputHeight;
    }
    const response = await apiFetch(baseUrl, '/api/settings', token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildSettingsMutation(
        patch,
        authoritativeRevisionRef.current,
        globalThis.crypto?.randomUUID?.() || `tauri-${Date.now()}`,
        'tauri'
      ))
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.success === false) {
      const rejection = describeMutationRejection(response.status, body);
      if (rejection.authoritativeState) {
        importAuthoritativeState(rejection.authoritativeState, true);
      } else {
        fetchStatus();
      }
      throw new Error(rejection.message);
    }
    if (body.revision != null) authoritativeRevisionRef.current = Number(body.revision);
    if (body.authoritativeState) importAuthoritativeState(body.authoritativeState, true);
    return body;
  };

  const applySettingsAndRefreshPreview = async (nextSettings: any, keysChanged: string[]) => {
    setIsSyncing(true);
    isSyncingRef.current = true;

    try {
      await waitForPreviewProducerIdle();
      // h264Bitrate and jpegQuality are intentionally absent: Android applies
      // both to the live pipeline without a rebind (bitrate via
      // MediaCodec.setParameters; JPEG quality is read per-frame). Restarting
      // the whole pipeline on every quality-slider step was the source of the
      // repeated producer restarts.
      const streamImpacting = ['profile', 'width', 'height', 'fps', 'cameraId', 'streamMode', 'h264KeyframeInterval', 'displayRotation', 'mirror'].some(k => keysChanged.includes(k));

      // Android capture, the decoded producer feed, and the virtual-camera
      // host are separate lifecycle layers. H.264 preview needs the producer,
      // but only an activated host publishes that feed as a Windows camera.
      const liveVcamState = vcamStateRef.current ?? vcamState;
      const producerRunning = !!(liveVcamState?.process_running ?? liveVcamState?.running);
      const androidStreaming =
        androidStreamStatus === 'running' &&
        (Number(androidMetrics?.encodedWidth || 0) > 0 ||
          Number(androidMetrics?.fps || 0) > 0 ||
          Number(androidMetrics?.latestFrameRevision || 0) > 0);

      const restartScope = selectPipelineRestartScope({
        streamImpacting,
        producerRunning,
        hostRunning: !!liveVcamState?.host_running,
        hostActivated: !!liveVcamState?.host_activated
      });

      if (restartScope === 'webcam') {
        // The complete webcam is active: preserve both its decoded feed and
        // its activated Media Foundation host across the settings change.
        addDiag('apply', `[${keysChanged.join(',')}] -> full pipeline restart (Android + producer)`);
        await restartFullPipelineWithSettings(nextSettings, keysChanged);
      } else if (restartScope === 'producer') {
        const previewOnly = producerPurposeRef.current === 'preview';
        if (previewOnly && nextSettings.streamMode !== 'h264') {
          // MJPEG renders directly in the WebView, so a producer that existed
          // only for H.264 preview is no longer needed after this mode switch.
          addDiag('apply', `[${keysChanged.join(',')}] -> Android rebind; release H.264 preview decoder`);
          await invoke('stop_virtual_camera_feeder');
          producerPurposeRef.current = null;
          await restartAndroidStreamWithSettings(nextSettings, keysChanged);
        } else {
          addDiag('apply', `[${keysChanged.join(',')}] -> Android + decoded feed restart (webcam host off)`);
          await restartProducerPipelineWithSettings(
            nextSettings,
            keysChanged,
            producerPurposeRef.current || 'feed'
          );
        }
      } else if (restartScope === 'android' && androidStreaming) {
        // MJPEG preview does not need the decoded producer feed. H.264 will
        // start its preview-only producer as soon as the rebind is complete.
        addDiag('apply', `[${keysChanged.join(',')}] -> Android rebind only (producer off)`);
        await restartAndroidStreamWithSettings(nextSettings, keysChanged);
      } else {
        addDiag('apply', `[${keysChanged.join(',')}] -> settings only (no rebind)`);
        // Non-stream-impacting change (quality/mirror/rotation/bandwidth), or
        // nothing is live. The running MJPEG already reflects quality/rotation
        // live and mirror is a preview transform, so do NOT reload the preview.
        await postSettingsToAndroid(nextSettings, keysChanged);
      }
      // The phone accepted the revision. Reflect the requested desired state;
      // the next authoritative status import may refine it after adaptation.
      settingsRef.current = nextSettings;
      setSettings(nextSettings);
    } catch (err: any) {
      console.error('[Tauri UI] applySettingsAndRefreshPreview failed:', err);
      setVcamMessage(`Settings apply failed: ${err.toString()}`);
      fetchStatus();
    } finally {
      setIsSyncing(false);
      isSyncingRef.current = false;
    }
  };

  const updateSetting = async (key: string, value: any) => {
    let newSettings = { ...settingsRef.current, [key]: value };
    if (key === 'cameraId' || key === 'streamMode') {
      const camera = cameras.find(c => c.id === newSettings.cameraId) as any;
      const modes = newSettings.streamMode === 'h264' ? camera?.h264Modes : camera?.mjpegModes;
      const canonical = Array.isArray(modes) ? modes : [];
      const selected = canonical.find((m: any) =>
        m.width === newSettings.width && m.height === newSettings.height && m.fps === newSettings.fps
      ) || canonical[0];
      if (selected) newSettings = {
        ...newSettings, width: selected.width, height: selected.height, fps: selected.fps,
        outputWidth: selected.width, outputHeight: selected.height
      };
    }

    if (key === 'torchEnabled') {
      try {
        const res = await apiFetch(baseUrl, '/api/camera/torch', token, { method: 'POST', body: JSON.stringify({
          enabled: value, baseRevision: authoritativeRevisionRef.current,
          requestId: globalThis.crypto?.randomUUID?.() || `tauri-${Date.now()}`, clientType: 'tauri'
        }), headers: { 'Content-Type': 'application/json' }});
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
          if (body.authoritativeState) importAuthoritativeState(body.authoritativeState, true); else fetchStatus();
          addDiag('torch', `Torch ${value ? 'on' : 'off'} failed: HTTP ${res.status} ${body.message || ''}`);
          setVcamMessage(`Torch not available on this camera (HTTP ${res.status}).`);
        } else {
          if (body.revision != null) authoritativeRevisionRef.current = Number(body.revision);
          settingsRef.current = newSettings; setSettings(newSettings);
          addDiag('torch', `Torch ${value ? 'on' : 'off'}`);
        }
      } catch (e: any) {
        addDiag('torch', `Torch request failed: ${e}`);
        setVcamMessage(`Torch request failed: ${e}`);
      }
      return;
    } else if (key === 'linearZoom') {
      try {
        const res = await apiFetch(baseUrl, '/api/camera/zoom', token, { method: 'POST', body: JSON.stringify({
          linearZoom: value, baseRevision: authoritativeRevisionRef.current,
          requestId: globalThis.crypto?.randomUUID?.() || `tauri-${Date.now()}`, clientType: 'tauri'
        }), headers: { 'Content-Type': 'application/json' }});
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
          if (body.authoritativeState) importAuthoritativeState(body.authoritativeState, true); else fetchStatus();
          addDiag('zoom', `Zoom failed: HTTP ${res.status}`);
        } else {
          if (body.revision != null) authoritativeRevisionRef.current = Number(body.revision);
          settingsRef.current = newSettings; setSettings(newSettings);
        }
      } catch (e: any) {
        addDiag('zoom', `Zoom request failed: ${e}`);
      }
      return;
    }

    await applySettingsAndRefreshPreview(
      newSettings,
      key === 'cameraId' || key === 'streamMode'
        ? [key, 'width', 'height', 'fps', 'outputWidth', 'outputHeight']
        : [key]
    );
  };

  const waitForAndroidResolution = async (s: any, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    let lastMetrics: any = null;

    while (Date.now() < deadline) {
      try {
        const res = await apiFetch(baseUrl, '/api/stream/metrics', token);
        if (res.ok) {
          const m = normalizeAndroidMetrics(await res.json());
          lastMetrics = m;

          const encodedOk =
            Number(m.encodedWidth) === Number(s.width) &&
            Number(m.encodedHeight) === Number(s.height);

          const hasFrame =
            Number(m.latestFrameRevision || 0) > 0 ||
            Number(m.fps || 0) > 0 ||
            Number(m.encodedWidth || 0) > 0;

          if (hasFrame && (encodedOk || m.fallbackUsed || m.activeStreamMode !== s.streamMode)) {
            return m;
          }
        }
      } catch {}

      await sleep(300);
    }

    // A different size is accepted only as an explicit adaptive/fallback
    // selection. An unexplained mismatch is a failed start, not silent resize.
    const hasAnyFrame = lastMetrics && (
      Number(lastMetrics.latestFrameRevision || 0) > 0 ||
      Number(lastMetrics.encodedWidth || 0) > 0
    );
    if (hasAnyFrame && (lastMetrics.fallbackUsed || lastMetrics.activeStreamMode !== s.streamMode || s.profile === 'adaptive')) {
      console.warn(
        `[Tauri UI] Android is streaming ${lastMetrics.encodedWidth}x${lastMetrics.encodedHeight} ` +
        `instead of the requested ${s.width}x${s.height}; continuing (producer resizes).`
      );
      setVcamMessage(
        `Adaptive capture: requested ${s.width}x${s.height}@${s.fps}, ` +
        `selected/actual ${lastMetrics.encodedWidth}x${lastMetrics.encodedHeight}@${lastMetrics.captureFps || 0}. ` +
        `${lastMetrics.fallbackReason || 'Producer will resize once on the GPU.'}`
      );
      return lastMetrics;
    }

    if (hasAnyFrame) throw new Error(
      `Android streamed unexplained ${lastMetrics.encodedWidth}x${lastMetrics.encodedHeight} ` +
      `instead of requested ${s.width}x${s.height}; refusing silent fallback.`
    );

    throw new Error(
      `Android did not start streaming after settings change (requested ${s.width}x${s.height}). ` +
      `Check the phone's Logs tab for camera errors.`
    );
  };

  const restartAndroidStreamWithSettings = async (s: any, keysChanged: string[]) => {
    console.log('[Tauri UI] Applying Android pipeline settings:', keysChanged);
    await postSettingsToAndroid(s, keysChanged);
    // Start is idempotent. If settings were applied while streaming, the phone
    // actor has already completed the required rebind before POST returned.
    await startStream();

    const metrics = await waitForAndroidResolution(s);
    if (metrics.lifecycleState !== 'STREAMING') {
      throw new Error(`Android pipeline is ${metrics.lifecycleState || 'not STREAMING'} after startup`);
    }
    console.log('[Tauri UI] Android stream rebound OK:', metrics);

    return metrics;
  };

  const restartProducerPipelineWithSettings = async (
    s: any,
    keysChanged: string[],
    purpose: ProducerPurpose
  ) => {
    // A preview/feed producer owns the same decoded NV12 ring as the complete
    // webcam pipeline, but no virtual-camera host is active. Restart only the
    // Android source and producer, and verify ring readiness without requiring
    // COM registration or host activation.
    try {
      await invoke('stop_virtual_camera_feeder');
      producerPurposeRef.current = null;
      addDiag('pipeline', 'decoded feed stopped for reconfiguration');
    } catch (error: any) {
      addDiag('pipeline', `decoded feed stop failed: ${error}`);
    }

    const actual = await restartAndroidStreamWithSettings(s, keysChanged);
    await handleStartProducer(s, actual, purpose);
    const state = await invoke<VirtualCamState>('get_virtual_camera_status');
    setVcamState(state);
    const committed = Number(state.metrics?.ring_frames_committed || 0);
    addDiag(
      'pipeline',
      `decodedFeedRunning=${!!state.process_running} state=${state.producer_state} committed=${committed}`
    );
    if (!state.process_running) throw new Error('Decoded frame producer exited during startup');
    if (state.producer_state !== 'WRITING_RING') {
      throw new Error(`Decoded frame producer is ${state.producer_state || 'not writing the ring'}`);
    }
    if (committed < 3) throw new Error(`Decoded frame producer committed only ${committed}/3 readiness frames`);

    fetchStatus();
    if (!previewOff) window.dispatchEvent(new CustomEvent('reload-preview'));
  };

  const restartFullPipelineWithSettings = async (s: any, keysChanged: string[]) => {
    // This path is only taken when the producer was actually running (see
    // applySettingsAndRefreshPreview using vcamState.running), so it MUST end
    // with the producer running again. Verify it and surface a real error if not
    // — a settings change must never silently leave OBS with prod=off.
    addDiag('pipeline', 'full restart: producerWasRunning=true');
    try {
      await invoke('stop_virtual_camera_feeder');
      producerPurposeRef.current = null;
      addDiag('pipeline', 'stopProducer ok');
    }
    catch (e: any) { addDiag('pipeline', `stopProducer fail: ${e}`); }

    let actual: any = null;
    try { actual = await restartAndroidStreamWithSettings(s, keysChanged); addDiag('pipeline', `androidRebind ok (${actual?.activeStreamMode || s.streamMode})`); }
    catch (e: any) { addDiag('pipeline', `androidRebind fail: ${e}`); throw e; }

    await handleStartProducer(s, actual, 'webcam');
    const readinessDeadline = Date.now() + 12_000;
    let state = await invoke<VirtualCamState>('get_virtual_camera_status');
    while (Date.now() < readinessDeadline) {
      setVcamState(state);
      if (!state.host_running || !state.host_activated) {
        throw new Error(state.last_error || 'Virtual-camera host exited before pipeline readiness');
      }
      if (!state.process_running && state.producer_state === 'FAILED') {
        throw new Error(state.last_error || 'Producer process exited during startup');
      }
      if (state.pipeline_ready) break;
      await sleep(250);
      state = await invoke<VirtualCamState>('get_virtual_camera_status');
    }
    setVcamState(state);
    const committed = Number(state.metrics?.ring_frames_committed || 0);
    addDiag('pipeline', `finalProducerRunning=${!!state.process_running} state=${state.producer_state} committed=${committed}`);
    if (!state.process_running) throw new Error('Producer process exited during startup');
    if (state.producer_state !== 'WRITING_RING') throw new Error(`Producer is ${state.producer_state || 'not writing the ring'}`);
    if (committed < 3) throw new Error(`Producer committed only ${committed}/3 readiness frames`);
    if (!state.host_running) throw new Error('Virtual-camera host exited during startup');
    if (!state.host_activated) throw new Error('Virtual-camera host did not activate the Media Foundation camera');
    if (!state.registered) throw new Error('Virtual-camera backend is not registered');
    if (!state.pipeline_ready) throw new Error('Complete webcam readiness timed out before the pipeline became ready');

    fetchStatus();

    if (!previewOff) {
      setTimeout(() => window.dispatchEvent(new CustomEvent('reload-preview')), 1000);
    }
  };

  const updateProfile = async (profile: string) => {
    const preset = PROFILE_PRESETS[profile];
    if (!preset) {
      console.error('Unknown profile:', profile);
      return;
    }
    const selected = activeModes.find((m: any) =>
      m.width === preset.width && m.height === preset.height && m.fps === preset.fps
    ) || activeModes[0];
    if (!selected) {
      setVcamMessage(`No canonical ${settingsRef.current.streamMode.toUpperCase()} modes are available on this camera.`);
      return;
    }
    const newSettings = {
      ...settingsRef.current, ...preset,
      width: selected.width, height: selected.height, fps: selected.fps,
      outputWidth: selected.width, outputHeight: selected.height
    };
    await applySettingsAndRefreshPreview(newSettings, ['profile', 'width', 'height', 'fps', 'jpegQuality']);
  };

  // Resolution and FPS are independent knobs, not baked into profile names.
  // Picking a resolution selects a capture policy that permits that size on the
  // phone (via `profile`) but leaves the frame rate untouched, so any
  // resolution can pair with any FPS (e.g. 720p30, 720p60, 1080p30, 1080p60).
  const updateResolution = async (w: number, h: number) => {
    const profile = w >= 1920 ? 'quality' : w >= 1280 ? 'balanced' : 'low-latency';
    const validFps = activeModes.filter((m: any) => m.width === w && m.height === h).map((m: any) => Number(m.fps));
    const selectedFps = validFps.includes(settingsRef.current.fps) ? settingsRef.current.fps : validFps[0];
    const next = {
      ...settingsRef.current,
      width: w, height: h, outputWidth: w, outputHeight: h, profile, fps: selectedFps,
    };
    logTestMarker('START', `${next.streamMode} ${w}x${h}@${next.fps} lens=${next.cameraId} q${next.jpegQuality}`);
    await applySettingsAndRefreshPreview(next, ['width', 'height', 'outputWidth', 'outputHeight', 'profile', 'fps']);
  };

  const updateFps = async (fps: number) => {
    const next = { ...settingsRef.current, fps };
    logTestMarker('START', `${next.streamMode} ${next.width}x${next.height}@${fps} lens=${next.cameraId} q${next.jpegQuality}`);
    await applySettingsAndRefreshPreview(next, ['fps']);
  };

  // Orientation mode ('auto' | '16:9' | '9:16'). Content is always
  // auto-uprighted on the phone for how it is physically held; this controls
  // the VIEW: Auto lets the preview follow the phone (vertical phone -> 9:16
  // preview), Horizontal/Vertical pin it. Selecting a mode also clears any
  // legacy manual rotation offset so old saved rotations cannot leave the
  // stream sideways. The virtual camera output itself stays 16:9 (consuming
  // apps expect a landscape webcam); vertical video is pillarboxed there.
  const orientationMode =
    settings.aspectRatio === '9:16' || settings.aspectRatio === '16:9'
      ? settings.aspectRatio
      : 'auto';
  const updateOrientationMode = async (mode: string) => {
    addDiag('orientation', `Preview layout -> ${mode}`);
    const next = { ...settingsRef.current, aspectRatio: mode, displayRotation: '0' };
    await applySettingsAndRefreshPreview(next, ['aspectRatio', 'displayRotation']);
  };

  // Manual rotate: cycles the on-phone rotation offset 0->90->180->270. It is a
  // display field (applied on the phone, no rebind), so it does not restart the
  // pipeline.
  const rotateOutput = async () => {
    const cur = parseInt(settings.displayRotation, 10) || 0;
    const next = { ...settingsRef.current, displayRotation: ((cur + 90) % 360).toString() };
    addDiag('rotate', `Rotate -> ${next.displayRotation}°`);
    await applySettingsAndRefreshPreview(next, ['displayRotation']);
  };

  // ---- Derived product state -------------------------------------------------
  // These are the only questions the overview needs to answer, in order:
  // is it on air, is it ready, is anything degraded, and where.
  const androidRunning = androidStreamStatus === 'running';
  const producerRunning = !!vcamState?.process_running;
  const framesReady = !!vcamState?.pipeline_ready;
  const isLive = !!vcamState?.virtual_camera_ready;
  const consumerAttached = isLive || !!vcamState?.metrics?.ring?.consumer_attached;
  const binariesBlocked = !!(vcamState?.binary_identity && !vcamState.binary_identity.ready);
  const activeError = vcamState?.last_error || vcamState?.metrics?.last_error || '';
  const ring = vcamState?.metrics?.ring;
  const metrics = vcamState?.metrics;
  const transport: 'USB' | 'LAN' = token ? 'LAN' : 'USB';
  const diagAlert = binariesBlocked ? 'fail' : activeError || androidMetrics?.fallbackUsed ? 'warn' : '';

  const tallyState = isLive ? 'is-live' : framesReady ? 'is-ready' : producerRunning ? 'is-busy' : '';
  const tallyTitle = isLive ? 'On air' : framesReady ? 'Ready' : producerRunning ? vcamState?.producer_state || 'Starting' : 'Off air';
  const tallyNote = isLive
    ? 'An app is reading OpenCamBridge Camera.'
    : framesReady
      ? 'Frames are queued. Select OpenCamBridge Camera in your app.'
      : producerRunning && !vcamState?.host_running
        ? 'Desktop preview only — nothing is published to Windows.'
        : producerRunning
          ? `Producer is ${vcamState?.producer_state || 'starting'}.`
          : 'Nothing is published to Windows yet.';

  const dash = (value: any, suffix = '') =>
    value === null || value === undefined || value === '' ? '—' : `${value}${suffix}`;

  return (
    <div className="control-panel panel animate-fade">
      {/* The tally lamp answers "is this actually working" before any number
          does, and it stays visible on every tab. */}
      <div className="rail-head">
        <div className={`tally ${tallyState}`}>
          <Lamp state={isLive ? 'live' : framesReady ? 'ok' : producerRunning ? 'busy' : 'idle'} />
          <span className="tally__title">{tallyTitle}</span>
          <span className="tally__sub">{tallyNote}</span>
        </div>
      </div>

      <nav className="tabs" role="tablist">
        {([
          ['signal', 'Signal'],
          ['output', 'Output'],
          ['image', 'Image'],
          ['diag', 'Diag'],
        ] as const).map(([id, label]) => (
          <button
            key={id}
            role="tab"
            aria-selected={tab === id}
            className={`tab${tab === id ? ' is-active' : ''}`}
            onClick={() => setTab(id)}
          >
            {label}
            {id === 'diag' && diagAlert && <span className={`tab__dot${diagAlert === 'fail' ? ' tab__dot--fail' : ''}`} />}
          </button>
        ))}
      </nav>

      <div className="control-panel__scroll">
        {/* ================================================================
            SIGNAL — start/stop, and where frames are being lost
            ================================================================ */}
        {tab === 'signal' && (
          <div className="stagger">
            <Section legend="Publish to Windows" icon={<Radio size={13} />}>
              {vcamState && !vcamState.registered ? (
                <div className="stack stack--10">
                  <Notice kind="warn" icon={<ShieldAlert size={14} />} title="Camera driver is not registered">
                    Windows cannot see OpenCamBridge Camera until its COM object is registered once, from an
                    elevated terminal.
                  </Notice>
                  <button className="btn btn-primary btn--block btn--lg" onClick={handleRegisterVcam} disabled={isVcamRegistering}>
                    {isVcamRegistering ? <RefreshCw size={15} className="animate-spin" /> : <ShieldAlert size={15} />}
                    {isVcamRegistering ? 'Registering…' : 'Register camera backend'}
                  </button>
                </div>
              ) : (
                <div className="stack stack--10">
                  <div className="btn-row">
                    <button
                      className="btn btn-primary btn--lg"
                      onClick={handleStartNativeCamera}
                      disabled={!!(vcamState?.process_running && vcamState?.host_running)}
                    >
                      <Play size={15} /> Start webcam
                    </button>
                    <button
                      className="btn btn--lg"
                      onClick={handleStopNativeCamera}
                      disabled={!vcamState?.process_running && !vcamState?.host_running}
                    >
                      <Square size={15} /> Stop
                    </button>
                  </div>

                  {binariesBlocked && vcamState?.binary_identity && (
                    <Notice kind="fail" icon={<AlertTriangle size={14} />} title="Stale or mismatched camera binaries">
                      {vcamState.binary_identity.error}
                      <code>{vcamState.binary_identity.remediation}</code>
                    </Notice>
                  )}

                  {activeError && (
                    <Notice kind="fail" icon={<AlertTriangle size={14} />} title="Last error">
                      {activeError}
                    </Notice>
                  )}

                  {vcamMessage && <p className="hint">{vcamMessage}</p>}

                  {devMode && !vcamState?.process_running && !vcamState?.host_running && (
                    <button className="btn btn-danger btn--block" onClick={handleUnregisterVcam} disabled={isVcamRegistering}>
                      Remove virtual camera registration
                    </button>
                  )}

                  {devMode && (
                    <Well legend="Granular pipeline (developer)" icon={<Cpu size={12} />}>
                      <div className="field">
                        <span className="field__label">Phone feed only</span>
                        <div className="btn-row">
                          <button className="btn btn--sm" onClick={handleStartFeedOnly} disabled={vcamState?.process_running}>
                            <Play size={12} /> Start
                          </button>
                          <button className="btn btn--sm" onClick={handleStopFeedOnly} disabled={!vcamState?.process_running}>
                            <Square size={12} /> Stop
                          </button>
                        </div>
                      </div>
                      <div className="field">
                        <span className="field__label">Virtual camera host</span>
                        <div className="btn-row">
                          <button
                            className="btn btn--sm"
                            disabled={vcamState?.host_running}
                            onClick={async () => {
                              try { await invoke('start_virtual_camera_host'); setVcamMessage(''); addDiag('host', 'Virtual camera host started'); }
                              catch (e: any) { setVcamMessage(`Virtual camera host failed: ${e}`); addDiag('host', `Host start failed: ${e}`); }
                              invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
                            }}
                          >
                            <Play size={12} /> Start
                          </button>
                          <button
                            className="btn btn--sm"
                            disabled={!vcamState?.host_running}
                            onClick={async () => {
                              try { await invoke('stop_virtual_camera_host'); addDiag('host', 'Virtual camera host stopped'); }
                              catch (e: any) { addDiag('host', `Host stop failed: ${e}`); }
                              invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
                            }}
                          >
                            <Square size={12} /> Stop
                          </button>
                        </div>
                      </div>
                    </Well>
                  )}
                </div>
              )}
            </Section>

            <Section legend="Signal chain" icon={<Activity size={13} />}>
              <SignalChain
                targetFps={Number(androidMetrics?.selectedFps || androidMetrics?.encodedFps || settings.fps)}
                transport={transport}
                androidRunning={androidRunning}
                androidMetrics={androidMetrics}
                metrics={metrics}
                producerRunning={producerRunning}
                consumerAttached={consumerAttached}
                streamMode={androidMetrics?.activeStreamMode || settings.streamMode}
              />
            </Section>

            <Section legend="Session" icon={<Cable size={13} />}>
              <Tel k="Phone" v={phoneInfo ? `${phoneInfo.manufacturer || ''} ${phoneInfo.model || ''}`.trim() || 'Connected' : '—'} />
              <Tel k="Lens" v={activeCam?.label || settings.cameraId} />
              <Tel
                k="Link"
                v={transport === 'USB' ? 'USB · adb forward' : 'Wi-Fi · bearer token'}
                tone={transport === 'USB' ? 'ready' : 'warn'}
              />
              <Tel
                k="Source"
                v={`${androidMetrics?.encodedWidth || settings.width}×${androidMetrics?.encodedHeight || settings.height} @ ${androidMetrics?.selectedFps || androidMetrics?.encodedFps || settings.fps} · ${(androidMetrics?.activeStreamMode || settings.streamMode).toUpperCase()}`}
              />
              <Tel
                k="Windows output"
                v={ring ? `${ring.negotiated_width}×${ring.negotiated_height} @ ${Math.round(ring.negotiated_fps_num / Math.max(1, ring.negotiated_fps_den))}` : `${settings.outputWidth}×${settings.outputHeight}`}
              />
              <Tel k="Device name" v="OpenCamBridge Camera" tone="muted" />
              <Tel k="Profile" v={settings.profile} tone="muted" />
            </Section>
          </div>
        )}

        {/* ================================================================
            OUTPUT — what the phone captures and what Windows receives
            ================================================================ */}
        {tab === 'output' && (
          <div className="stagger">
            <Section legend="Resolution & rate" icon={<Settings2 size={13} />}>
              <div className="field">
                <span className="field__label">Resolution</span>
                <select
                  className="input-control"
                  value={`${settings.width}x${settings.height}`}
                  onChange={(e) => {
                    const [w, h] = e.target.value.split('x').map(Number);
                    updateResolution(w, h);
                  }}
                >
                  {resolutionChoices.length === 0 && <option value="" disabled>No supported modes on this lens</option>}
                  {resolutionChoices.map((r: any) => (
                    <option key={`${r.width}x${r.height}`} value={`${r.width}x${r.height}`}>
                      {r.height === 1080 ? '1080p' : r.height === 720 ? '720p' : `${r.height}p`} ({r.width}×{r.height})
                    </option>
                  ))}
                </select>
              </div>

              <div className="field">
                <span className="field__label">
                  Frame rate
                  {androidMetrics?.actualFps != null && <b>{androidMetrics.actualFps} / {settings.fps} now</b>}
                </span>
                <select className="input-control" value={settings.fps} onChange={(e) => updateFps(parseInt(e.target.value, 10))}>
                  {fpsChoices.map(rate => <option key={rate} value={rate}>{rate} fps</option>)}
                  {fpsChoices.length === 0 && <option value="" disabled>No supported rate</option>}
                </select>
                <p className="hint">
                  Resolution and frame rate are validated together as one complete camera and encoder mode.
                  {maxFpsHere > 0
                    ? ` This lens reaches ${maxFpsHere} fps at ${settings.width}×${settings.height} on the selected path.`
                    : settings.streamMode === 'h264'
                      ? ' No hardware H.264 mode is available for this combination.'
                      : ' Actual rate depends on the phone camera and lighting.'}
                </p>
                {devMode && activeCam?.supportsHighSpeed && (
                  <p className="hint">
                    <strong>Diagnostics:</strong> this lens has constrained high-speed modes
                    {Array.isArray(activeCam.highSpeedFpsRanges) && activeCam.highSpeedFpsRanges.length > 0
                      ? ` up to ${Math.max(...activeCam.highSpeedFpsRanges.map((r: any) => r.max))} fps`
                      : ''}. H.264 may use a direct high-speed surface or the GPU bridge; MJPEG stays on the regular
                    ImageAnalysis capability.
                  </p>
                )}
              </div>

              {devMode && (
                <div className="field">
                  <span className="field__label">Capture profile (advanced)</span>
                  <select className="input-control" value={settings.profile} onChange={(e) => updateProfile(e.target.value)}>
                    <option value="low-latency">Low latency (1280×720, Q70)</option>
                    <option value="balanced">Balanced (1280×720, Q85)</option>
                    <option value="balanced-720p60">Balanced 60 (1280×720 @ 60, Q80)</option>
                    <option value="quality">Quality (1920×1080, Q75)</option>
                    <option value="experimental-1080p60">1080p @ 60</option>
                  </select>
                  <p className="hint">
                    Developer preset: sets resolution and quality together. Normal use goes through Resolution and
                    Frame rate above.
                  </p>
                </div>
              )}
            </Section>

            <Section legend="Codec" icon={<Cpu size={13} />}>
              <div className="field">
                <span className="field__label">Transport codec</span>
                <select className="input-control" value={settings.streamMode} onChange={(e) => updateSetting('streamMode', e.target.value)}>
                  <option value="h264">Hardware H.264 / OCB2 (recommended)</option>
                  <option value="mjpeg">MJPEG (compatibility)</option>
                </select>
                <p className="hint">
                  H.264 encodes straight off the Camera2 surface, frames it as OCB2, and decodes on Windows in
                  hardware. The app falls back to MJPEG only when that complete path is unavailable.
                </p>
              </div>

              {settings.streamMode === 'mjpeg' ? (
                <>
                  <div className="field">
                    <span className="field__label">JPEG quality <b>{settings.jpegQuality}%</b></span>
                    <input type="range" min="40" max="95" step="1" value={settings.jpegQuality}
                      onChange={(e) => updateSetting('jpegQuality', parseInt(e.target.value))} />
                  </div>
                  <div className="field">
                    <span className="field__label">
                      Target bandwidth
                      <b>{settings.targetBandwidthMbps === 0 ? 'off' : `${settings.targetBandwidthMbps} Mb/s`}</b>
                    </span>
                    <input type="range" min="0" max="50" step="1" value={settings.targetBandwidthMbps}
                      onChange={(e) => updateSetting('targetBandwidthMbps', parseInt(e.target.value))} />
                    <p className="hint">Zero disables automatic quality adjustment.</p>
                  </div>
                </>
              ) : (
                <>
                  <div className="field">
                    <span className="field__label">Bitrate <b>{(settings.h264Bitrate / 1_000_000).toFixed(0)} Mb/s</b></span>
                    <input type="range" min="1" max="20" step="1" value={Math.round(settings.h264Bitrate / 1_000_000)}
                      onChange={(e) => updateSetting('h264Bitrate', parseInt(e.target.value) * 1_000_000)} />
                    <p className="hint">
                      Applies live with no stream interruption. The phone raises very low requests to a
                      resolution-appropriate floor.
                    </p>
                  </div>
                  <div className="field">
                    <span className="field__label">Keyframe interval <b>{settings.h264KeyframeInterval}s</b></span>
                    <input type="range" min="1" max="10" step="1" value={settings.h264KeyframeInterval}
                      onChange={(e) => updateSetting('h264KeyframeInterval', parseInt(e.target.value))} />
                    <p className="hint">
                      Keyframes are requested on demand whenever a consumer connects or the stream is
                      interrupted, so this is only a safety net. Longer intervals spend more of the bitrate on
                      the picture and less on repeating full frames; shorter ones recover marginally faster if a
                      request is ever missed.
                    </p>
                  </div>
                </>
              )}
            </Section>

            <Section legend="Desktop" icon={<Monitor size={13} />}>
              <ToggleRow
                title="Desktop preview"
                note="Turn off to remove this window from the frame budget while diagnosing throughput."
                checked={!previewOff}
                onChange={(value) => setPreviewOff(!value)}
              />
              <ToggleRow
                title="Developer mode"
                note="Adds capture profiles, granular pipeline controls, and verbose telemetry."
                checked={devMode}
                onChange={setDevMode}
              />
            </Section>
          </div>
        )}

        {/* ================================================================
            IMAGE — everything that changes how the picture looks
            ================================================================ */}
        {tab === 'image' && (
          <div className="stagger">
            <Section legend="Lens" icon={<Aperture size={13} />}>
              <div className="field">
                <span className="field__label">Camera</span>
                <select className="input-control" value={settings.cameraId} onChange={(e) => updateSetting('cameraId', e.target.value)}>
                  {cameras.map(c => (
                    <option key={c.id} value={c.id}>
                      {c.label || `${c.facing?.charAt(0).toUpperCase()}${c.facing?.slice(1)} camera (${c.id})`}
                    </option>
                  ))}
                  {cameras.length === 0 && <option value="0">Default camera</option>}
                </select>
              </div>

              <div className="field">
                <span className="field__label">Zoom <b>{((settings.linearZoom || 0) * 100).toFixed(0)}%</b></span>
                <div className="fader-row">
                  <button className="btn" onClick={() => updateSetting('linearZoom', Math.max(0, (settings.linearZoom || 0) - 0.1))}>
                    <ZoomOut size={14} />
                  </button>
                  <input type="range" min="0" max="100" step="1" value={(settings.linearZoom || 0) * 100}
                    onChange={(e) => updateSetting('linearZoom', parseInt(e.target.value) / 100.0)} />
                  <button className="btn" onClick={() => updateSetting('linearZoom', Math.min(1.0, (settings.linearZoom || 0) + 0.1))}>
                    <ZoomIn size={14} />
                  </button>
                </div>
                {settings.linearZoom > 0 && (
                  <button className="btn btn--sm btn--block mt-8" onClick={() => updateSetting('linearZoom', 0.0)}>Reset zoom</button>
                )}
              </div>
            </Section>

            <Section legend="Framing" icon={<RotateCw size={13} />}>
              <div className="field">
                <span className="field__label">Rotation <b>{parseInt(settings.displayRotation, 10) || 0}°</b></span>
                <button className="btn btn--block" onClick={rotateOutput}>
                  <RotateCw size={14} /> Rotate 90°
                </button>
                <p className="hint">
                  Video is already uprighted for how the phone is held. This adds a further 90° offset.
                </p>
              </div>

              {devMode && (
                <div className="field">
                  <span className="field__label">Preview layout (developer)</span>
                  <select className="input-control" value={orientationMode} onChange={(e) => updateOrientationMode(e.target.value)}>
                    <option value="auto">Auto — follow phone</option>
                    <option value="16:9">Horizontal (16:9)</option>
                    <option value="9:16">Vertical (9:16)</option>
                  </select>
                  <p className="hint">
                    Shapes only this preview box. The virtual camera stays 16:9, so vertical video is pillarboxed
                    in the apps that consume it.
                  </p>
                </div>
              )}

              <div className="mt-14">
                <ToggleRow
                  title="Mirror image"
                  note="Flip horizontally, as a front camera normally previews."
                  checked={settings.mirror}
                  onChange={(value) => updateSetting('mirror', value)}
                />
                {torchSupported ? (
                  <ToggleRow
                    title={<span className="row"><Flashlight size={13} /> Torch</span>}
                    note="Hold the LED on for a dim room."
                    checked={settings.torchEnabled}
                    onChange={(value) => updateSetting('torchEnabled', value)}
                  />
                ) : activeCam ? (
                  <ToggleRow
                    title={<span className="row muted"><Flashlight size={13} /> Torch</span>}
                    right={<span className="micro">not on this lens</span>}
                  />
                ) : null}
              </div>
            </Section>

            {isSyncing && (
              <div className="section">
                <p className="hint row"><RefreshCw size={12} className="animate-spin" /> Syncing settings with the phone…</p>
              </div>
            )}
          </div>
        )}

        {/* ================================================================
            DIAG — every raw counter, still one click away
            ================================================================ */}
        {tab === 'diag' && (
          <div className="stagger">
            <Section legend="Events" icon={<Terminal size={13} />}>
              <div className="well">
                <div className="well__head">
                  <h4 className="legend"><Terminal size={12} /> Session log</h4>
                  <div className="well__head-actions">
                    <button className="btn btn--sm" onClick={copyDiagnostics}><Copy size={11} /> Copy</button>
                    <button className="btn btn--sm" onClick={() => { setDiagLog([]); lastDiagRef.current = {}; }}>
                      <Trash2 size={11} /> Clear
                    </button>
                  </div>
                </div>
                <div className="log">
                  {diagLog.length === 0
                    ? <span className="log__empty">No events yet.</span>
                    : diagLog.slice().reverse().map((line, i) => <div className="log__line" key={i}>{line}</div>)}
                </div>
              </div>
            </Section>

            <Section legend="Desktop preview stages" icon={<Monitor size={13} />}>
              <div className="tel-grid">
                <Tel k="Preview state" v={previewDiagnostics.ready ? 'READY — frame displayed' : 'WAITING — no displayed frame'} tone={previewDiagnostics.ready ? 'ready' : 'warn'} />
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
              </div>
              {previewDiagnostics.consumerStalled && (
                <Notice kind="fail" icon={<AlertTriangle size={13} />}>
                  Preview consumer is not releasing frames; producer ring is healthy.
                </Notice>
              )}
            </Section>

            {/* --- degradation warnings, gathered in one place --- */}
            {(androidMetrics || metrics) && (
              <Section legend="Warnings" icon={<AlertTriangle size={13} />}>
                <div className="stack stack--8">
                  {androidMetrics?.fallbackUsed && (
                    <Notice kind="warn" icon={<AlertTriangle size={13} />}>
                      Fallback resolution in use: {androidMetrics.resolutionPolicy}
                    </Notice>
                  )}
                  {(androidMetrics?.fallbackReason || metrics?.fallback_reason) && (
                    <Notice kind="warn" icon={<AlertTriangle size={13} />} title="Active fallback">
                      {androidMetrics?.fallbackReason || metrics?.fallback_reason}
                    </Notice>
                  )}
                  {settings.profile === 'native' && (
                    <Notice kind="fail" icon={<AlertTriangle size={13} />}>
                      Native mode is active: expect high CPU use and latency.
                    </Notice>
                  )}
                  {androidMetrics?.selectedEffectiveWidth > settings.width * 1.5 && (
                    <Notice kind="fail" icon={<AlertTriangle size={13} />} title="Source too large">
                      The profile degraded and selected {androidMetrics.selectedRawWidth}×{androidMetrics.selectedRawHeight}
                      {' '}instead of {settings.width}×{settings.height}.
                    </Notice>
                  )}
                  {metrics && metrics.decoded_fps < settings.fps - 5 && (
                    <Notice kind="warn" icon={<AlertTriangle size={13} />}>
                      Decoded rate is below target: {metrics.decoded_fps} of {settings.fps} fps.
                    </Notice>
                  )}
                  {metrics && metrics.source_width !== metrics.output_width && (
                    <Notice kind="warn" icon={<AlertTriangle size={13} />}>
                      Resize in the path: {metrics.source_width}×{metrics.source_height} → {metrics.output_width}×{metrics.output_height}.
                    </Notice>
                  )}
                  {settings.profile === 'experimental-1080p60' && settings.outputWidth === 1280 && (
                    <Notice kind="warn" icon={<AlertTriangle size={13} />}>
                      Capture requested 1080p60 but the virtual output is still 720p.
                    </Notice>
                  )}
                  {!androidMetrics?.fallbackUsed
                    && !(androidMetrics?.fallbackReason || metrics?.fallback_reason)
                    && settings.profile !== 'native'
                    && !(androidMetrics?.selectedEffectiveWidth > settings.width * 1.5)
                    && !(metrics && metrics.decoded_fps < settings.fps - 5)
                    && !(metrics && metrics.source_width !== metrics.output_width)
                    && <p className="hint">No degradation reported on the active path.</p>}
                </div>
              </Section>
            )}

            <Section legend="Throughput" icon={<Gauge size={13} />}>
              {metrics ? (
                <>
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
                    {metrics.source === 'ocb2-h264' && <Tel k="Phone→ring lower bound" v={`${metrics.phone_to_ring_latency_ms ?? metrics.latency_ms ?? 0} ms`} />}
                  </div>
                  <Tel
                    k="Decoder"
                    v={`${metrics.decoder_name || metrics.decode_backend || 'MJPEG'} · ${
                      metrics.hardware_decoder == null
                        ? `hardware unknown, D3D11 ${metrics.d3d11_output ? 'active' : 'inactive'}`
                        : metrics.hardware_decoder ? 'hardware' : 'software fallback'
                    }`}
                    tone={metrics.hardware_decoder === false ? 'warn' : undefined}
                  />
                  <Tel k="Pixel format" v={metrics.pixel_format} tone="muted" />
                  {metrics.source_width !== metrics.output_width && (
                    <Tel k="Resizing" v={`${metrics.source_width}×${metrics.source_height} → ${metrics.output_width}×${metrics.output_height}`} tone="warn" />
                  )}
                  {ring && (
                    <>
                      <Tel k="Playout buffer / target" v={`${ring.playout_buffer_depth_ms} / ${ring.playout_target_delay_ms} ms`} />
                      <Tel k="Playout underruns / late drops" v={`${ring.playout_underruns} / ${ring.playout_late_dropped}`} tone={ring.playout_underruns || ring.playout_late_dropped ? 'warn' : 'ready'} />
                    </>
                  )}
                  {vcamState?.last_metrics_time && (
                    <p className="hint" style={{ textAlign: 'right' }}>
                      Last update {Math.max(0, Math.floor(now - vcamState.last_metrics_time))}s ago
                    </p>
                  )}
                  {settings.profile === 'experimental-1080p60' && (
                    <Notice kind="info" icon={<Gauge size={13} />} title="1080p60 truth metrics">
                      Target 60 fps, actual {metrics.written_fps} fps —{' '}
                      {metrics.written_fps >= 55 ? 'viable' : metrics.written_fps >= 45 ? 'degraded' : 'not viable'}.
                      Phone capture {androidMetrics?.actualFps || 0} fps via {androidMetrics?.captureEngine || 'unknown'};
                      decode {metrics.decode_ms_avg} ms; IPC write {metrics.write_ms_avg} ms.
                    </Notice>
                  )}
                </>
              ) : (
                <p className="hint">Waiting for producer metrics…</p>
              )}
            </Section>

            <Section legend="Phone pipeline" icon={<Activity size={13} />}>
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
                    </>
                  )}
                  {androidMetrics.mjpeg && (
                    <>
                      <Tel k="MJPEG phone processing" v={`${Number(androidMetrics.androidEncodeMsAvg || 0).toFixed(1)} ms`} />
                      <Tel
                        k="MJPEG measured capacity"
                        v={`${androidMetrics.mjpegProcessingCapacityFps || 0} fps at Q${settings.jpegQuality}`}
                        tone={(androidMetrics.mjpegProcessingCapacityFps || 0) >= settings.fps ? 'ready' : 'warn'}
                      />
                    </>
                  )}
                </>
              ) : (
                <p className="hint">Waiting for phone metrics…</p>
              )}
            </Section>

            <Section legend="Processes" icon={<Cpu size={13} />}>
              <div className="stack stack--8">
                <div className="row"><Lamp state={androidRunning ? 'ok' : 'down'} /><span className="grow">Android control server</span>
                  <span className="micro">{androidRunning ? 'running' : 'stopped'}</span></div>
                <div className="row"><Lamp state={producerRunning ? 'ok' : 'down'} /><span className="grow">Frame producer</span>
                  <span className="micro">{vcamState?.producer_state || (producerRunning ? 'running' : 'stopped')}</span></div>
                <div className="row"><Lamp state={vcamState?.host_running ? 'ok' : 'down'} /><span className="grow">Virtual camera host</span>
                  <span className="micro">{vcamState?.host_running ? 'running' : 'stopped'}</span></div>
                <div className="row"><Lamp state={consumerAttached ? 'live' : 'idle'} /><span className="grow">Virtual camera consumer</span>
                  <span className="micro">{consumerAttached ? 'reading' : 'not attached'}</span></div>
              </div>
              <div className="mt-14">
                <Tel k="Producer PID" v={vcamState?.producer_pid || 'none'} />
                <Tel k="Producer binary present" v={vcamState?.producer_exists ? 'yes' : 'no'} tone={vcamState?.producer_exists ? 'ready' : 'fail'} />
                <Tel
                  k="Executable"
                  v={vcamState?.producer_path ? vcamState.producer_path.split('\\').pop() : 'unknown'}
                  tone="link"
                  title={vcamState?.producer_path ? `${vcamState.producer_path} — click to copy` : 'unknown'}
                  onClick={() => vcamState?.producer_path && navigator.clipboard.writeText(vcamState.producer_path)}
                />
                <Tel k="Last error" v={vcamState?.last_error || 'none'} tone={vcamState?.last_error ? 'fail' : 'muted'} />
                {vcamState?.last_event && <Tel k="Last producer event" v={vcamState.last_event} tone="muted" />}
              </div>
            </Section>

            {ring && (
              <Section legend="Ring & binaries" icon={<Layers size={13} />}>
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
                <p className="hint">
                  A mismatch between built, installed, registered, and loaded hashes means Windows is running a
                  different DLL than the one just built. Re-run <strong>dev-build-vcam.ps1</strong>.
                </p>
              </Section>
            )}

            <Section legend="OBS fallback" icon={<Video size={13} />}>
              <p className="hint" style={{ marginTop: 0 }}>
                Only needed when the native Windows camera is blocked — OBS can read this window or a browser
                source instead.
              </p>
              <div className="field mt-14">
                <span className="field__label">Mode</span>
                <select className="input-control" value={obsMode} onChange={(e) => setObsMode(e.target.value as 'browser' | 'window')}>
                  <option value="browser">Browser source (recommended)</option>
                  <option value="window">Window capture</option>
                </select>
              </div>
              <div className="field">
                <span className="field__label">OBS WebSocket password</span>
                <input type="password" className="input-control" placeholder="optional" value={obsPassword}
                  onChange={(e) => setObsPassword(e.target.value)} />
              </div>
              <button className="btn btn--block mt-14" onClick={handleStartObs} disabled={isObsConnecting}>
                {isObsConnecting ? <RefreshCw size={14} className="animate-spin" /> : <Monitor size={14} />}
                {isObsConnecting ? 'Connecting…' : 'Connect OBS WebSocket'}
              </button>

              {obsStatus && (
                <div className="mt-8">
                  <Notice kind={obsStatus.error ? 'fail' : 'info'} title={obsStatus.message}>
                    {obsStatus.error}
                  </Notice>
                </div>
              )}

              <button className="btn btn--block mt-14" onClick={onEnterObsMode}>
                <Monitor size={14} /> Enter clean feed
              </button>
              <p className="hint">Full-screen, chrome-free preview for manual window capture. Esc exits.</p>
            </Section>

            <Section legend="Reference" icon={<Sliders size={13} />}>
              <Tel k="Source generation" v={dash(androidMetrics?.generation)} tone="muted" />
              <Tel k="Lifecycle" v={dash(androidMetrics?.lifecycleState)} tone="muted" />
              <Tel k="Ring ABI hash" v={ring ? `0x${(ring.ring_abi_hash >>> 0).toString(16)}` : '—'} tone="muted" />
              <Tel k="Consumer PID" v={dash(ring?.consumer_pid)} tone="muted" />
            </Section>
          </div>
        )}
      </div>
    </div>
  );
}
