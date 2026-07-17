import { useState, useEffect, useCallback, useRef } from 'react';
import { Play, Square, Settings2, Sliders, RefreshCw, RotateCw, ZoomIn, ZoomOut, Monitor, Video, ShieldAlert } from 'lucide-react';
import { connectAndSetupObs, ObsStatus } from '../services/obs';
import { apiFetch, buildUrl } from '../services/api';
import { logEvent, logError, logTestMarker } from '../services/logging';
import { invoke } from '@tauri-apps/api/core';

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
  http_jpeg_fps: number;
  decoded_fps: number;
  written_fps: number;
  transport_fps?: number;
  decoded_unique_fps?: number;
  virtual_camera_unique_fps?: number;
  repeated_samples?: number;
  dropped_jpegs: number;
  replaced_frames?: number;
  jpeg_queue_len: number;
  decode_ms_avg: number;
  rotate_ms_avg: number;
  resize_ms_avg: number;
  write_ms_avg: number;
  total_pipeline_ms: number;
  latency_ms?: number;
  bytes_per_sec: number;
  estimated_mbps: string;
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
    selectedFps: capture?.selectedFps ?? 0,
    cameraSessionFps: capture?.cameraSessionFps ?? 0,
    gpuBridgeFps: capture?.gpuBridgeFps,
    captureEngine: capture?.engine,
    encodedWidth: capture?.actualWidth ?? 0,
    encodedHeight: capture?.actualHeight ?? 0,
    encodedFps: h264?.encodedFps ?? mjpeg?.encodedFps ?? 0,
    encodedBitrate: h264?.bitrate ?? 0,
    encoderName: h264?.encoderName,
    hardwareEncoder: h264?.hardwareEncoder ?? false,
    androidEncodeMsAvg: mjpeg?.encodeMs,
    yuvMsAvg: mjpeg?.yuvMs,
    jpegMsAvg: mjpeg?.jpegMs,
    rotateMsAvg: mjpeg?.rotateMs,
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
    fps: 60,
    jpegQuality: 85,
    displayRotation: '0',
    aspectRatio: '16:9',
    mirror: false,
    torchEnabled: false,
    linearZoom: 0.0,
    streamMode: 'h264',
    targetBandwidthMbps: 0,
    h264Bitrate: 4000000,
    h264KeyframeInterval: 1
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
      jpegQuality: 90,
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

  // Developer mode only controls verbose diagnostics and presets. Codec choice
  // is a normal product setting because hardware H.264 is the V2 primary path.
  const [devMode, setDevMode] = useState<boolean>(() => localStorage.getItem('ocb.devMode') === '1');
  useEffect(() => { localStorage.setItem('ocb.devMode', devMode ? '1' : '0'); }, [devMode]);

  // Rolling diagnostics log surfaced in-app so runtime problems can be copied
  // without digging through the terminal. Capped to the most recent entries.
  const [diagLog, setDiagLog] = useState<string[]>([]);
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

  const importAuthoritativeState = useCallback((raw: any) => {
    const status = raw?.status || raw;
    if (!status) return;
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
      h264KeyframeInterval: status.h264KeyframeInterval ?? current.h264KeyframeInterval
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
          addDiag('fpsDrift', `FPS running low: ${out}/${m.fps_target} (camera AE/encode limited, not the PC)`);
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
        m ? `prodIn=${m.decoded_fps} prodOut=${m.written_fps}` : 'prod=off (producer not started — OBS is not receiving frames)',
        // Producer per-stage profiling (ms) + which optimized paths ran.
        m ? `prod[decode=${m.decode_ms_avg}(${m.decode_backend ?? '?'}) rot=${m.rotate_ms_avg} resize=${m.resize_ms_avg}(${m.resize_backend ?? '?'}) write=${m.write_ms_avg}]` : '',
        m ? `mbps=${m.estimated_mbps} lat=${m.total_pipeline_ms}ms drop=${m.dropped_jpegs} q=${m.jpeg_queue_len}` : '',
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
      m ? `Producer: in ${m.decoded_fps} / out ${m.written_fps} fps target ${m.fps_target}, ${m.estimated_mbps} Mbps, ${m.total_pipeline_ms}ms, dropped ${m.dropped_jpegs}, queue ${m.jpeg_queue_len}` : 'Producer: not running',
      `Producer last error: ${vcamState?.last_error || m?.last_error || 'none'}`,
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
      setVcamMessage(msg);
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
  const startStream = () => pipelineCommand('/api/stream/start');
  const stopStream = () => pipelineCommand('/api/stream/stop');

  const handleStartProducer = async (s: any, actual?: any) => {
    const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    const source = (actual?.activeStreamMode || s.streamMode) === 'h264' ? 'h264' : 'mjpeg';
    const targetUrl = source === 'h264' ? `${base}/stream.ocb2` : `${base}/stream.mjpeg`;
    // Android's selected/actual encoded tuple is the producer input. The
    // Windows output canvas remains an independent consumer setting.
    const sourceWidth = Number(actual?.encodedWidth || actual?.selectedEffectiveWidth || 0);
    const sourceHeight = Number(actual?.encodedHeight || actual?.selectedEffectiveHeight || 0);
    const sourceFps = Number(actual?.encodedFps || actual?.selectedFps || 0);
    if (sourceWidth <= 0 || sourceHeight <= 0 || sourceFps <= 0) {
      throw new Error('Android did not publish a valid selected/actual source tuple; refusing to launch the producer from desired defaults');
    }
    const outputWidth = Number(s.outputWidth || s.width);
    const outputHeight = Number(s.outputHeight || s.height);
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
    console.log('[Tauri UI] start_virtual_camera_feeder completed');
  };

  const handleStartNativeCamera = async () => {
    const s = settingsRef.current;

    setVcamMessage('Starting pipeline...');
    try {
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
      try { await invoke('stop_virtual_camera_feeder'); } catch {}
      try { setVcamState(await invoke<VirtualCamState>('get_virtual_camera_status')); } catch {}
    }
  };

  const handleStopNativeCamera = async () => {
    try {
      await invoke('stop_virtual_camera_feeder');
      await invoke('stop_virtual_camera_host');
      await stopStream();
      setVcamMessage('Stopped native pipeline.');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const handleStartFeedOnly = async () => {
    const s = settingsRef.current;

    setVcamMessage('Starting feed...');
    try {
      const actual = await restartAndroidStreamWithSettings(s, []);
      await handleStartProducer(s, actual);

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
      await invoke('stop_virtual_camera_feeder');
      await stopStream();
      setVcamMessage('Stopped feed.');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

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
    if (keysChanged.includes('width') || keysChanged.includes('height')) {
      patch.outputWidth = s.outputWidth;
      patch.outputHeight = s.outputHeight;
    }
    const response = await apiFetch(baseUrl, '/api/settings', token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        ...patch,
        baseRevision: authoritativeRevisionRef.current,
        requestId: globalThis.crypto?.randomUUID?.() || `tauri-${Date.now()}`,
        clientType: 'tauri',
      })
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.success === false) {
      if ((response.status === 409 || response.status === 422) && body.authoritativeState) {
        importAuthoritativeState(body.authoritativeState);
      } else {
        fetchStatus();
      }
      const requested = body.requested ? ` Requested: ${body.requested}.` : '';
      const alternatives = Array.isArray(body.alternatives) && body.alternatives.length
        ? ` Alternatives: ${body.alternatives.join(', ')}.` : '';
      throw new Error(`${body.message || `HTTP ${response.status}`}.${requested}${alternatives}`);
    }
    if (body.revision != null) authoritativeRevisionRef.current = Number(body.revision);
    if (body.authoritativeState) importAuthoritativeState(body.authoritativeState);
    return body;
  };

  const applySettingsAndRefreshPreview = async (nextSettings: any, keysChanged: string[]) => {
    setIsSyncing(true);
    isSyncingRef.current = true;

    try {
      // h264Bitrate and jpegQuality are intentionally absent: Android applies
      // both to the live pipeline without a rebind (bitrate via
      // MediaCodec.setParameters; JPEG quality is read per-frame). Restarting
      // the whole pipeline on every quality-slider step was the source of the
      // repeated producer restarts.
      const streamImpacting = ['profile', 'width', 'height', 'fps', 'cameraId', 'streamMode', 'h264KeyframeInterval', 'displayRotation', 'mirror'].some(k => keysChanged.includes(k));

      // Android streaming and the producer/virtual-camera are SEPARATE things.
      // The producer must never be started just because Android has frames.
      const producerRunning = !!(vcamState?.process_running ?? vcamState?.running);
      const androidStreaming =
        androidStreamStatus === 'running' &&
        (Number(androidMetrics?.encodedWidth || 0) > 0 ||
          Number(androidMetrics?.fps || 0) > 0 ||
          Number(androidMetrics?.latestFrameRevision || 0) > 0);

      if (streamImpacting && producerRunning) {
        // Producer is feeding OBS: a resolution/fps/lens/codec change needs both
        // the Android stream AND the producer restarted.
        addDiag('apply', `[${keysChanged.join(',')}] -> full pipeline restart (Android + producer)`);
        await restartFullPipelineWithSettings(nextSettings, keysChanged);
      } else if (streamImpacting && androidStreaming) {
        // Only the phone stream/preview is live (producer OFF). Rebind Android
        // alone — do NOT start the producer. Preview reconnects itself when
        // frames resume (metrics-based recovery in Preview).
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
          if (body.authoritativeState) importAuthoritativeState(body.authoritativeState); else fetchStatus();
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
          if (body.authoritativeState) importAuthoritativeState(body.authoritativeState); else fetchStatus();
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

  const restartFullPipelineWithSettings = async (s: any, keysChanged: string[]) => {
    // This path is only taken when the producer was actually running (see
    // applySettingsAndRefreshPreview using vcamState.running), so it MUST end
    // with the producer running again. Verify it and surface a real error if not
    // — a settings change must never silently leave OBS with prod=off.
    addDiag('pipeline', 'full restart: producerWasRunning=true');
    try { await invoke('stop_virtual_camera_feeder'); addDiag('pipeline', 'stopProducer ok'); }
    catch (e: any) { addDiag('pipeline', `stopProducer fail: ${e}`); }

    let actual: any = null;
    try { actual = await restartAndroidStreamWithSettings(s, keysChanged); addDiag('pipeline', `androidRebind ok (${actual?.activeStreamMode || s.streamMode})`); }
    catch (e: any) { addDiag('pipeline', `androidRebind fail: ${e}`); throw e; }

    await handleStartProducer(s, actual);
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

  return (
    <div className="control-panel glass-panel animate-fade" style={{ display: 'flex', flexDirection: 'column', gap: 24, padding: 24 }}>

      {/* NATIVE WINDOWS CAMERA SECTION */}
      <div className="control-group" style={{ background: 'rgba(30, 40, 50, 0.4)', borderRadius: 12, padding: 16, border: '1px solid rgba(100, 150, 255, 0.2)' }}>
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16, color: '#4dabf7' }}>
          <Monitor size={18} /> Native Windows Camera
        </h3>

        {/* Status Grid */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 16, fontSize: '0.85rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Camera Host:</span>
            <span style={{ color: vcamState?.host_running ? '#51cf66' : '#ff6b6b' }}>{vcamState?.host_running ? 'Running' : 'Stopped'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Android Stream:</span>
            <span style={{ color: androidStreamStatus === 'running' ? '#51cf66' : '#ff6b6b' }}>{androidStreamStatus === 'running' ? 'Running' : 'Stopped'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Producer:</span>
            <span style={{ color: vcamState?.process_running ? '#51cf66' : '#ff6b6b' }}>{vcamState?.process_running ? 'Running' : 'Stopped'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Profile:</span>
            <span style={{ color: '#fff', textTransform: 'capitalize' }}>{settings.profile}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Phone:</span>
            <span style={{ color: '#fff' }}>{phoneInfo ? `${phoneInfo.manufacturer || ''} ${phoneInfo.model || ''}`.trim() : 'Unknown'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Camera:</span>
            <span style={{ color: '#fff' }}>{activeCam?.label || settings.cameraId}</span>
          </div>
        </div>

        {/* Buttons */}
        {vcamState && !vcamState.registered ? (
          <div style={{ marginBottom: 16, background: 'rgba(255, 179, 0, 0.1)', padding: 12, borderRadius: 6, border: '1px solid rgba(255, 179, 0, 0.3)' }}>
            <p style={{ fontSize: '0.85rem', color: '#ffb300', marginBottom: 12 }}>
              <ShieldAlert size={14} style={{ display: 'inline', verticalAlign: 'text-bottom', marginRight: 4 }} />
              Camera COM Object is not registered.
            </p>
            <button className="btn btn-primary" style={{ width: '100%' }} onClick={handleRegisterVcam} disabled={isVcamRegistering}>
              {isVcamRegistering ? 'Registering...' : 'Register Camera Backend'}
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
            {/* Primary product control: ONE button starts the whole webcam
                (Android stream + virtual camera host + producer to OBS). */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              <button className="btn btn-primary" onClick={handleStartNativeCamera} disabled={!!(vcamState?.process_running && vcamState?.host_running)}>
                <Play size={16} style={{ marginRight: 6 }} /> Start Webcam
              </button>
              <button className="btn btn-secondary" onClick={handleStopNativeCamera} disabled={!vcamState?.process_running && !vcamState?.host_running}>
                <Square size={16} style={{ marginRight: 6 }} /> Stop Webcam
              </button>
            </div>
            <p style={{ fontSize: '0.75rem', color: vcamState?.pipeline_ready ? '#51cf66' : '#ffb300', margin: 0 }}>
              {vcamState?.virtual_camera_ready
                ? "OBS is consuming frames from 'OpenCamBridge Camera'."
                : vcamState?.pipeline_ready
                  ? "Frames are ready; waiting for a virtual-camera consumer such as OBS."
                  : vcamState?.process_running
                    ? `Producer is ${vcamState.producer_state || 'starting'}; pipeline is not ready yet.`
                    : 'Phone preview only — not sending to OBS. Press Start Webcam.'}
            </p>

            {/* Granular pipeline controls: developer mode only. */}
            {devMode && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 4, paddingTop: 8, borderTop: '1px solid rgba(255,255,255,0.05)' }}>
                <div style={{ fontSize: '0.72rem', color: '#666' }}>Developer: granular pipeline controls</div>
                <div>
                  <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: 6 }}>Phone stream only (feed):</div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                    <button className="btn btn-secondary" onClick={handleStartFeedOnly} disabled={vcamState?.process_running}><Play size={14} style={{ marginRight: 6 }} /> Start</button>
                    <button className="btn btn-secondary" onClick={handleStopFeedOnly} disabled={!vcamState?.process_running}><Square size={14} style={{ marginRight: 6 }} /> Stop</button>
                  </div>
                </div>
                <div>
                  <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: 6 }}>Virtual camera host:</div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                    <button className="btn btn-secondary" onClick={async () => {
                      try { await invoke('start_virtual_camera_host'); setVcamMessage(''); addDiag('host', 'Virtual camera host started'); }
                      catch (e: any) { setVcamMessage(`Virtual camera host failed: ${e}`); addDiag('host', `Host start failed: ${e}`); }
                      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
                    }} disabled={vcamState?.host_running}><Play size={14} style={{ marginRight: 6 }} /> Start</button>
                    <button className="btn btn-secondary" onClick={async () => {
                      try { await invoke('stop_virtual_camera_host'); addDiag('host', 'Virtual camera host stopped'); }
                      catch (e: any) { addDiag('host', `Host stop failed: ${e}`); }
                      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
                    }} disabled={!vcamState?.host_running}><Square size={14} style={{ marginRight: 6 }} /> Stop</button>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {vcamMessage && (
          <p style={{ fontSize: '0.8rem', color: '#aaa', marginBottom: 12, fontStyle: 'italic' }}>{vcamMessage}</p>
        )}

        {/* Compact product status (always visible) */}
        {vcamState && (
          <div style={{ background: 'rgba(20,25,30,0.5)', padding: 12, borderRadius: 8, border: '1px solid #222', fontSize: '0.8rem', marginBottom: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <span style={{ color: '#888' }}>Status</span>
              <span style={{ color: vcamState.pipeline_ready ? '#51cf66' : vcamState.process_running ? '#ffb300' : '#888' }}>
                {vcamState.virtual_camera_ready ? 'Consumer active' : vcamState.pipeline_ready ? 'Frames ready' : vcamState.process_running ? vcamState.producer_state || 'Starting' : 'Idle'}
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <span style={{ color: '#888' }}>Unique FPS (camera / encode / decode / camera)</span>
              <span style={{ color: (vcamState.metrics && (vcamState.metrics.virtual_camera_unique_fps ?? vcamState.metrics.written_fps) >= settings.fps - 5) ? '#51cf66' : '#ffb300' }}>
                {androidMetrics?.captureFps ?? androidMetrics?.actualFps ?? '—'} / {androidMetrics?.encodedFps ?? '—'} / {vcamState.metrics?.decoded_unique_fps ?? vcamState.metrics?.decoded_fps ?? '—'} / {vcamState.metrics?.virtual_camera_unique_fps ?? vcamState.metrics?.written_fps ?? '—'}
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ color: '#888' }}>Output</span>
              <span>{vcamState.metrics ? `${vcamState.metrics.output_width}x${vcamState.metrics.output_height} ${(androidMetrics?.activeStreamMode || settings.streamMode).toUpperCase()}` : `${settings.outputWidth}x${settings.outputHeight}`}</span>
            </div>
            {(vcamState.last_error || vcamState.metrics?.last_error) && (
              <div style={{ marginTop: 6, color: '#ff6b6b', fontSize: '0.75rem', wordBreak: 'break-all' }}>
                {vcamState.last_error || vcamState.metrics?.last_error}
              </div>
            )}
          </div>
        )}

        {/* Diagnostics log (always visible; copyable) */}
        <div style={{ background: '#0a0a0a', padding: 12, borderRadius: 8, border: '1px solid #222', marginBottom: 12 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <span style={{ color: '#4dabf7', fontSize: '0.85rem', fontWeight: 600 }}>Diagnostics</span>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn btn-secondary" style={{ padding: '2px 10px', fontSize: '0.7rem' }} onClick={copyDiagnostics}>Copy</button>
              <button className="btn btn-secondary" style={{ padding: '2px 10px', fontSize: '0.7rem' }} onClick={() => { setDiagLog([]); lastDiagRef.current = {}; }}>Clear</button>
            </div>
          </div>
          <div style={{ maxHeight: 140, overflowY: 'auto', fontFamily: 'monospace', fontSize: '0.68rem', color: '#9aa', lineHeight: 1.5 }}>
            {diagLog.length === 0
              ? <span style={{ color: '#555' }}>No events yet.</span>
              : diagLog.slice().reverse().map((l, i) => (<div key={i}>{l}</div>))}
          </div>
        </div>

        {/* Pipeline truth metrics are product diagnostics, not synthetic FPS. */}
        {vcamState && (
          <div style={{ background: '#0a0a0a', padding: 12, borderRadius: 8, border: '1px solid #222', fontFamily: 'monospace', fontSize: '0.75rem', color: '#51cf66' }}>

            {/* Extended Status */}
            <div style={{ marginBottom: 8, paddingBottom: 8, borderBottom: '1px solid #222' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888' }}>
                <span>Producer process:</span>
                <span style={{ color: vcamState.process_running ? '#51cf66' : '#ff6b6b' }}>{vcamState.process_running ? 'Running' : 'Stopped'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Producer state / pipeline:</span>
                <span style={{ color: vcamState.pipeline_ready ? '#51cf66' : '#ffb300' }}>{vcamState.producer_state || 'Unknown'} / {vcamState.pipeline_ready ? 'Ready' : 'Not ready'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Virtual-camera consumer:</span>
                <span style={{ color: vcamState.virtual_camera_ready ? '#51cf66' : '#ffb300' }}>{vcamState.virtual_camera_ready ? 'Reading frames' : 'Not attached'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Producer Exists:</span>
                <span style={{ color: vcamState.producer_exists ? '#51cf66' : '#ff6b6b' }}>{vcamState.producer_exists ? 'Yes' : 'No'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Producer PID:</span>
                <span style={{ color: '#fff' }}>{vcamState.producer_pid || 'None'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Executable:</span>
                <span
                  style={{ color: '#4dabf7', textDecoration: 'underline', cursor: 'pointer', textAlign: 'right', wordBreak: 'break-all', maxWidth: '70%' }}
                  onClick={() => vcamState.producer_path && navigator.clipboard.writeText(vcamState.producer_path)}
                  title={vcamState.producer_path ? `${vcamState.producer_path} (Click to copy)` : 'Unknown'}
                >
                  {vcamState.producer_path ? vcamState.producer_path.split('\\').pop() : 'Unknown'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#ff6b6b', marginTop: 4 }}>
                <span>Last Error:</span>
                <span style={{ textAlign: 'right', wordBreak: 'break-all', maxWidth: '70%' }}>{vcamState.last_error || 'None'}</span>
              </div>
              {vcamState.last_event && (
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Last producer event:</span>
                  <span style={{ textAlign: 'right', wordBreak: 'break-all', maxWidth: '70%' }}>{vcamState.last_event}</span>
                </div>
              )}
              {vcamState.metrics?.ring && (
                <>
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                    <span>Ring commits / reads / requests:</span>
                    <span>{vcamState.metrics.ring_frames_committed ?? 0} / {vcamState.metrics.ring.ring_read_successes} / {vcamState.metrics.ring.sample_requests}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                    <span>Ring validation / copy failures:</span>
                    <span style={{ color: (vcamState.metrics.ring.ring_validation_failures || vcamState.metrics.ring.sample_copy_failures) ? '#ff6b6b' : '#51cf66' }}>
                      {vcamState.metrics.ring.ring_validation_failures} / {vcamState.metrics.ring.sample_copy_failures} (0x{(vcamState.metrics.ring.last_ring_error >>> 0).toString(16)})
                    </span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                    <span>Negotiated media type:</span>
                    <span>{vcamState.metrics.ring.negotiated_width}x{vcamState.metrics.ring.negotiated_height} @ {vcamState.metrics.ring.negotiated_fps_num}/{vcamState.metrics.ring.negotiated_fps_den}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                    <span>VCam resize / source FPS:</span>
                    <span style={{ color: vcamState.metrics.ring.resize_backend === 'cpu-fallback' ? '#ffb300' : '#51cf66' }}>
                      {vcamState.metrics.ring.resize_backend || 'unknown'} ({vcamState.metrics.ring.resize_failures} GPU failures) / {vcamState.metrics.ring.source_fps_num}/{vcamState.metrics.ring.source_fps_den}
                    </span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                    <span>Producer / DLL hashes:</span>
                    <span title={`${vcamState.metrics.ring.producer_build_hash} / ${vcamState.metrics.ring.installed_dll_build_hash}`}>
                      {vcamState.metrics.ring.producer_build_hash.slice(0, 12) || 'unknown'} / {vcamState.metrics.ring.installed_dll_build_hash.slice(0, 12) || 'unknown'}
                    </span>
                  </div>
                </>
              )}
            </div>

            {androidMetrics && (
              <div style={{ marginBottom: 8, paddingBottom: 8, borderBottom: '1px solid #222' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Requested Aspect:</span>
                  <span>{androidMetrics.requestedAspectRatio}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Selected Aspect:</span>
                  <span style={{ color: androidMetrics.aspectRatioMatch ? '#51cf66' : '#ffb300' }}>{androidMetrics.selectedAspectRatio}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Source resolution desired / selected / actual:</span>
                  <span>{settings.width}x{settings.height} / {androidMetrics.selectedEffectiveWidth || 0}x{androidMetrics.selectedEffectiveHeight || 0} / {androidMetrics.encodedWidth}x{androidMetrics.encodedHeight}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Source mode desired / active:</span>
                  <span>{settings.streamMode.toUpperCase()} / {(androidMetrics.activeStreamMode || settings.streamMode).toUpperCase()}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Rotation Resizing:</span>
                  <span style={{ color: androidMetrics.resizeNeeded ? '#ffb300' : '#51cf66' }}>{androidMetrics.resizeNeeded ? 'Required' : 'Native Match'}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Source FPS desired / selected / encoded:</span>
                  <span style={{ color: (androidMetrics.encodedFps || 0) >= settings.fps - 5 ? '#51cf66' : '#ffb300' }}>
                    {settings.fps} / {androidMetrics.selectedFps || 0} / {androidMetrics.encodedFps || 0}
                  </span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Consumer output:</span>
                  <span>{vcamState.metrics?.ring?.negotiated_width || vcamState.metrics?.output_width || settings.outputWidth}x{vcamState.metrics?.ring?.negotiated_height || vcamState.metrics?.output_height || settings.outputHeight} @ {vcamState.metrics?.ring?.negotiated_fps_num || '—'}/{vcamState.metrics?.ring?.negotiated_fps_den || '—'}</span>
                </div>
                {androidMetrics.capture && (
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                    <span>Capture engine / session FPS:</span>
                    <span>{androidMetrics.captureEngine || 'Unknown'} / {androidMetrics.cameraSessionFps || 0}{androidMetrics.gpuBridgeFps != null ? ` → GPU ${androidMetrics.gpuBridgeFps}` : ''}</span>
                  </div>
                )}
                <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ color: '#888' }}>Phone preview:</span>
                  <span style={{ color: androidMetrics.phonePreviewRequested && !androidMetrics.phonePreviewActive ? '#ffb300' : '#51cf66' }}>
                    {!androidMetrics.phonePreviewRequested ? 'Not requested'
                      : androidMetrics.phonePreviewActive ? 'Active'
                      : `Inactive: ${androidMetrics.phonePreviewFailureReason || 'waiting for target/session'}`}
                  </span>
                </div>
                {androidMetrics.h264 && (
                  <>
                    <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                      <span>Encoder:</span>
                      <span>{androidMetrics.encoderName} ({androidMetrics.hardwareEncoder ? 'hardware' : 'software fallback'})</span>
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                      <span>Encoded FPS / bitrate:</span>
                      <span>{androidMetrics.encodedFps || 0} / {((androidMetrics.encodedBitrate || 0) / 1_000_000).toFixed(2)} Mbps</span>
                    </div>
                  </>
                )}
                {(androidMetrics.fallbackReason || vcamState.metrics?.fallback_reason) && (
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#ffb300', marginTop: 4 }}>
                    <span>Active fallback:</span>
                    <span>{androidMetrics.fallbackReason || vcamState.metrics?.fallback_reason}</span>
                  </div>
                )}
                {androidMetrics.mjpeg && (
                  <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                    <span>MJPEG encode time:</span>
                    <span>{Number(androidMetrics.androidEncodeMsAvg || 0).toFixed(1)} ms</span>
                  </div>
                )}

                {/* Degradation Warnings */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginTop: 8 }}>
                  {androidMetrics.fallbackUsed && (
                    <div style={{ padding: '4px 8px', background: 'rgba(255, 179, 0, 0.1)', color: '#ffb300', border: '1px solid rgba(255, 179, 0, 0.3)', borderRadius: 4, fontSize: '0.7rem' }}>
                      ⚠️ Fallback resolution used: {androidMetrics.resolutionPolicy}
                    </div>
                  )}
                  {settings.profile === 'native' && (
                    <div style={{ padding: '4px 8px', background: 'rgba(255, 107, 107, 0.1)', color: '#ff6b6b', border: '1px solid rgba(255, 107, 107, 0.3)', borderRadius: 4, fontSize: '0.7rem' }}>
                      ⚠️ Native mode active: High CPU & latency expected
                    </div>
                  )}
                  {androidMetrics.selectedEffectiveWidth > settings.width * 1.5 && (
                    <div style={{ padding: '4px 8px', background: 'rgba(255, 107, 107, 0.1)', color: '#ff6b6b', border: '1px solid rgba(255, 107, 107, 0.3)', borderRadius: 4, fontSize: '0.7rem' }}>
                      ⚠️ Source too large: Profile degraded, selected {androidMetrics.selectedRawWidth}x{androidMetrics.selectedRawHeight} instead of {settings.width}x{settings.height}
                    </div>
                  )}
                  {vcamState?.metrics && vcamState.metrics.decoded_fps < settings.fps - 5 && (
                    <div style={{ padding: '4px 8px', background: 'rgba(255, 179, 0, 0.1)', color: '#ffb300', border: '1px solid rgba(255, 179, 0, 0.3)', borderRadius: 4, fontSize: '0.7rem' }}>
                      ⚠️ FPS below target: {vcamState.metrics.decoded_fps} / {settings.fps}
                    </div>
                  )}
                  {vcamState?.metrics && vcamState.metrics.source_width !== vcamState.metrics.output_width && (
                    <div style={{ padding: '4px 8px', background: 'rgba(255, 179, 0, 0.1)', color: '#ffb300', border: '1px solid rgba(255, 179, 0, 0.3)', borderRadius: 4, fontSize: '0.7rem' }}>
                      ⚠️ Heavy resize: {vcamState.metrics.source_width}x{vcamState.metrics.source_height} &rarr; {vcamState.metrics.output_width}x{vcamState.metrics.output_height}
                    </div>
                  )}
                  {settings.profile === 'experimental-1080p60' && settings.outputWidth === 1280 && (
                    <div style={{ padding: '4px 8px', background: 'rgba(255, 179, 0, 0.1)', color: '#ffb300', border: '1px solid rgba(255, 179, 0, 0.3)', borderRadius: 4, fontSize: '0.7rem' }}>
                      ⚠️ Virtual output mismatch: Capture requested 1080p60, virtual output currently 720p.
                    </div>
                  )}
                  {settings.profile === 'experimental-1080p60' && vcamState?.metrics && (
                    <div style={{ padding: '8px', background: 'rgba(77, 171, 247, 0.1)', color: '#4dabf7', border: '1px solid rgba(77, 171, 247, 0.3)', borderRadius: 4, fontSize: '0.75rem', marginTop: 4 }}>
                      <div style={{ fontWeight: 'bold', marginBottom: 4 }}>1080p60 Truth Metrics:</div>
                      <div>Target: 60 FPS | Actual: {vcamState.metrics.written_fps} FPS</div>
                      <div>
                        Status: {
                          vcamState.metrics.written_fps >= 55 ? <span style={{ color: '#51cf66' }}>OK</span> :
                          vcamState.metrics.written_fps >= 45 ? <span style={{ color: '#ffb300' }}>Degraded</span> :
                          <span style={{ color: '#ff6b6b' }}>Not Viable</span>
                        }
                      </div>
                      <div style={{ marginTop: 4, fontStyle: 'italic', color: '#888' }}>
                        Bottleneck Analysis:
                        <ul style={{ margin: '2px 0 0 16px', padding: 0 }}>
                          <li>Android Capture: {androidMetrics.actualFps || 0} FPS via {androidMetrics.captureEngine || 'unknown'}</li>
                          <li>Rust Decode: {vcamState.metrics.decode_ms_avg} ms</li>
                          <li>IPC Write: {vcamState.metrics.write_ms_avg} ms</li>
                        </ul>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )}

            {vcamState.metrics ? (
              <>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Transport FPS:</span>
                    <span>{vcamState.metrics.transport_fps ?? vcamState.metrics.http_jpeg_fps}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Decoded unique FPS:</span>
                    <span>{vcamState.metrics.decoded_unique_fps ?? vcamState.metrics.decoded_fps}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Virtual-camera unique FPS:</span>
                    <span>{vcamState.metrics.virtual_camera_unique_fps ?? vcamState.metrics.written_fps}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Repeated samples:</span>
                    <span>{vcamState.metrics.repeated_samples ?? 0}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>End-to-end latency:</span>
                    <span>{vcamState.metrics.latency_ms ?? vcamState.metrics.total_pipeline_ms} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Bandwidth:</span>
                    <span style={{ color: '#4dabf7' }}>{vcamState.metrics.estimated_mbps} Mbps</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Decoder:</span>
                    <span>{vcamState.metrics.decoder_name || vcamState.metrics.decode_backend || 'MJPEG'} (
                      {vcamState.metrics.hardware_decoder == null
                        ? `hardware unknown; D3D11 output ${vcamState.metrics.d3d11_output ? 'active' : 'inactive'}`
                        : vcamState.metrics.hardware_decoder ? 'hardware' : 'software fallback'})</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Replaced / dropped:</span>
                    <span>{vcamState.metrics.replaced_frames ?? 0} / {vcamState.metrics.dropped_jpegs}</span>
                  </div>
                </div>
                {vcamState.metrics.source_width !== vcamState.metrics.output_width && (
                  <div style={{ marginTop: 8, paddingTop: 8, borderTop: '1px solid #222', display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Resizing:</span>
                    <span>{vcamState.metrics.source_width}x{vcamState.metrics.source_height} &rarr; {vcamState.metrics.output_width}x{vcamState.metrics.output_height}</span>
                  </div>
                )}
                {vcamState.last_metrics_time && (
                  <div style={{ marginTop: 8, fontSize: '0.65rem', color: (now - vcamState.last_metrics_time > 3) ? '#ffb300' : '#444', textAlign: 'right' }}>
                    Last update: {Math.max(0, Math.floor(now - vcamState.last_metrics_time))}s ago
                  </div>
                )}
              </>
            ) : (
              <div style={{ color: '#888', textAlign: 'center', padding: '8px 0' }}>Waiting for metrics...</div>
            )}
          </div>
        )}
      </div>

      <div className="control-group">
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          <Settings2 size={16} /> Resolution &amp; Frame Rate
        </h3>

        <div className="control-item">
          <label>Resolution</label>
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
                {r.height === 1080 ? '1080p' : r.height === 720 ? '720p' : `${r.height}p`} ({r.width}x{r.height})
              </option>
            ))}
          </select>
        </div>

        <div className="control-item" style={{ marginTop: 12 }}>
          <label>Frame Rate</label>
          <select
            className="input-control"
            value={settings.fps}
            onChange={(e) => updateFps(parseInt(e.target.value, 10))}
          >
            {fpsChoices.map(rate => <option key={rate} value={rate}>{rate} fps</option>)}
            {fpsChoices.length === 0 && <option value="" disabled>No supported rate</option>}
          </select>
          <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
            Resolution and frame rate are validated as one complete camera/encoder mode.
            {maxFpsHere > 0
              ? ` This lens supports up to ${maxFpsHere} fps at ${settings.width}x${settings.height} on the selected path.`
              : settings.streamMode === 'h264' ? ' No hardware H.264 mode is available for this combination.' : ' Actual rate depends on the phone camera and lighting.'}
            {androidMetrics?.actualFps != null &&
              ` Delivering ${androidMetrics.actualFps}/${settings.fps} fps now.`}
          </p>
          {devMode && activeCam?.supportsHighSpeed && (
            <p style={{ fontSize: '0.68rem', color: '#ffb300', marginTop: 4 }}>
              Diagnostics: this lens has constrained high-speed modes
              {Array.isArray(activeCam.highSpeedFpsRanges) && activeCam.highSpeedFpsRanges.length > 0
                ? ` up to ${Math.max(...activeCam.highSpeedFpsRanges.map((r: any) => r.max))} fps`
                : ''}. H.264 may use a direct high-speed surface or the GPU bridge;
              MJPEG remains limited to the regular ImageAnalysis capability.
            </p>
          )}
        </div>

        {devMode && (
          <div className="control-item" style={{ marginTop: 12 }}>
            <label>Capture Profile (advanced)</label>
            <select
              className="input-control"
              value={settings.profile}
              onChange={(e) => updateProfile(e.target.value)}
            >
              <option value="low-latency">Low Latency (1280x720, Q70)</option>
              <option value="balanced">Balanced (1280x720, Q85)</option>
              <option value="balanced-720p60">Balanced 60 (1280x720 @ 60fps, Q80)</option>
              <option value="quality">Quality (1920x1080, Q90)</option>
              <option value="experimental-1080p60">1080p @ 60fps</option>
            </select>
            <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
              Developer preset: sets resolution + quality (+fps for "60" presets)
              together. Normal users use the Resolution and Frame Rate controls.
            </p>
          </div>
        )}
      </div>

      <div className="control-group">
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          <Sliders size={16} /> Image Controls
        </h3>

        <div className="control-item">
          <label>Camera Lens</label>
          <select
            className="input-control"
            value={settings.cameraId}
            onChange={(e) => updateSetting('cameraId', e.target.value)}
          >
            {cameras.map(c => (
              <option key={c.id} value={c.id}>
                {c.label || `${c.facing?.charAt(0).toUpperCase() + c.facing?.slice(1)} Camera (${c.id})`}
              </option>
            ))}
            {cameras.length === 0 && <option value="0">Default Camera</option>}
          </select>
        </div>

        <div className="control-item">
          <label style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span>Zoom Level</span>
            <span>{((settings.linearZoom || 0) * 100).toFixed(0)}%</span>
          </label>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 4 }}>
            <button className="btn btn-secondary" style={{ padding: '6px' }} onClick={() => updateSetting('linearZoom', Math.max(0, (settings.linearZoom || 0) - 0.1))}>
              <ZoomOut size={16} />
            </button>
            <input
              type="range"
              min="0" max="100" step="1"
              style={{ flex: 1 }}
              value={((settings.linearZoom || 0) * 100)}
              onChange={(e) => updateSetting('linearZoom', parseInt(e.target.value) / 100.0)}
            />
            <button className="btn btn-secondary" style={{ padding: '6px' }} onClick={() => updateSetting('linearZoom', Math.min(1.0, (settings.linearZoom || 0) + 0.1))}>
              <ZoomIn size={16} />
            </button>
          </div>
          {settings.linearZoom > 0 && (
            <button className="btn btn-secondary" style={{ width: '100%', marginTop: 8 }} onClick={() => updateSetting('linearZoom', 0.0)}>
              Reset Zoom
            </button>
          )}
        </div>

        <div className="control-item">
            <label>Stream Codec</label>
            <select
              className="input-control"
              value={settings.streamMode}
              onChange={(e) => updateSetting('streamMode', e.target.value)}
            >
              <option value="h264">Hardware H.264 / OCB2 (Recommended)</option>
              <option value="mjpeg">MJPEG (Compatibility)</option>
            </select>
            <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
              H.264 uses Camera2 surface encoding, OCB2 framing and Windows hardware decoding. The app falls back to MJPEG when that complete path is unavailable.
            </p>
          </div>

        {settings.streamMode === 'mjpeg' ? (
          <>
            <div className="control-item" style={{ marginTop: 12 }}>
              <label style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span>JPEG Quality</span>
                <span>{settings.jpegQuality}%</span>
              </label>
              <input
                type="range"
                min="40" max="95" step="1"
                style={{ width: '100%', marginTop: 8 }}
                value={settings.jpegQuality}
                onChange={(e) => updateSetting('jpegQuality', parseInt(e.target.value))}
              />
            </div>

            <div className="control-item" style={{ marginTop: 12 }}>
              <label style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span>Target Bandwidth (Auto Quality)</span>
                <span>{settings.targetBandwidthMbps === 0 ? 'Off' : `${settings.targetBandwidthMbps} Mbps`}</span>
              </label>
              <input
                type="range"
                min="0" max="50" step="1"
                style={{ width: '100%', marginTop: 8 }}
                value={settings.targetBandwidthMbps}
                onChange={(e) => updateSetting('targetBandwidthMbps', parseInt(e.target.value))}
              />
              <p style={{ fontSize: '0.75rem', color: '#888', marginTop: 4 }}>Set to 0 to disable automatic quality adjustment.</p>
            </div>
          </>
        ) : (
          <div style={{ marginTop: 12, padding: 12, background: 'rgba(255, 179, 0, 0.06)', borderRadius: 6, border: '1px solid rgba(255, 179, 0, 0.25)' }}>
            <p style={{ fontSize: '0.75rem', color: '#4dabf7', marginBottom: 12 }}>
              Hardware H.264 is the low-latency V2 path. Disable the desktop preview if the phone cannot run a simultaneous preview surface at the selected rate.
            </p>
            <div className="control-item">
              <label style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span>Bitrate</span>
                <span>{(settings.h264Bitrate / 1_000_000).toFixed(0)} Mbps</span>
              </label>
              <input
                type="range"
                min="1" max="20" step="1"
                style={{ width: '100%', marginTop: 8 }}
                value={Math.round(settings.h264Bitrate / 1_000_000)}
                onChange={(e) => updateSetting('h264Bitrate', parseInt(e.target.value) * 1_000_000)}
              />
              <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
                Applies live — no stream interruption.
              </p>
            </div>
            <div className="control-item" style={{ marginTop: 12 }}>
              <label style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span>Keyframe Interval</span>
                <span>{settings.h264KeyframeInterval}s</span>
              </label>
              <input
                type="range"
                min="1" max="1" step="1" disabled
                style={{ width: '100%', marginTop: 8 }}
                value={settings.h264KeyframeInterval}
                onChange={(e) => updateSetting('h264KeyframeInterval', parseInt(e.target.value))}
              />
              <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
                Fixed at one second for bounded webcam recovery latency.
              </p>
            </div>
          </div>
        )}

        <div className="control-row" style={{ marginTop: 20 }}>
          <label style={{ fontSize: '0.9rem' }}>Desktop Preview Enabled</label>
          <label className="switch">
            <input
              type="checkbox"
              checked={!previewOff}
              onChange={(e) => setPreviewOff(!e.target.checked)}
            />
            <span className="slider"></span>
          </label>
        </div>

        <div className="control-row" style={{ marginTop: 12 }}>
          <label style={{ fontSize: '0.9rem' }}>
            Developer / Experimental Mode
            <span style={{ display: 'block', fontSize: '0.7rem', color: '#888' }}>
              Shows H.264 codec, capture profiles, and verbose metrics.
            </span>
          </label>
          <label className="switch">
            <input
              type="checkbox"
              checked={devMode}
              onChange={(e) => setDevMode(e.target.checked)}
            />
            <span className="slider"></span>
          </label>
        </div>

        <div className="control-item" style={{ marginTop: 20 }}>
          <label>Rotate</label>
          <button className="btn btn-secondary" style={{ width: '100%' }} onClick={rotateOutput}>
            <RotateCw size={16} style={{ marginRight: 6 }} /> Rotate 90°  (now {parseInt(settings.displayRotation, 10) || 0}°)
          </button>
          <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
            Video is auto-uprighted for how the phone is held; this adds a 90° offset.
          </p>
        </div>

        {devMode && (
          <div className="control-item" style={{ marginTop: 16 }}>
            <label>Preview layout (developer)</label>
            <select
              className="input-control"
              value={orientationMode}
              onChange={(e) => updateOrientationMode(e.target.value)}
            >
              <option value="auto">Auto (follow phone)</option>
              <option value="16:9">Horizontal (16:9)</option>
              <option value="9:16">Vertical (9:16)</option>
            </select>
            <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
              Shapes only THIS preview box. The virtual camera apps receive stays
              16:9 — vertical video shows there with side bars.
            </p>
          </div>
        )}

        <div className="control-row" style={{ marginTop: 20 }}>
          <label style={{ fontSize: '0.9rem' }}>Mirror Image</label>
          <label className="switch">
            <input
              type="checkbox"
              checked={settings.mirror}
              onChange={(e) => updateSetting('mirror', e.target.checked)}
            />
            <span className="slider"></span>
          </label>
        </div>

        {torchSupported ? (
          <div className="control-row" style={{ marginTop: 12 }}>
            <label style={{ fontSize: '0.9rem' }}>Flashlight (Torch)</label>
            <label className="switch">
              <input
                type="checkbox"
                checked={settings.torchEnabled}
                onChange={(e) => updateSetting('torchEnabled', e.target.checked)}
              />
              <span className="slider"></span>
            </label>
          </div>
        ) : activeCam ? (
          <div className="control-row" style={{ marginTop: 12 }}>
            <label style={{ fontSize: '0.9rem', color: '#888' }}>Flashlight (Torch)</label>
            <span style={{ fontSize: '0.75rem', color: '#888' }}>Not available on this lens</span>
          </div>
        ) : null}

        {isSyncing && (
          <div style={{ marginTop: 16, fontSize: '0.8rem', color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center' }}>
            <RefreshCw size={12} className="animate-spin" /> Syncing settings...
          </div>
        )}
      </div>

      {/* OBS FALLBACK SECTION */}
      <div style={{ borderTop: '1px solid var(--surface-border)', paddingTop: 16 }}>
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12, color: '#aaa' }}>
          <Video size={16} /> OBS Fallback Mode
        </h3>

        <div className="control-item">
          <select
            className="input-control"
            value={obsMode}
            onChange={(e) => setObsMode(e.target.value as 'browser' | 'window')}
            style={{ marginBottom: 8, width: '100%', cursor: 'pointer' }}
          >
            <option value="browser">Mode: Browser Source (Recommended)</option>
            <option value="window">Mode: Window Capture</option>
          </select>
          <input
            type="password"
            className="input-control"
            placeholder="OBS WebSocket Password (optional)"
            value={obsPassword}
            onChange={(e) => setObsPassword(e.target.value)}
            style={{ marginBottom: 8 }}
          />
          <button className="btn btn-secondary" style={{ width: '100%', display: 'flex', justifyContent: 'center', gap: 8 }} onClick={handleStartObs} disabled={isObsConnecting}>
            {isObsConnecting ? <RefreshCw size={16} className="animate-spin" /> : <Monitor size={16} />}
            {isObsConnecting ? 'Connecting...' : 'Start OBS WebSocket Integration'}
          </button>
        </div>

        {obsStatus && (
          <div style={{ marginTop: 12, padding: 12, background: obsStatus.error ? 'rgba(255,50,50,0.1)' : 'rgba(50,255,50,0.1)', borderRadius: 6, border: `1px solid ${obsStatus.error ? 'rgba(255,50,50,0.3)' : 'rgba(50,255,50,0.3)'}` }}>
            <strong style={{ display: 'block', fontSize: '0.85rem', color: obsStatus.error ? '#ff6b6b' : '#51cf66' }}>{obsStatus.message}</strong>
            {obsStatus.error && <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: 4, lineHeight: 1.4 }}>{obsStatus.error}</p>}
          </div>
        )}

        <div style={{ marginTop: 16, borderTop: '1px solid var(--surface-border)', paddingTop: 16 }}>
          <button className="btn" style={{ width: '100%', background: 'var(--surface-light)', color: 'var(--text-primary)', display: 'flex', justifyContent: 'center', gap: 8 }} onClick={onEnterObsMode}>
            Enter Clean Feed (Manual Mode)
          </button>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textAlign: 'center', marginTop: 8, lineHeight: 1.4 }}>
            Only needed if Native Camera is blocked.
          </p>
        </div>
      </div>
    </div>
  );
}
