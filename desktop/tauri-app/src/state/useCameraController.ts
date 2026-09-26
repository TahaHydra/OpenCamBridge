import { useState, useEffect, useCallback, useRef } from 'react';
import { apiFetch, buildUrl } from '../services/api';
import { desktopInvoke as invoke } from '../services/desktopBridge';
import { logEvent, logError, logTestMarker } from '../services/logging';
import {
  EMPTY_PREVIEW_DIAGNOSTICS,
  PREVIEW_DIAGNOSTICS_EVENT,
  PREVIEW_FALLBACK_EVENT,
  type PreviewStageDiagnostics,
} from '../services/previewDiagnostics';
import {
  buildProducerLaunchSpec,
  buildSettingsMutation,
  describeMutationRejection,
  selectPipelineRestartScope,
  shouldStartH264PreviewProducer,
  shouldImportAuthoritativeState,
} from '../services/pipelineSyncPolicy.js';
import {
  buildProfileChange,
  describeSettingsChange,
  phoneProfileForResolution,
  profileAvailability,
  resolveProfile,
  type CaptureProfile,
} from '../services/profilePolicy.js';
import { normalizeAndroidMetrics } from './androidMetrics';
import { phoneStateFrom, virtualCameraPhase } from './status';
import type { CameraInfo, CameraMode, CameraSettings, PhoneInfo, VirtualCamState } from './types';

/**
 * The desktop's camera engine: phone settings sync, capability model, pipeline
 * orchestration (phone stream -> decoded producer -> Windows camera host) and
 * the diagnostics event log.
 *
 * This logic used to live inside the 2,000-line ControlPanel component, tangled
 * with its markup. It moved here essentially unchanged — same refs, same
 * ordering, same restart-scope policy — so every screen of the redesigned UI
 * drives exactly the same, already-validated behaviour. Screens only read the
 * returned state and call the returned actions.
 *
 * Deliberately NOT here: how the preview renders. The preview implementation
 * (Preview / Nv12RingPreview) stays isolated; this hook only decides when the
 * native preview decoder should run, exactly as before.
 */

type ProducerPurpose = 'preview' | 'feed' | 'webcam';

export interface CameraControllerOptions {
  baseUrl: string;
  token: string;
  /** Whether the desktop preview is decoding. Off keeps it out of the frame budget. */
  previewEnabled: boolean;
}

const INITIAL_SETTINGS: CameraSettings = {
  cameraId: '0',
  profile: 'adaptive',
  width: 1920,
  height: 1080,
  outputWidth: 1920,
  outputHeight: 1080,
  // Default to 30 fps: on many phones (e.g. OnePlus 9) 60 fps at 1080p/720p is
  // only reachable via a constrained high-speed session that crops the sensor
  // FOV (looks like a "lens switch") and AE-limits to ~15 fps in low light.
  // 30 fps uses the full-FOV regular session; 60 remains an explicit choice.
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
  h264BitrateMode: 'auto',
  h264KeyframeInterval: 5,
};

const newRequestId = () => globalThis.crypto?.randomUUID?.() || `tauri-${Date.now()}`;

export function useCameraController({ baseUrl, token, previewEnabled }: CameraControllerOptions) {
  const previewOff = !previewEnabled;
  // The WebCodecs preview reads the compressed stream itself. Only when it has
  // fallen back to the native compatibility renderer does preview need the
  // desktop producer (H264Preview announces that with PREVIEW_FALLBACK_EVENT).
  const [nativePreviewFallback, setNativePreviewFallback] = useState(false);
  useEffect(() => {
    const onFallback = (event: Event) => setNativePreviewFallback(Boolean((event as CustomEvent).detail));
    window.addEventListener(PREVIEW_FALLBACK_EVENT, onFallback);
    return () => window.removeEventListener(PREVIEW_FALLBACK_EVENT, onFallback);
  }, []);

  const [cameras, setCameras] = useState<CameraInfo[]>([]);
  const [settings, setSettings] = useState<CameraSettings>(INITIAL_SETTINGS);

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
  // Human summary of the change in flight ("Switching to 1080p30…").
  const [syncLabel, setSyncLabel] = useState('');
  // Mirror of isSyncing readable from the status-poll callback without adding it
  // as a dependency. While a local apply is in flight we must not let a stale
  // poll overwrite the user's just-made selection with the pre-change server
  // value (which would make the desktop controls appear to "snap back").
  const isSyncingRef = useRef(false);

  const [vcamState, setVcamState] = useState<VirtualCamState | null>(null);
  const [isVcamRegistering, setIsVcamRegistering] = useState(false);
  const [vcamMessage, setVcamMessage] = useState('');
  const [androidStreamStatus, setAndroidStreamStatus] = useState('unknown');
  const [androidMetrics, setAndroidMetrics] = useState<any>(null);
  const [phoneInfo, setPhoneInfo] = useState<PhoneInfo | null>(null);
  // The raw authoritative status, which the preview reads for lifecycle and
  // geometry. An unreachable server is marked OFFLINE rather than left stale.
  const [serverStatus, setServerStatus] = useState<any>(null);
  const [now, setNow] = useState(Date.now() / 1000);

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

  // Zoom is a slider: send only the newest value, one request at a time, and
  // keep polls from dragging the thumb back while a change is settling.
  const zoomInFlightRef = useRef(false);
  const zoomPendingRef = useRef<number | null>(null);
  const zoomSettleUntilRef = useRef(0);

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

  const clearDiagLog = useCallback(() => {
    setDiagLog([]);
    lastDiagRef.current = {};
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
  const activeCam: CameraInfo | undefined = cameras.find(c => c.id === settings.cameraId);
  // Torch: hide only when the active lens explicitly reports no flash. If the
  // field is absent (older phone build / capability unknown) keep it visible so
  // version skew never hides a working torch.
  const torchSupported = activeCam ? activeCam.hasTorch !== false : false;
  const h264Modes: CameraMode[] = Array.isArray(activeCam?.h264Modes) ? activeCam!.h264Modes! : [];
  const mjpegModes: CameraMode[] = Array.isArray(activeCam?.mjpegModes) ? activeCam!.mjpegModes! : [];
  const activeModes = settings.streamMode === 'h264' ? h264Modes : mjpegModes;
  const fpsCapFor = (w: number, h: number): number => {
    const modes = settings.streamMode === 'h264' ? h264Modes : mjpegModes;
    return Math.max(0, ...modes.filter(m => m.width === w && m.height === h).map(m => m.fps));
  };
  const maxFpsHere = fpsCapFor(settings.width, settings.height);
  const fpsChoices = Array.from(new Set(activeModes
    .filter(m => m.width === settings.width && m.height === settings.height)
    .map(m => Number(m.fps)))).sort((a, b) => a - b);
  const resolutionChoices = Array.from(new Map(activeModes.map(m =>
    [`${m.width}x${m.height}`, { width: m.width, height: m.height }])).values())
    .sort((a, b) => b.width * b.height - a.width * a.height);

  const importAuthoritativeState = useCallback((raw: any, force = false) => {
    const status = raw?.status || raw;
    if (!status) return;
    if (!force && !shouldImportAuthoritativeState(
      authoritativeRevisionRef.current, status.revision, isSyncingRef.current
    )) return;
    authoritativeRevisionRef.current = Number(status.revision ?? 0);
    settingsHydratedRef.current = true;
    const current = settingsRef.current;
    const zoomSettling = zoomInFlightRef.current || Date.now() < zoomSettleUntilRef.current;
    const next: CameraSettings = {
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
      linearZoom: zoomSettling ? current.linearZoom : status.linearZoom ?? current.linearZoom,
      targetBandwidthMbps: status.targetBandwidthMbps ?? current.targetBandwidthMbps,
      h264Bitrate: status.h264Bitrate ?? current.h264Bitrate,
      // The phone omits h264BitrateMode while it is "auto" (kotlinx does not
      // encode default values), so a full status that carries h264Bitrate but
      // no mode means Automatic.
      h264BitrateMode: status.h264BitrateMode === 'manual' || status.h264BitrateMode === 'auto'
        ? status.h264BitrateMode
        : status.h264Bitrate != null ? 'auto' : current.h264BitrateMode,
      // Mirror the phone's real interval, clamped to the range it accepts. It
      // used to be pinned to 5 here, and because every settings mutation posts
      // the interval, any other value chosen on the phone was silently reset
      // by the next unrelated desktop change.
      h264KeyframeInterval: Math.min(10, Math.max(1, status.h264KeyframeInterval ?? current.h264KeyframeInterval)),
    };
    settingsRef.current = next;
    setSettings(next);
  }, []);

  const fetchStatus = useCallback(() => {
    apiFetch(baseUrl, '/api/camera/status', token)
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => {
        const status = data.status || data;
        if (status) {
          setServerStatus(status);
          importAuthoritativeState(status);
        }
      })
      .catch(() => {
        setServerStatus((prev: any) => ({ ...prev, lifecycleState: 'OFFLINE', lastError: 'Server disconnected' }));
      });
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

  const refreshCapabilities = useCallback(() => {
    apiFetch(baseUrl, '/api/device/info', token)
      .then(res => res.json())
      .then(setPhoneInfo)
      .catch(() => setPhoneInfo(null));

    apiFetch(baseUrl, '/api/pipeline/capabilities', token)
      .then(res => res.json())
      .then(data => {
        const list: CameraInfo[] = Array.isArray(data) ? data : data.cameras || [];
        setCameras(list);
        // Prefer main/back-wide as the default lens (not telephoto/ultrawide),
        // but only when the current selection is not a real camera yet — never
        // override an explicit phone/user choice.
        const haveActive = list.some(c => c.id === settingsRef.current.cameraId);
        if (!haveActive && list.length) {
          const preferred =
            list.find(c => c.facing === 'back' && c.lensType === 'wide') ||
            list.find(c => c.facing === 'back') ||
            list[0];
          if (preferred) {
            setSettings(prev => ({ ...prev, cameraId: preferred.id }));
            addDiag('lens', `Default lens: ${preferred.label} (${preferred.id})`);
          }
        }
      })
      .catch(e => addDiag('cameraList', `Camera list fetch failed: ${e}`));
  }, [baseUrl, token, addDiag]);

  useEffect(() => {
    refreshCapabilities();
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
  }, [baseUrl, token, fetchStatus, refreshCapabilities]);

  // A phone that restarts its service may come back with different cameras
  // (e.g. a permission grant): refresh capabilities when it becomes reachable.
  const wasReachableRef = useRef(false);
  useEffect(() => {
    const reachable = androidStreamStatus === 'running';
    if (reachable && !wasReachableRef.current && cameras.length === 0) refreshCapabilities();
    wasReachableRef.current = reachable;
  }, [androidStreamStatus, cameras.length, refreshCapabilities]);

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

  const buildDiagnosticsSnapshot = () => {
    const s = settingsRef.current;
    const m = vcamState?.metrics;
    return [
      '=== OpenCamBridge diagnostics snapshot ===',
      `Connection: ${token ? 'LAN (token)' : 'USB'}  base=${baseUrl}`,
      `Android control server: ${androidStreamStatus}`,
      `Phone: ${phoneInfo ? `${phoneInfo.manufacturer || ''} ${phoneInfo.model || ''}`.trim() : 'unknown'}  app=${phoneInfo?.version || '?'}`,
      `Lens: ${activeCam ? `${activeCam.label} (${activeCam.id})` : s.cameraId}  torch=${torchSupported}`,
      `Resolution: ${s.width}x${s.height}  requested fps: ${s.fps}  maxFps@res: ${maxFpsHere || 'unknown'}`,
      `Codec: ${s.streamMode}  jpegQuality: ${s.jpegQuality}  targetBandwidth: ${s.targetBandwidthMbps || 'off'}  h264Bitrate: ${s.h264Bitrate}`,
      `Rotation: ${s.displayRotation}  mirror: ${s.mirror}  profile: ${s.profile}`,
      `Android FPS actual: ${androidMetrics?.actualFps ?? '?'}  encoded: ${androidMetrics?.encodedWidth}x${androidMetrics?.encodedHeight}`,
      m ? `Producer: transport ${m.transport_received_fps ?? m.transport_fps} / decoded ${m.producer_decoded_fps ?? m.decoded_fps} / ring ${m.ring_written_fps ?? m.written_fps} fps, configured source ${m.source_fps ?? m.fps_target}, ${m.transport_bandwidth_mbps ?? m.estimated_mbps} Mbps, processing ${m.producer_processing_ms ?? m.total_pipeline_ms}ms${m.source === 'mjpeg' ? `, JPEG dropped ${m.dropped_jpegs}, queue ${m.jpeg_queue_len}` : ''}` : 'Producer: not running',
      `Producer last error: ${vcamState?.last_error || m?.last_error || 'none'}`,
      `Virtual camera: registered=${vcamState?.registered} host=${vcamState?.host_running}/${vcamState?.host_activated} pipelineReady=${vcamState?.pipeline_ready} consumer=${vcamState?.virtual_camera_ready}`,
      `Desktop preview: ready=${previewDiagnostics.ready} ring=${previewDiagnostics.ringAlive} generation=${previewDiagnostics.streamGeneration} ringWrite=${previewDiagnostics.ringWriteSequence} receivedFps=${previewDiagnostics.previewReceivedFps} displayedFps=${previewDiagnostics.previewDisplayedFps} skipped=${previewDiagnostics.previewSkippedSequences} ipcMs=${previewDiagnostics.ipcTransferMs.toFixed(2)} uploadMs=${previewDiagnostics.previewUploadMs.toFixed(2)} colour=${previewDiagnostics.colorMatrix}/${previewDiagnostics.colorRange} parsed=${previewDiagnostics.parsedWidth}x${previewDiagnostics.parsedHeight} displayed=${previewDiagnostics.lastDisplayedSequence} error=${previewDiagnostics.lastError || 'none'}`,
      '=== event log ===',
      ...diagLog,
    ].join('\n');
  };

  const copyDiagnostics = async () => {
    try {
      await navigator.clipboard.writeText(buildDiagnosticsSnapshot());
      addDiag('copy', 'Diagnostics copied to clipboard');
      return true;
    } catch (e) {
      addDiag('copy', `Copy failed: ${e}`);
      return false;
    }
  };

  const refreshVcamState = async () => {
    try {
      const state = await invoke<VirtualCamState>('get_virtual_camera_status');
      vcamStateRef.current = state;
      setVcamState(state);
      return state;
    } catch {
      return null;
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

  const getBackendDetails = async (): Promise<string> => {
    try {
      return await invoke<string>('get_virtual_camera_backend_details');
    } catch (e: any) {
      return `Unavailable: ${e}`;
    }
  };

  const startStream = () => pipelineCommand('/api/stream/start');
  const stopStream = () => pipelineCommand('/api/stream/stop');

  const handleStartProducer = async (
    s: CameraSettings,
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

  // WebCodecs owns its own compressed stream. Only the compatibility preview
  // needs a native ring producer; preview never activates the webcam host.
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
      previewEnabled: !previewOff && nativePreviewFallback,
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
  }, [androidMetrics, previewOff, nativePreviewFallback, settings.fps, settings.streamMode, vcamState?.process_running, addDiag]);

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

  /** Starts the complete Windows camera: phone stream, decoded feed and MF host. */
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

  /**
   * Stops everything this app drives: decoded feed, Windows camera host AND
   * the phone's camera stream. The phone's control server stays alive, so the
   * camera can be started again from here.
   */
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

  /**
   * Unpublishes OpenCamBridge Camera but keeps the phone streaming, so the
   * desktop preview carries on. The preview decoder restarts on its own (for
   * H.264) because the auto-start policy sees the producer gone.
   */
  const stopVirtualCamera = async () => {
    try {
      await waitForPreviewProducerIdle();
      await invoke('stop_virtual_camera_feeder');
      producerPurposeRef.current = null;
      await invoke('stop_virtual_camera_host');
      addDiag('host', 'Virtual camera stopped; phone stream left running');
      setVcamMessage('');
      await refreshVcamState();
    } catch (e: any) {
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

  const startHostOnly = async () => {
    try { await invoke('start_virtual_camera_host'); setVcamMessage(''); addDiag('host', 'Virtual camera host started'); }
    catch (e: any) { setVcamMessage(`Virtual camera host failed: ${e}`); addDiag('host', `Host start failed: ${e}`); }
    invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
  };

  const stopHostOnly = async () => {
    try { await invoke('stop_virtual_camera_host'); addDiag('host', 'Virtual camera host stopped'); }
    catch (e: any) { addDiag('host', `Host stop failed: ${e}`); }
    invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
  };

  /** Restarts the phone camera while its control server is still running. */
  const startPhoneCamera = async () => {
    setVcamMessage('');
    try {
      previewAutoStartSuppressedRef.current = false;
      await startStream();
      addDiag('phone', 'Phone camera started from the desktop');
      fetchStatus();
    } catch (e: any) {
      setVcamMessage(`Could not start the phone camera: ${e.message || e}`);
    }
  };

  /** Asks the phone to rebuild a FAILED camera pipeline. */
  const recoverPhoneCamera = async () => {
    setVcamMessage('');
    try {
      await pipelineCommand('/api/stream/recover');
      addDiag('phone', 'Phone camera recovery requested');
      fetchStatus();
    } catch (e: any) {
      setVcamMessage(`Recovery failed: ${e.message || e}`);
    }
  };

  const postSettingsToAndroid = async (s: CameraSettings, keysChanged: string[]) => {
    if (keysChanged.length === 0) return null;
    if (!settingsHydratedRef.current || authoritativeRevisionRef.current == null) {
      throw new Error('Phone settings have not been loaded; refusing to post local defaults');
    }
    const patch: any = {};
    const directKeys: (keyof CameraSettings)[] = [
      'profile', 'width', 'height', 'outputWidth', 'outputHeight', 'fps', 'jpegQuality',
      'cameraId', 'aspectRatio', 'displayRotation', 'mirror', 'streamMode',
      'targetBandwidthMbps', 'h264Bitrate', 'h264BitrateMode', 'h264KeyframeInterval'
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
        newRequestId(),
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

  const applySettingsAndRefreshPreview = async (nextSettings: CameraSettings, keysChanged: string[]) => {
    setIsSyncing(true);
    isSyncingRef.current = true;
    setSyncLabel(describeSettingsChange(
      keysChanged, nextSettings, cameras.find(c => c.id === nextSettings.cameraId)?.label
    ));

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
      setSyncLabel('');
    }
  };

  const sendZoom = async (value: number): Promise<void> => {
    zoomInFlightRef.current = true;
    try {
      const res = await apiFetch(baseUrl, '/api/camera/zoom', token, {
        method: 'POST',
        body: JSON.stringify({
          linearZoom: value, baseRevision: authoritativeRevisionRef.current,
          requestId: newRequestId(), clientType: 'tauri'
        }),
        headers: { 'Content-Type': 'application/json' }
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        if (body.authoritativeState) importAuthoritativeState(body.authoritativeState, true); else fetchStatus();
        addDiag('zoom', `Zoom failed: HTTP ${res.status}`);
      } else if (body.revision != null) {
        authoritativeRevisionRef.current = Number(body.revision);
      }
    } catch (e: any) {
      addDiag('zoom', `Zoom request failed: ${e}`);
    } finally {
      zoomInFlightRef.current = false;
      zoomSettleUntilRef.current = Date.now() + 700;
      const pending = zoomPendingRef.current;
      zoomPendingRef.current = null;
      if (pending !== null && pending !== value) void sendZoom(pending);
    }
  };

  /** Live zoom: the thumb moves immediately, the phone receives the newest value. */
  const setZoom = (value: number) => {
    const clamped = Math.min(1, Math.max(0, Math.round(value * 100) / 100));
    const next = { ...settingsRef.current, linearZoom: clamped };
    settingsRef.current = next;
    setSettings(next);
    if (zoomInFlightRef.current) {
      zoomPendingRef.current = clamped;
      return;
    }
    void sendZoom(clamped);
  };

  const updateSetting = async <K extends keyof CameraSettings>(key: K, value: CameraSettings[K]) => {
    let newSettings: CameraSettings = { ...settingsRef.current, [key]: value };
    if (key === 'cameraId' || key === 'streamMode') {
      const camera = cameras.find(c => c.id === newSettings.cameraId);
      const modes = newSettings.streamMode === 'h264' ? camera?.h264Modes : camera?.mjpegModes;
      const canonical = Array.isArray(modes) ? modes : [];
      const selected = canonical.find(m =>
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
          requestId: newRequestId(), clientType: 'tauri'
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
      setZoom(Number(value));
      return;
    }

    await applySettingsAndRefreshPreview(
      newSettings,
      key === 'cameraId' || key === 'streamMode'
        ? [key, 'width', 'height', 'fps', 'outputWidth', 'outputHeight']
        : [key]
    );
  };

  const waitForAndroidResolution = async (s: CameraSettings, timeoutMs = 8000) => {
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
      `Check the log in the phone's Settings > Advanced for camera errors.`
    );
  };

  const restartAndroidStreamWithSettings = async (s: CameraSettings, keysChanged: string[]) => {
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
    s: CameraSettings,
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

  const restartFullPipelineWithSettings = async (s: CameraSettings, keysChanged: string[]) => {
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

  /**
   * Applies a capture profile — only when the selected lens reports that exact
   * mode. An unavailable profile is explained, never approximated. Capability-
   * aware profiles (Smooth Motion) are resolved for the selected lens here.
   */
  const applyProfile = async (requested: CaptureProfile): Promise<boolean> => {
    const profile = resolveProfile(requested, activeCam);
    const availability = profileAvailability(profile, activeCam);
    if (!availability.available) {
      setVcamMessage(availability.reason || `${profile.name} is not available on this camera.`);
      return false;
    }
    const { next, keys } = buildProfileChange(profile, settingsRef.current);
    if (keys.length === 0) return true;
    addDiag('profile', `Profile -> ${profile.name}`);
    logTestMarker('START', `profile "${profile.name}" ${next.streamMode} ${next.width}x${next.height}@${next.fps} lens=${next.cameraId}`);
    await applySettingsAndRefreshPreview(next, keys);
    return true;
  };

  // Resolution and FPS are independent knobs, not baked into profile names.
  // Picking a resolution selects a capture policy that permits that size on the
  // phone (via `profile`) but leaves the frame rate untouched where the new
  // size supports it, so any supported pair can be chosen.
  const updateResolution = async (w: number, h: number) => {
    const profile = phoneProfileForResolution(w);
    const validFps = activeModes.filter(m => m.width === w && m.height === h).map(m => Number(m.fps));
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
    const cur = parseInt(settingsRef.current.displayRotation, 10) || 0;
    const next = { ...settingsRef.current, displayRotation: ((cur + 90) % 360).toString() };
    addDiag('rotate', `Rotate -> ${next.displayRotation}°`);
    await applySettingsAndRefreshPreview(next, ['displayRotation']);
  };

  // ---- Derived product state -------------------------------------------------
  const androidRunning = androidStreamStatus === 'running';
  const producerRunning = !!vcamState?.process_running;
  const framesReady = !!vcamState?.pipeline_ready;
  const isLive = !!vcamState?.virtual_camera_ready;
  const consumerAttached = isLive || !!vcamState?.metrics?.ring?.consumer_attached;
  const binariesBlocked = !!(vcamState?.binary_identity && !vcamState.binary_identity.ready);
  const activeError = vcamState?.last_error || vcamState?.metrics?.last_error || '';
  const transport: 'USB' | 'LAN' = token ? 'LAN' : 'USB';
  const diagAlert: '' | 'warn' | 'fail' = binariesBlocked ? 'fail' : activeError || androidMetrics?.fallbackUsed ? 'warn' : '';
  const phoneState = phoneStateFrom(serverStatus);
  const virtualCamera = virtualCameraPhase(vcamState);

  return {
    // identity
    baseUrl,
    token,
    transport,
    // phone
    phoneInfo,
    phoneState,
    serverStatus,
    androidMetrics,
    androidRunning,
    cameras,
    activeCam,
    // settings
    settings,
    isSyncing,
    syncLabel,
    torchSupported,
    resolutionChoices,
    fpsChoices,
    maxFpsHere,
    orientationMode,
    // Windows side
    vcamState,
    virtualCamera,
    producerRunning,
    framesReady,
    isLive,
    consumerAttached,
    binariesBlocked,
    activeError,
    isVcamRegistering,
    // feedback
    message: vcamMessage,
    dismissMessage: () => setVcamMessage(''),
    diagLog,
    diagAlert,
    previewDiagnostics,
    now,
    // actions: camera
    updateSetting,
    updateResolution,
    updateFps,
    setZoom,
    rotateOutput,
    updateOrientationMode,
    applyProfile,
    refreshCapabilities,
    // actions: pipeline
    startVirtualCamera: handleStartNativeCamera,
    stopVirtualCamera,
    stopEverything: handleStopNativeCamera,
    startPhoneCamera,
    recoverPhoneCamera,
    startFeedOnly: handleStartFeedOnly,
    stopFeedOnly: handleStopFeedOnly,
    startHostOnly,
    stopHostOnly,
    registerVirtualCamera: handleRegisterVcam,
    unregisterVirtualCamera: handleUnregisterVcam,
    getBackendDetails,
    // actions: diagnostics
    copyDiagnostics,
    buildDiagnosticsSnapshot,
    clearDiagLog,
    addDiag,
  };
}

export type CameraController = ReturnType<typeof useCameraController>;
