import { useState, useEffect, useCallback, useRef } from 'react';
import { Play, Square, Settings2, Sliders, RefreshCw, ZoomIn, ZoomOut, Monitor, Video, ShieldAlert } from 'lucide-react';
import { connectAndSetupObs, ObsStatus } from '../services/obs';
import { apiFetch, buildUrl } from '../services/api';
import { logEvent, logError, logTestMarker } from '../services/logging';
import { invoke } from '@tauri-apps/api/core';

interface VirtualCamMetrics {
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
  dropped_jpegs: number;
  jpeg_queue_len: number;
  decode_ms_avg: number;
  rotate_ms_avg: number;
  resize_ms_avg: number;
  write_ms_avg: number;
  total_pipeline_ms: number;
  bytes_per_sec: number;
  estimated_mbps: string;
  pixel_format: string;
  last_error: string | null;
}

interface VirtualCamState {
  running: boolean;
  host_running: boolean;
  registered: boolean;
  metrics: VirtualCamMetrics | null;
  producer_path?: string;
  producer_exists?: boolean;
  producer_pid?: number;
  last_error?: string;
  last_metrics_time?: number;
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

export default function ControlPanel({ baseUrl, token, fitMode, onEnterObsMode, previewOff, setPreviewOff }: ControlPanelProps) {
  const [cameras, setCameras] = useState<any[]>([]);
  const [settings, setSettings] = useState({
    cameraId: '0',
    profile: 'balanced',
    width: 1280,
    height: 720,
    outputWidth: 1280,
    outputHeight: 720,
    fps: 30,
    jpegQuality: 85,
    displayRotation: '0',
    aspectRatio: '16:9',
    mirror: false,
    torchEnabled: false,
    linearZoom: 0.0,
    streamMode: 'mjpeg',
    targetBandwidthMbps: 0,
    h264Bitrate: 4000000,
    h264KeyframeInterval: 2
  });

  const PROFILE_PRESETS: Record<string, any> = {
    'low-latency': {
      profile: 'low-latency',
      width: 960,
      height: 540,
      outputWidth: 960,
      outputHeight: 540,
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
  const [now, setNow] = useState(Date.now() / 1000);

  // Developer / Experimental mode. Off by default: V1 is a stable MJPEG product.
  // When off, H.264 codec selection, capture profiles, and the verbose producer
  // metrics are hidden — normal users just pick resolution/fps/quality.
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
  const fpsCapFor = (w: number, h: number): number => {
    const e = activeCam?.fpsByResolution?.find((r: any) => r.width === w && r.height === h);
    return e ? e.maxFps : 0; // 0 = unknown (do not restrict)
  };
  const maxFpsHere = fpsCapFor(settings.width, settings.height);
  const supports60 = maxFpsHere === 0 || maxFpsHere >= 50;
  const supports30 = maxFpsHere === 0 || maxFpsHere >= 25;

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
      mirror: settings.mirror ? 'true' : 'false',
      rotate: '0',
    });

    const success = await connectAndSetupObs(obsPassword, obsUrl, obsMode, (status) => setObsStatus(status));
    setIsObsConnecting(false);
    if (obsMode === 'browser' && success && onEnterObsMode) {
      onEnterObsMode();
    }
  };

  const fetchStatus = useCallback(() => {
    apiFetch(baseUrl, '/api/camera/status', token)
      .then(res => res.json())
      .then(data => {
        const status = data.status || data;
        if (status) {
          setSettings(prev => {
            const merged: any = {
              ...prev,
              // Display/control fields are safe to mirror on every poll so a
              // change made on the phone shows up on the desktop within ~1s.
              cameraId: status.cameraId ?? prev.cameraId,
              displayRotation: status.displayRotation ?? prev.displayRotation,
              aspectRatio: status.aspectRatio ?? prev.aspectRatio,
              mirror: status.mirror ?? prev.mirror,
              torchEnabled: status.torchEnabled ?? prev.torchEnabled,
              linearZoom: status.linearZoom ?? prev.linearZoom,
              streamMode: status.streamMode ?? prev.streamMode,
              targetBandwidthMbps: status.targetBandwidthMbps ?? prev.targetBandwidthMbps,
              h264Bitrate: status.h264Bitrate ?? prev.h264Bitrate,
              h264KeyframeInterval: status.h264KeyframeInterval ?? prev.h264KeyframeInterval
            };
            // Stream-shaping fields (resolution/fps/quality/profile) also need to
            // reflect phone-side changes, but only when the desktop is not in the
            // middle of applying its own change — otherwise an in-flight poll
            // would revert the user's selection before it lands.
            if (!isSyncingRef.current) {
              merged.profile = status.profile ?? prev.profile;
              merged.width = status.width ?? prev.width;
              merged.height = status.height ?? prev.height;
              merged.outputWidth = status.outputWidth ?? prev.outputWidth;
              merged.outputHeight = status.outputHeight ?? prev.outputHeight;
              merged.fps = status.fps ?? prev.fps;
              merged.jpegQuality = status.jpegQuality ?? prev.jpegQuality;
            }
            return merged;
          });
        }
      })
      .catch(console.error);
  }, [baseUrl, token]);

  useEffect(() => {
    apiFetch(baseUrl, '/api/camera/list', token)
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
        .then(data => setAndroidMetrics(data))
        .catch(() => setAndroidMetrics(null));

    }, 1000);

    return () => clearInterval(interval);
  }, [baseUrl, token, fetchStatus]);

  // --- Diagnostics capture (reactive, so log entries are deduped per source) ---
  useEffect(() => {
    if (vcamState?.last_error) addDiag('producerErr', `Producer: ${vcamState.last_error}`);
    const m = vcamState?.metrics;
    if (m?.last_error) addDiag('decodeErr', `Producer decode: ${m.last_error}`);
    if (m) {
      const drift = Math.abs((m.written_fps || 0) - (m.fps_target || 0));
      if (m.fps_target > 0 && (m.written_fps || 0) < m.fps_target - 5) {
        addDiag('fpsDrift', `FPS below target: out ${m.written_fps}/${m.fps_target} (in ${m.decoded_fps})`);
      } else if (drift <= 5) {
        addDiag('fpsDrift', `FPS on target: ${m.written_fps}/${m.fps_target}`);
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
        m ? `prodIn=${m.decoded_fps} prodOut=${m.written_fps}` : 'prod=off',
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

  const startStream = () => apiFetch(baseUrl, '/api/stream/start', token, { method: 'POST' });
  const stopStream = () => apiFetch(baseUrl, '/api/stream/stop', token, { method: 'POST' });

  const handleStartProducer = async (s: any) => {
    const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    const source = s.streamMode === 'h264' ? 'h264' : 'mjpeg';
    const targetUrl = source === 'h264' ? `${base}/stream.h264` : `${base}/stream.mjpeg`;
    // Rotation is applied on the phone now (the /stream.mjpeg frames are already
    // rotated), so the producer must NOT rotate again — pass 0 explicitly, which
    // also disables its portrait auto-rotate. The producer still letterboxes a
    // portrait frame into the fixed landscape output.
    const rotate = 0;
    console.log('[Tauri UI] Calling start_virtual_camera_feeder with', {
      url: targetUrl, source, width: s.outputWidth || s.width, height: s.outputHeight || s.height, fps: s.fps, quality: s.jpegQuality, profile: s.profile, rotate, mirror: s.mirror
    });
    try {
      await invoke('start_virtual_camera_feeder', {
        url: targetUrl,
        source,
        width: s.outputWidth || s.width,
        height: s.outputHeight || s.height,
        fps: s.fps,
        quality: s.jpegQuality,
        profile: s.profile,
        rotate,
        mirror: !!s.mirror,
        token: token || undefined
      });
      console.log('[Tauri UI] start_virtual_camera_feeder completed');
    } catch (e: any) {
      console.error('[Tauri UI] start_virtual_camera_feeder failed:', e);
      setVcamMessage(`Failed to start producer: ${e.toString()}`);
    }
  };

  const handleStartNativeCamera = async () => {
    const s = settingsRef.current;

    setVcamMessage('Starting pipeline...');
    try {
      if (!vcamState?.host_running) {
        await invoke('start_virtual_camera_host');
      }

      await restartFullPipelineWithSettings(s);

      setVcamMessage('');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      console.error('[Tauri UI] handleStartNativeCamera failed:', e);
      setVcamMessage(`Error: ${e.toString()}`);
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
      await restartAndroidStreamWithSettings(s);
      await handleStartProducer(s);

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

  const postSettingsToAndroid = async (s: any) => {
    await apiFetch(baseUrl, '/api/settings', token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        profile: s.profile,
        width: s.width,
        height: s.height,
        outputWidth: s.outputWidth,
        outputHeight: s.outputHeight,
        fps: s.fps,
        jpegQuality: s.jpegQuality,
        cameraId: s.cameraId,
        aspectRatio: s.aspectRatio || 'auto',
        // These two were missing, so desktop orientation/mirror changes never
        // actually reached the phone (and polling snapped the UI back).
        displayRotation: s.displayRotation ?? '0',
        mirror: !!s.mirror,
        streamMode: s.streamMode,
        targetBandwidthMbps: s.targetBandwidthMbps,
        h264Bitrate: s.h264Bitrate,
        h264KeyframeInterval: s.h264KeyframeInterval,
        localPreviewEnabled: !previewOff
      })
    });
  };

  const applySettingsAndRefreshPreview = async (nextSettings: any, keysChanged: string[]) => {
    setSettings(nextSettings);
    settingsRef.current = nextSettings;
    setIsSyncing(true);
    isSyncingRef.current = true;

    try {
      // h264Bitrate and jpegQuality are intentionally absent: Android applies
      // both to the live pipeline without a rebind (bitrate via
      // MediaCodec.setParameters; JPEG quality is read per-frame). Restarting
      // the whole pipeline on every quality-slider step was the source of the
      // repeated producer restarts.
      const streamImpacting = ['profile', 'width', 'height', 'fps', 'cameraId', 'streamMode', 'h264KeyframeInterval'].some(k => keysChanged.includes(k));
      const streamWasRunning = vcamState?.running || androidMetrics?.encodedWidth > 0;

      if (streamImpacting && streamWasRunning) {
        await restartFullPipelineWithSettings(nextSettings);
      } else {
        await postSettingsToAndroid(nextSettings);
        if (!previewOff) {
          window.dispatchEvent(new CustomEvent('reload-preview'));
        }
      }
    } catch (err: any) {
      console.error('[Tauri UI] applySettingsAndRefreshPreview failed:', err);
      setVcamMessage(`Settings apply failed: ${err.toString()}`);
    } finally {
      setIsSyncing(false);
      isSyncingRef.current = false;
    }
  };

  const updateSetting = async (key: string, value: any) => {
    const newSettings = { ...settingsRef.current, [key]: value };

    if (key === 'torchEnabled') {
      setSettings(newSettings);
      settingsRef.current = newSettings;
      try {
        const res = await apiFetch(baseUrl, '/api/camera/torch', token, { method: 'POST', body: JSON.stringify({ enabled: value }), headers: { 'Content-Type': 'application/json' }});
        if (!res.ok) {
          const body = await res.text().catch(() => '');
          addDiag('torch', `Torch ${value ? 'on' : 'off'} failed: HTTP ${res.status} ${body}`);
          setVcamMessage(`Torch not available on this camera (HTTP ${res.status}).`);
        } else {
          addDiag('torch', `Torch ${value ? 'on' : 'off'}`);
        }
      } catch (e: any) {
        addDiag('torch', `Torch request failed: ${e}`);
        setVcamMessage(`Torch request failed: ${e}`);
      }
      return;
    } else if (key === 'linearZoom') {
      setSettings(newSettings);
      settingsRef.current = newSettings;
      try {
        const res = await apiFetch(baseUrl, '/api/camera/zoom', token, { method: 'POST', body: JSON.stringify({ linearZoom: value }), headers: { 'Content-Type': 'application/json' }});
        if (!res.ok) addDiag('zoom', `Zoom failed: HTTP ${res.status}`);
      } catch (e: any) {
        addDiag('zoom', `Zoom request failed: ${e}`);
      }
      return;
    }

    await applySettingsAndRefreshPreview(newSettings, [key]);
  };

  const waitForAndroidResolution = async (s: any, timeoutMs = 8000) => {
    const deadline = Date.now() + timeoutMs;
    let lastMetrics: any = null;

    while (Date.now() < deadline) {
      try {
        const res = await apiFetch(baseUrl, '/api/stream/metrics', token);
        if (res.ok) {
          const m = await res.json();
          lastMetrics = m;

          const encodedOk =
            Number(m.encodedWidth) === Number(s.width) &&
            Number(m.encodedHeight) === Number(s.height);

          const hasFrame =
            Number(m.latestFrameRevision || 0) > 0 ||
            Number(m.fps || 0) > 0 ||
            Number(m.encodedWidth || 0) > 0;

          if (encodedOk && hasFrame) {
            return m;
          }
        }
      } catch {}

      await sleep(300);
    }

    // The device may legitimately pick a nearby supported size instead of the
    // exact request (that is what the resolution policy is for). If frames are
    // flowing, proceed with a warning instead of failing the whole pipeline.
    const hasAnyFrame = lastMetrics && (
      Number(lastMetrics.latestFrameRevision || 0) > 0 ||
      Number(lastMetrics.encodedWidth || 0) > 0
    );
    if (hasAnyFrame) {
      console.warn(
        `[Tauri UI] Android is streaming ${lastMetrics.encodedWidth}x${lastMetrics.encodedHeight} ` +
        `instead of the requested ${s.width}x${s.height}; continuing (producer resizes).`
      );
      return lastMetrics;
    }

    throw new Error(
      `Android did not start streaming after settings change (requested ${s.width}x${s.height}). ` +
      `Check the phone's Logs tab for camera errors.`
    );
  };

  const restartAndroidStreamWithSettings = async (s: any) => {
    console.log('[Tauri UI] Restarting Android stream with settings:', s);

    await stopStream();
    await sleep(500);

    await postSettingsToAndroid(s);
    await sleep(200);

    await startStream();

    const metrics = await waitForAndroidResolution(s);
    console.log('[Tauri UI] Android stream rebound OK:', metrics);

    return metrics;
  };

  const restartFullPipelineWithSettings = async (s: any) => {
    console.log('[Tauri UI] Restarting full pipeline:', s);

    await invoke('stop_virtual_camera_feeder');
    await restartAndroidStreamWithSettings(s);
    await handleStartProducer(s);

    const state = await invoke<VirtualCamState>('get_virtual_camera_status');
    setVcamState(state);

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

    const newSettings = { ...settingsRef.current, ...preset };
    await applySettingsAndRefreshPreview(newSettings, ['profile', 'width', 'height', 'fps', 'jpegQuality']);
  };

  // Resolution and FPS are independent knobs, not baked into profile names.
  // Picking a resolution selects a capture policy that permits that size on the
  // phone (via `profile`) but leaves the frame rate untouched, so any
  // resolution can pair with any FPS (e.g. 720p30, 720p60, 1080p30, 1080p60).
  const updateResolution = async (w: number, h: number) => {
    const profile = w >= 1920 ? 'quality' : w >= 1280 ? 'balanced' : 'low-latency';
    const next = {
      ...settingsRef.current,
      width: w, height: h, outputWidth: w, outputHeight: h, profile,
    };
    logTestMarker('START', `${next.streamMode} ${w}x${h}@${next.fps} lens=${next.cameraId} q${next.jpegQuality}`);
    await applySettingsAndRefreshPreview(next, ['width', 'height', 'profile']);
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
    addDiag('orientation', `Orientation mode -> ${mode}`);
    const next = { ...settingsRef.current, aspectRatio: mode, displayRotation: '0' };
    await applySettingsAndRefreshPreview(next, ['aspectRatio', 'displayRotation']);
  };

  // V1 is MJPEG-only for normal users: leaving Developer mode forces the codec
  // back to the stable MJPEG path so H.264 can never be left running by accident.
  useEffect(() => {
    if (!devMode && settingsRef.current.streamMode !== 'mjpeg') {
      const next = { ...settingsRef.current, streamMode: 'mjpeg' };
      setSettings(next);
      settingsRef.current = next;
      addDiag('codec', 'Developer mode off — codec reset to MJPEG');
      postSettingsToAndroid(next).catch(() => {});
    }
  }, [devMode, addDiag]);

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
            <span style={{ color: vcamState?.running ? '#51cf66' : '#ff6b6b' }}>{vcamState?.running ? 'Running' : 'Stopped'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Profile:</span>
            <span style={{ color: '#fff', textTransform: 'capitalize' }}>{settings.profile}</span>
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
            <div>
              <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: 6 }}>Phone Camera Stream:</div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <button className="btn btn-secondary" onClick={handleStartFeedOnly} disabled={vcamState?.running}>
                  <Play size={14} style={{ marginRight: 6 }} /> Start
                </button>
                <button className="btn btn-secondary" onClick={handleStopFeedOnly} disabled={!vcamState?.running}>
                  <Square size={14} style={{ marginRight: 6 }} /> Stop
                </button>
              </div>
            </div>

            <div>
              <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: 6 }}>Desktop Virtual Camera:</div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <button className="btn btn-secondary" onClick={async () => {
                  try {
                    await invoke('start_virtual_camera_host');
                    setVcamMessage('');
                    addDiag('host', 'Virtual camera host started');
                  } catch (e: any) {
                    setVcamMessage(`Virtual camera host failed: ${e}`);
                    addDiag('host', `Host start failed: ${e}`);
                  }
                  invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
                }} disabled={vcamState?.host_running}>
                  <Play size={14} style={{ marginRight: 6 }} /> Start
                </button>
                <button className="btn btn-secondary" onClick={async () => {
                  try {
                    await invoke('stop_virtual_camera_host');
                    addDiag('host', 'Virtual camera host stopped');
                  } catch (e: any) {
                    addDiag('host', `Host stop failed: ${e}`);
                  }
                  invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState).catch(() => {});
                }} disabled={!vcamState?.host_running}>
                  <Square size={14} style={{ marginRight: 6 }} /> Stop
                </button>
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 4, paddingTop: 8, borderTop: '1px solid rgba(255,255,255,0.05)' }}>
              <button
                style={{ background: 'none', border: 'none', color: '#4dabf7', fontSize: '0.75rem', cursor: (vcamState?.running && vcamState?.host_running) ? 'default' : 'pointer', opacity: (vcamState?.running && vcamState?.host_running) ? 0.5 : 1 }}
                onClick={handleStartNativeCamera}
                disabled={vcamState?.running && vcamState?.host_running}
              >
                Start All
              </button>
              <button
                style={{ background: 'none', border: 'none', color: '#ff6b6b', fontSize: '0.75rem', cursor: (!vcamState?.running && !vcamState?.host_running) ? 'default' : 'pointer', opacity: (!vcamState?.running && !vcamState?.host_running) ? 0.5 : 1 }}
                onClick={handleStopNativeCamera}
                disabled={!vcamState?.running && !vcamState?.host_running}
              >
                Stop All
              </button>
            </div>
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
              <span style={{ color: vcamState.running ? '#51cf66' : '#888' }}>{vcamState.running ? 'Streaming' : 'Idle'}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <span style={{ color: '#888' }}>FPS (out / target)</span>
              <span style={{ color: (vcamState.metrics && vcamState.metrics.written_fps >= settings.fps - 5) ? '#51cf66' : '#ffb300' }}>
                {vcamState.metrics ? `${vcamState.metrics.written_fps} / ${settings.fps}` : '— / ' + settings.fps}
                {androidMetrics?.actualFps != null ? ` (phone ${androidMetrics.actualFps})` : ''}
              </span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <span style={{ color: '#888' }}>Output</span>
              <span>{vcamState.metrics ? `${vcamState.metrics.output_width}x${vcamState.metrics.output_height} ${settings.streamMode.toUpperCase()}` : `${settings.width}x${settings.height}`}</span>
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

        {/* Verbose producer metrics (developer mode) */}
        {devMode && vcamState && (
          <div style={{ background: '#0a0a0a', padding: 12, borderRadius: 8, border: '1px solid #222', fontFamily: 'monospace', fontSize: '0.75rem', color: '#51cf66' }}>

            {/* Extended Status */}
            <div style={{ marginBottom: 8, paddingBottom: 8, borderBottom: '1px solid #222' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888' }}>
                <span>Producer Running:</span>
                <span style={{ color: vcamState.running ? '#51cf66' : '#ff6b6b' }}>{vcamState.running ? 'Yes' : 'No'}</span>
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
                  <span>Native Encode Dim:</span>
                  <span>{androidMetrics.encodedWidth}x{androidMetrics.encodedHeight}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Rotation Resizing:</span>
                  <span style={{ color: androidMetrics.resizeNeeded ? '#ffb300' : '#51cf66' }}>{androidMetrics.resizeNeeded ? 'Required' : 'Native Match'}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Android FPS:</span>
                  <span style={{ color: (androidMetrics.actualFps || androidMetrics.fps) >= settings.fps - 5 ? '#51cf66' : '#ffb300' }}>
                    {androidMetrics.actualFps ?? androidMetrics.fps} / {settings.fps}
                  </span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                  <span>Android JPEG Encode:</span>
                  <span>{Number(androidMetrics.androidEncodeMsAvg || 0).toFixed(1)} ms</span>
                </div>

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
                          <li>Android Encode: {androidMetrics.actualFps || 0} FPS ({Number(androidMetrics.androidEncodeMsAvg || 0).toFixed(1)}ms)</li>
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
                    <span style={{ color: '#888' }}>FPS (In/Out):</span>
                    <span>{vcamState.metrics.decoded_fps} / {vcamState.metrics.written_fps}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Latency Est:</span>
                    <span>{vcamState.metrics.total_pipeline_ms} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Est. Bandwidth:</span>
                    <span style={{ color: '#4dabf7' }}>{vcamState.metrics.estimated_mbps} Mbps</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Decode Time:</span>
                    <span>{vcamState.metrics.decode_ms_avg} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Rotate Time:</span>
                    <span>{vcamState.metrics.rotate_ms_avg} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Resize Time:</span>
                    <span>{vcamState.metrics.resize_ms_avg} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Dropped JPEGs:</span>
                    <span style={{ color: vcamState.metrics.dropped_jpegs > 0 ? '#ff6b6b' : 'inherit' }}>{vcamState.metrics.dropped_jpegs}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Queue Len:</span>
                    <span>{vcamState.metrics.jpeg_queue_len}</span>
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
            <option value="640x480">480p (640x480)</option>
            <option value="960x540">540p (960x540)</option>
            <option value="1280x720">720p (1280x720)</option>
            <option value="1920x1080">1080p (1920x1080)</option>
          </select>
        </div>

        <div className="control-item" style={{ marginTop: 12 }}>
          <label>Frame Rate</label>
          <select
            className="input-control"
            value={settings.fps}
            onChange={(e) => updateFps(parseInt(e.target.value, 10))}
          >
            <option value={15}>15 fps</option>
            <option value={30} disabled={!supports30}>30 fps{!supports30 ? ' (unsupported here)' : ''}</option>
            <option value={60} disabled={!supports60}>60 fps{!supports60 ? ' (unsupported here)' : ''}</option>
          </select>
          <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
            Resolution and frame rate are independent.
            {maxFpsHere > 0
              ? ` This lens reports up to ${maxFpsHere} fps at ${settings.width}x${settings.height} via the normal camera API.`
              : ' Actual rate depends on the phone camera and lighting.'}
            {androidMetrics?.actualFps != null &&
              ` Delivering ${androidMetrics.actualFps}/${settings.fps} fps now.`}
          </p>
          {devMode && activeCam?.supportsHighSpeed && (
            <p style={{ fontSize: '0.68rem', color: '#ffb300', marginTop: 4 }}>
              Diagnostics: this lens has high-speed (slow-motion) modes
              {Array.isArray(activeCam.highSpeedFpsRanges) && activeCam.highSpeedFpsRanges.length > 0
                ? ` up to ${Math.max(...activeCam.highSpeedFpsRanges.map((r: any) => r.max))} fps`
                : ''}, but Android's constrained high-speed session is not usable by the
              MJPEG webcam path — so the webcam max stays the normal-API value above.
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
              <option value="low-latency">Low Latency (960x540, Q70)</option>
              <option value="balanced">Balanced (1280x720, Q85)</option>
              <option value="balanced-720p60">Balanced 60 (1280x720 @ 60fps, Q80)</option>
              <option value="quality">Quality (1920x1080, Q90)</option>
              <option value="experimental-1080p60">Experimental (1080p @ 60fps)</option>
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

        {devMode && (
          <div className="control-item">
            <label>Stream Codec (Developer)</label>
            <select
              className="input-control"
              value={settings.streamMode}
              onChange={(e) => updateSetting('streamMode', e.target.value)}
            >
              <option value="mjpeg">MJPEG (Stable)</option>
              <option value="h264">H.264 (Experimental / unstable)</option>
            </select>
            <p style={{ fontSize: '0.7rem', color: '#ffb300', marginTop: 4 }}>
              ⚠️ H.264 is experimental and may fail on some devices (the bundled
              openh264 decoder errors on certain phone encoder output). MJPEG is
              the stable V1 path. Use H.264 only for testing.
            </p>
          </div>
        )}

        {(settings.streamMode === 'mjpeg' || !devMode) ? (
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
            <p style={{ fontSize: '0.75rem', color: '#ffb300', marginBottom: 12 }}>
              H.264 is experimental. The desktop preview falls back to ~5 fps JPEG
              snapshots; the virtual camera itself runs at full rate.
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
                min="1" max="10" step="1"
                style={{ width: '100%', marginTop: 8 }}
                value={settings.h264KeyframeInterval}
                onChange={(e) => updateSetting('h264KeyframeInterval', parseInt(e.target.value))}
              />
              <p style={{ fontSize: '0.7rem', color: '#888', marginTop: 4 }}>
                Shorter intervals recover faster after network hiccups but cost bandwidth.
              </p>
            </div>
          </div>
        )}

        <div className="control-row" style={{ marginTop: 20 }}>
          <label style={{ fontSize: '0.9rem' }}>Disable Preview (Diagnostic)</label>
          <label className="switch">
            <input
              type="checkbox"
              checked={previewOff}
              onChange={(e) => setPreviewOff(e.target.checked)}
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
          <label>Orientation</label>
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
            The video is always upright. Auto resizes this preview to match how
            the phone is held; Horizontal/Vertical pin it. The virtual camera
            apps receive stays 16:9 — vertical video shows there with side bars.
          </p>
        </div>

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
