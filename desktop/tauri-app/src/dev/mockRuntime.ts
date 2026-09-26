/**
 * DEVELOPMENT ONLY — never shipped. main.tsx loads this module only when
 * `import.meta.env.DEV` is true and the page URL contains `?mock`, so the
 * production bundle contains none of it.
 *
 * A simulated phone and desktop backend for working on the UI without a phone,
 * adb or the native virtual camera:
 *   - Tauri commands via @tauri-apps/api/mocks (adb, producer/host lifecycle,
 *     logging, and synthetic NV12 frames for the real WebGL preview renderer)
 *   - the phone's HTTP API on http://127.0.0.1:8080 via a fetch shim
 *
 * Scenarios: ?mock (streaming), ?mock=stopped, ?mock=offline, ?mock=failed,
 * ?mock=unregistered, ?mock=nodevice. Add &connected to skip the connect screen.
 *
 * The compressed /stream.ocb2 feed is not simulated, so the WebCodecs preview
 * fails over to the native compatibility renderer (mock NV12 frames), which
 * also exercises the fallback path.
 */
import { mockIPC } from '@tauri-apps/api/mocks';
import { setPreferences } from '../state/preferences';

const PHONE = 'http://127.0.0.1:8080';

type Mode = { width: number; height: number; fps: number };
const m = (width: number, height: number, fps: number): Mode => ({ width, height, fps });

/**
 * H.264 modes the way CameraRepository reports them: path evidence for every
 * preferred mode (high-speed is disabled on the phone), and `h264Modes` = the
 * modes a path supports.
 */
function h264(regular: Mode[]) {
  const same = (a: Mode, b: Mode) => a.width === b.width && a.height === b.height && a.fps === b.fps;
  const h264PathCapabilities = [m(1920, 1080, 60), m(1280, 720, 60), m(1920, 1080, 30), m(1280, 720, 30)].map(mode => {
    const supported = regular.some(r => same(r, mode));
    return {
      mode,
      paths: [
        { engine: 'REGULAR_SURFACE', supported, reason: supported ? 'Regular session AE range covers the mode' : 'No regular AE range reaches this rate', cameraFps: supported ? mode.fps : undefined },
        { engine: 'HIGH_SPEED_SURFACE', supported: false, reason: 'High-speed capture is disabled' },
        { engine: 'HIGH_SPEED_GPU_BRIDGE', supported: false, reason: 'High-speed capture is disabled' },
      ],
    };
  });
  return {
    h264Modes: h264PathCapabilities.filter(entry => entry.paths.some(path => path.supported)).map(entry => entry.mode),
    h264PathCapabilities,
  };
}

// Back main: genuine 1080p60 (like a Galaxy S24). Ultrawide: 60 FPS only at
// 720p. Front: 30 FPS only. Together they cover every Smooth Motion outcome.
const cameras = [
  {
    id: '0', facing: 'back', label: 'Back main', lensType: 'wide', hasTorch: true, zoomRatioMin: 1, zoomRatioMax: 8,
    sensorOrientation: 90, supportedSizes: [], supportedFpsRanges: [],
    ...h264([m(1920, 1080, 60), m(1280, 720, 60), m(1920, 1080, 30), m(1280, 720, 30)]),
    mjpegModes: [m(1920, 1080, 30), m(1280, 720, 30), m(1280, 720, 15)],
    fpsByResolution: [{ width: 1920, height: 1080, maxFps: 60 }, { width: 1280, height: 720, maxFps: 60 }],
    supportsHighSpeed: true, highSpeedFpsRanges: [{ min: 120, max: 120 }, { min: 240, max: 240 }],
  },
  {
    id: '2', facing: 'back', label: 'Back ultrawide', lensType: 'ultrawide', hasTorch: true, zoomRatioMin: 1, zoomRatioMax: 2,
    sensorOrientation: 90, supportedSizes: [], supportedFpsRanges: [],
    ...h264([m(1280, 720, 60), m(1920, 1080, 30), m(1280, 720, 30)]),
    mjpegModes: [m(1280, 720, 30)],
  },
  {
    id: '1', facing: 'front', label: 'Front camera 1', lensType: '', hasTorch: false, zoomRatioMin: 1, zoomRatioMax: 4,
    sensorOrientation: 270, supportedSizes: [], supportedFpsRanges: [],
    ...h264([m(1920, 1080, 30), m(1280, 720, 30)]),
    mjpegModes: [m(1280, 720, 30)],
  },
];

export function installMockRuntime(scenario: string) {
  const params = new URLSearchParams(location.search);
  const phone = {
    reachable: scenario !== 'offline',
    lifecycle: scenario === 'stopped' ? 'STOPPED' : scenario === 'failed' ? 'FAILED' : 'STREAMING',
    lastError: scenario === 'failed' ? 'CameraAccessException: CAMERA_DISCONNECTED (2): camera device was closed by the system' : '',
    revision: 12,
    generation: 3,
    settings: {
      cameraId: '0', profile: 'quality', width: 1920, height: 1080, outputWidth: 1920, outputHeight: 1080, fps: 30,
      jpegQuality: 80, displayRotation: '0', aspectRatio: 'auto', mirror: false, torchEnabled: false, linearZoom: 0,
      streamMode: 'h264', targetBandwidthMbps: 0, h264Bitrate: 8_000_000, h264BitrateMode: 'auto', h264KeyframeInterval: 5,
    } as Record<string, any>,
  };
  const desktop = {
    registered: scenario !== 'unregistered',
    hostRunning: false,
    producerRunning: false,
    producerStartedAt: 0,
    readyAt: 0,
    sequence: 0,
  };
  // &connected exercises the real auto-reconnect path with a remembered phone.
  if (params.has('connected')) {
    setPreferences({
      autoReconnect: true,
      lastDeviceId: 'R5CT30ABCDE',
      knownDevices: [{ id: 'R5CT30ABCDE', mode: 'usb', name: 'Google Pixel 8 Pro', serial: 'R5CT30ABCDE', port: 8080, lastConnected: Date.now() }],
    });
  }
  if (params.has('advanced')) setPreferences({ advancedMode: params.get('advanced') !== '0' });

  // Like the phone's kotlinx JSON: fields equal to their default are omitted,
  // so h264BitrateMode only appears while it is "manual".
  const withoutDefaults = (settings: Record<string, any>) => {
    const { h264BitrateMode, ...rest } = settings;
    return h264BitrateMode === 'manual' ? { ...rest, h264BitrateMode } : rest;
  };

  const zoomRatio = () => {
    const camera = cameras.find(c => c.id === phone.settings.cameraId) ?? cameras[0];
    return 1 + phone.settings.linearZoom * ((camera.zoomRatioMax ?? 1) - 1);
  };

  const status = () => ({
    revision: phone.revision,
    pipelineGeneration: phone.generation,
    lifecycleState: phone.lifecycle,
    streaming: phone.lifecycle === 'STREAMING',
    lastError: phone.lastError,
    accessMode: 'usbOnly',
    port: 8080,
    activeStreamMode: phone.settings.streamMode,
    encodedWidth: phone.lifecycle === 'STREAMING' ? phone.settings.width : 0,
    encodedHeight: phone.lifecycle === 'STREAMING' ? phone.settings.height : 0,
    latestFrameRevision: Date.now(),
    hasTorch: true,
    zoomRatio: zoomRatio(),
    rotationDegrees: Number(phone.settings.displayRotation) || 0,
    snapshot: { actual: { encodedFps: phone.settings.fps }, selected: { fps: phone.settings.fps }, generation: phone.generation },
    ...withoutDefaults(phone.settings),
  });

  const metrics = () => {
    const live = phone.lifecycle === 'STREAMING';
    const s = phone.settings;
    const jitter = () => (Math.random() < 0.2 ? -1 : 0);
    return {
      generation: phone.generation,
      lifecycleState: phone.lifecycle,
      activeStreamMode: s.streamMode,
      phonePreviewRequested: false,
      phonePreviewActive: false,
      phonePreviewFailureReason: '',
      capture: live ? {
        engine: 'REGULAR_SURFACE', cameraId: s.cameraId, requestedWidth: s.width, requestedHeight: s.height,
        actualWidth: s.width, actualHeight: s.height, requestedFps: s.fps, selectedFps: s.fps,
        cameraSessionFps: s.fps, actualFps: s.fps + jitter(),
      } : null,
      h264: live && s.streamMode === 'h264' ? {
        encoderName: 'c2.qti.avc.encoder', hardwareEncoder: true, encodedFps: s.fps + jitter(),
        bitrate: Math.round(s.h264Bitrate * (0.92 + Math.random() * 0.08)), clientCount: desktop.producerRunning ? 1 : 0,
      } : null,
      mjpeg: live && s.streamMode === 'mjpeg' ? {
        encodedFps: s.fps + jitter(), encodeMs: 11.2, yuvMs: 3.1, jpegMs: 7.4, rotateMs: 0.7,
        processingCapacityFps: 58, latestFrameRevision: Date.now(), clientCount: 1,
      } : null,
      transport: { estimatedMbps: (s.h264Bitrate / 1_000_000).toFixed(2), targetBandwidthMbps: s.targetBandwidthMbps },
      selection: {
        requestedAspectRatio: '16:9', selectedAspectRatio: '16:9', aspectRatioMatch: true, resizeNeeded: false,
        selectedRawWidth: s.width, selectedRawHeight: s.height, selectedEffectiveWidth: s.width, selectedEffectiveHeight: s.height,
        resolutionPolicy: 'strict_quality',
      },
      fallback: null,
    };
  };

  const committed = () => (desktop.producerRunning ? Math.floor((Date.now() - desktop.producerStartedAt) / 33) : 0);
  const pipelineReady = () => desktop.hostRunning && desktop.producerRunning && committed() >= 3;
  const consumerAttached = () => pipelineReady() && desktop.readyAt > 0 && Date.now() - desktop.readyAt > 4000;

  const vcamStatus = () => {
    if (pipelineReady() && !desktop.readyAt) desktop.readyAt = Date.now();
    if (!pipelineReady()) desktop.readyAt = 0;
    const s = phone.settings;
    const fps = s.fps;
    return {
      running: desktop.producerRunning,
      process_running: desktop.producerRunning,
      pipeline_ready: pipelineReady(),
      producer_ready: desktop.producerRunning,
      virtual_camera_ready: consumerAttached(),
      producer_state: desktop.producerRunning ? 'WRITING_RING' : 'STOPPED',
      host_running: desktop.hostRunning,
      host_activated: desktop.hostRunning,
      registered: desktop.registered,
      producer_path: 'C:\\Dev\\OpenCamBridge\\windows\\virtual-camera-mediafoundation\\rust-frame-producer\\target\\release\\opencambridge-frame-producer.exe',
      producer_exists: true,
      producer_pid: desktop.producerRunning ? 18244 : undefined,
      last_error: undefined,
      last_metrics_time: Date.now() / 1000,
      binary_identity: {
        ready: true, producer_path: '', producer_file_hash: 'a1b2c3', producer_runtime_hash: 'a1b2c3', built_dll_path: '',
        built_dll_hash: '9f8e7d6c5b4a', installed_dll_path: '', installed_dll_hash: '9f8e7d6c5b4a', registered_dll_path: '',
        registered_dll_hash: '9f8e7d6c5b4a', loaded_dll_hash: '9f8e7d6c5b4a', loaded_dll_current: true, remediation: '',
      },
      metrics: desktop.producerRunning ? {
        producer_state: 'WRITING_RING', ring_frames_committed: committed(), source: s.streamMode === 'h264' ? 'ocb2-h264' : 'mjpeg',
        profile: s.profile, source_width: s.width, source_height: s.height, output_width: s.width, output_height: s.height,
        fps_target: fps, source_fps: fps, http_jpeg_fps: fps, decoded_fps: fps, written_fps: fps, transport_fps: fps,
        transport_received_fps: fps, decoded_unique_fps: fps, producer_decoded_fps: fps, ring_written_fps: fps,
        virtual_camera_unique_fps: consumerAttached() ? fps : 0, repeated_samples: 0, virtual_camera_requested_fps: fps,
        virtual_camera_repeated_fps: 0, dropped_jpegs: 0, replaced_frames: 0, jpeg_queue_len: 0, decode_ms_avg: 2,
        rotate_ms_avg: 0, resize_ms_avg: 0, write_ms_avg: 1, total_pipeline_ms: 4, producer_processing_ms: 4,
        latency_ms: 38, phone_to_ring_latency_ms: 38, bytes_per_sec: s.h264Bitrate / 8,
        estimated_mbps: (s.h264Bitrate / 1_000_000).toFixed(1), transport_bandwidth_mbps: (s.h264Bitrate / 1_000_000).toFixed(1),
        pixel_format: 'NV12', decode_backend: 'media-foundation-d3d11', resize_backend: 'skipped',
        decoder_name: 'Microsoft H264 Video Decoder MFT', d3d11_output: true, hardware_decoder: true,
        last_error: null, virtual_camera_ready: consumerAttached(),
        ring: {
          consumer_attached: consumerAttached(), consumer_pid: consumerAttached() ? 9920 : 0, consumer_heartbeat_qpc: 0,
          sample_requests: committed(), ring_read_attempts: committed(), ring_read_successes: committed(),
          ring_validation_failures: 0, sample_copy_failures: 0, last_ring_error: 0, last_accepted_sequence: committed(),
          negotiated_subtype: 0, negotiated_width: s.width, negotiated_height: s.height, negotiated_fps_num: fps,
          negotiated_fps_den: 1, source_fps_num: fps, source_fps_den: 1, resize_backend: 'skipped', resize_failures: 0,
          installed_dll_build_hash: '9f8e7d6c5b4a3210', producer_build_hash: 'a1b2c3d4e5f60718', ring_abi_hash: 0x5eed1234,
          ring_write_sequence: committed(), stream_generation: phone.generation, ring_frames_overwritten: 0,
          playout_buffer_depth_ms: 33, playout_target_delay_ms: 33, playout_late_dropped: 0, playout_underruns: 0,
          playout_scheduler_resets: 0, playout_clock_ppm: 3, playout_max_output_gap_ms: 35,
        },
      } : null,
    };
  };

  // --- synthetic camera picture, converted to NV12 for the real renderer ---
  const FW = 640;
  const FH = 360;
  const canvas = new OffscreenCanvas(FW, FH);
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  const frame = new Uint8Array(80 + FW * FH * 1.5);
  let lastFrameAt = 0;

  const drawScene = (t: number) => {
    const g = ctx.createLinearGradient(0, 0, FW, FH);
    g.addColorStop(0, '#2b3a55');
    g.addColorStop(1, '#141b28');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, FW, FH);
    // a window with daylight
    ctx.fillStyle = '#6f8fb8';
    ctx.fillRect(FW * 0.66, FH * 0.12, FW * 0.26, FH * 0.42);
    ctx.fillStyle = '#a9c3e0';
    ctx.fillRect(FW * 0.68, FH * 0.15, FW * 0.1, FH * 0.36);
    ctx.fillRect(FW * 0.8, FH * 0.15, FW * 0.1, FH * 0.36);
    // shelf
    ctx.fillStyle = '#3d2f25';
    ctx.fillRect(FW * 0.05, FH * 0.3, FW * 0.28, FH * 0.03);
    ['#c0563f', '#d9a441', '#4f8b6a', '#4a6fa5'].forEach((c, i) => {
      ctx.fillStyle = c;
      ctx.fillRect(FW * (0.07 + i * 0.05), FH * 0.18, FW * 0.035, FH * 0.12);
    });
    // a person, gently swaying
    const sway = Math.sin(t / 900) * 10;
    ctx.fillStyle = '#e0b89a';
    ctx.beginPath();
    ctx.arc(FW * 0.45 + sway, FH * 0.42, FH * 0.14, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = '#3b5b8c';
    ctx.beginPath();
    ctx.ellipse(FW * 0.45 + sway, FH * 0.95, FH * 0.3, FH * 0.36, 0, Math.PI, 0);
    ctx.fill();
    ctx.fillStyle = '#2a1f1a';
    ctx.beginPath();
    ctx.arc(FW * 0.45 + sway, FH * 0.36, FH * 0.145, Math.PI, 0);
    ctx.fill();
    // timestamp, so motion and freezes are visible
    ctx.fillStyle = 'rgba(255,255,255,0.75)';
    ctx.font = '600 20px Segoe UI, sans-serif';
    ctx.fillText('MOCK CAMERA', 24, 36);
  };

  const LOOP = 36;
  const loop: Uint8Array[] = [];
  const frameFor = (sequence: number) => {
    const slot = sequence % LOOP;
    if (!loop[slot]) loop[slot] = new Uint8Array(buildFrame(slot, (slot / LOOP) * Math.PI * 2 * 900));
    const bytes = loop[slot];
    const view = new DataView(bytes.buffer);
    view.setBigUint64(8, BigInt(sequence), true);
    view.setBigUint64(48, BigInt(phone.generation), true);
    view.setBigUint64(56, BigInt(sequence), true);
    view.setUint32(68, phone.settings.fps, true);
    return bytes.slice().buffer;
  };

  const buildFrame = (sequence: number, t: number) => {
    drawScene(t);
    const rgba = ctx.getImageData(0, 0, FW, FH).data;
    const view = new DataView(frame.buffer);
    view.setUint32(0, 0x5250564e, true);
    view.setUint16(4, 2, true);
    view.setUint16(6, 80, true);
    view.setBigUint64(8, BigInt(sequence), true);
    view.setUint32(24, FW, true);
    view.setUint32(28, FH, true);
    view.setUint32(32, FW, true);
    view.setUint32(36, FW, true);
    view.setUint32(40, FW * FH * 1.5, true);
    view.setBigUint64(48, BigInt(phone.generation), true);
    view.setBigUint64(56, BigInt(sequence), true);
    view.setUint32(64, 2 | (1 << 8) | (2 << 16) | (1 << 24), true);
    view.setUint32(68, phone.settings.fps, true);
    view.setUint32(72, 1, true);
    const yBase = 80;
    const uvBase = 80 + FW * FH;
    for (let y = 0; y < FH; y++) {
      for (let x = 0; x < FW; x++) {
        const i = (y * FW + x) * 4;
        const r = rgba[i], gg = rgba[i + 1], b = rgba[i + 2];
        frame[yBase + y * FW + x] = 16 + ((47 * r + 157 * gg + 16 * b) >> 8);
        if ((y & 1) === 0 && (x & 1) === 0) {
          const o = uvBase + (y >> 1) * FW + x;
          frame[o] = 128 + ((-26 * r - 87 * gg + 112 * b) >> 8);
          frame[o + 1] = 128 + ((112 * r - 102 * gg - 10 * b) >> 8);
        }
      }
    }
    return frame.slice().buffer;
  };

  const previewCounters = { calls: 0, nonEmpty: 0, empty: 0, lastReturned: 0 };

  mockIPC((cmd, args: any) => {
    switch (cmd) {
      case 'list_devices':
        return scenario === 'nodevice' ? [] : [
          { serial: 'R5CT30ABCDE', state: 'device', model: 'Pixel_8_Pro' },
          ...(params.has('twodevices') ? [{ serial: 'LE2113A7F2', state: 'device', model: 'LE2113' }] : []),
        ];
      case 'forward_port': return 'forward active';
      case 'remove_forwards': return 'removed';
      case 'get_adb_status': return 'Android Debug Bridge version 1.0.41';
      case 'check_virtual_camera_backend': return desktop.registered;
      case 'register_virtual_camera_backend': desktop.registered = true; return 'Registered.';
      case 'unregister_virtual_camera_backend': desktop.registered = false; return 'Unregistered.';
      case 'get_virtual_camera_backend_details':
        return `OpenCamBridge Camera: ${desktop.registered ? 'registered' : 'not registered'}\nCLSID {8CF75B14-3F68-46BC-80DF-5FB86AED931E}\nDLL C:\\Program Files\\OpenCamBridge\\VirtualCameraMediaSource.dll`;
      case 'start_virtual_camera_host': desktop.hostRunning = true; return null;
      case 'stop_virtual_camera_host': desktop.hostRunning = false; return null;
      case 'start_virtual_camera_feeder':
        desktop.producerRunning = true;
        desktop.producerStartedAt = Date.now() - 400;
        return null;
      case 'stop_virtual_camera_feeder': desktop.producerRunning = false; return null;
      case 'get_virtual_camera_status': return vcamStatus();
      case 'get_nv12_preview_diagnostics':
        return {
          ring_alive: desktop.producerRunning, ring_write_sequence: desktop.sequence, stream_generation: phone.generation,
          preview_command_calls: previewCounters.calls, non_empty_responses: previewCounters.nonEmpty,
          empty_responses: previewCounters.empty, last_returned_sequence: previewCounters.lastReturned,
          last_ipc_payload_bytes: frame.byteLength, last_width: FW, last_height: FH, torn_slots_rejected: 0,
          skipped_sequences: 0, last_error: '',
        };
      case 'get_nv12_preview_frame': {
        previewCounters.calls += 1;
        const interval = 1000 / (phone.settings.fps || 30);
        if (!desktop.producerRunning || phone.lifecycle !== 'STREAMING' || performance.now() - lastFrameAt < interval) {
          previewCounters.empty += 1;
          return new ArrayBuffer(0);
        }
        lastFrameAt = performance.now();
        desktop.sequence = Math.max(desktop.sequence + 1, Number(args?.afterSequence || 0) + 1);
        previewCounters.nonEmpty += 1;
        previewCounters.lastReturned = desktop.sequence;
        return frameFor(desktop.sequence);
      }
      case 'start_log_session': return 'C:\\ProgramData\\OpenCamBridge\\logs\\session-mock.log';
      case 'append_log': return null;
      case 'get_log_path': return 'C:\\ProgramData\\OpenCamBridge\\logs\\session-mock.log';
      case 'read_log_tail': return '[12:00:01] INFO  [device] {"manufacturer":"Google","model":"Pixel 8 Pro"}\n[12:00:02] INFO  [metrics] h264 1920x1080@30';
      case 'clear_log': return null;
      case 'open_logs_folder': return null;
      case 'plugin:app|version': return '0.1.0';
      case 'plugin:app|tauri_version': return '2.11.3';
      case 'plugin:app|name': return 'OpenCamBridge';
      case 'plugin:opener|open_url': window.open(args?.url, '_blank'); return null;
      case 'plugin:http|fetch': throw new Error('The mock phone does not serve /stream.ocb2');
      default:
        console.warn('[mock] unhandled command', cmd, args);
        return null;
    }
  });

  const json = (body: unknown, init: ResponseInit = {}) =>
    new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' }, ...init });
  const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
  const CAPTURE_KEYS = ['cameraId', 'streamMode', 'width', 'height', 'fps', 'profile', 'displayRotation', 'mirror', 'h264KeyframeInterval'];

  const realFetch = window.fetch.bind(window);
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (!url.startsWith(PHONE)) return realFetch(input, init);
    await delay(25);
    if (!phone.reachable) throw new TypeError('Failed to fetch');
    const path = new URL(url).pathname;
    const method = (init?.method || 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(String(init.body)) : {};

    if (path === '/health') return new Response('OK');
    if (path === '/api/camera/status' || path === '/api/settings' && method === 'GET') return json(status());
    if (path === '/api/stream/metrics') return json(metrics());
    if (path === '/api/device/info') {
      return json({ app: 'OpenCamBridge', version: '2.0.0', platform: 'android', serverPort: 8080, manufacturer: 'Google', model: 'Pixel 8 Pro', batteryOptimizationExempt: false });
    }
    if (path === '/api/pipeline/capabilities') return json({ revision: phone.revision, cameras });
    if (path === '/api/camera/list') return json(cameras);

    const conflict = () => json({ success: false, message: `Revision conflict: client base=${body.baseRevision}, authoritative=${phone.revision}`, authoritativeState: status() }, { status: 409 });

    if (path === '/api/settings' && method === 'POST') {
      if (Number(body.baseRevision) !== phone.revision) return conflict();
      const { baseRevision: _b, requestId: _r, clientType: _c, ...patch } = body;
      const captureChanged = CAPTURE_KEYS.some(key => key in patch && patch[key] !== phone.settings[key]);
      // As on the phone: a bitrate sent without a mode means Manual.
      if ('h264Bitrate' in patch && !('h264BitrateMode' in patch)) patch.h264BitrateMode = 'manual';
      Object.assign(phone.settings, patch);
      phone.revision += 1;
      if (captureChanged && phone.lifecycle === 'STREAMING') {
        phone.lifecycle = 'RECONFIGURING';
        await delay(700);
        phone.generation += 1;
        phone.lifecycle = 'STREAMING';
      }
      return json({ success: true, message: 'Settings applied', revision: phone.revision, lifecycleState: phone.lifecycle });
    }
    if (path === '/api/camera/torch' && method === 'POST') {
      if (Number(body.baseRevision) !== phone.revision) return conflict();
      phone.settings.torchEnabled = !!body.enabled;
      phone.revision += 1;
      return json({ success: true, revision: phone.revision });
    }
    if (path === '/api/camera/zoom' && method === 'POST') {
      if (Number(body.baseRevision) !== phone.revision) return conflict();
      phone.settings.linearZoom = Number(body.linearZoom ?? phone.settings.linearZoom);
      phone.revision += 1;
      await delay(60);
      return json({ success: true, revision: phone.revision });
    }
    if (path === '/api/stream/start' && method === 'POST') {
      if (phone.lifecycle !== 'STREAMING') {
        phone.lifecycle = 'STARTING';
        await delay(600);
        phone.generation += 1;
        phone.lifecycle = 'STREAMING';
        phone.lastError = '';
      }
      return json({ success: true, message: 'Camera streaming', revision: phone.revision });
    }
    if (path === '/api/stream/stop' && method === 'POST') {
      phone.lifecycle = 'STOPPED';
      return json({ success: true, message: 'Camera stopped', revision: phone.revision });
    }
    if (path === '/api/stream/recover' && method === 'POST') {
      phone.lifecycle = 'RECOVERING';
      await delay(700);
      phone.lifecycle = 'STREAMING';
      phone.lastError = '';
      return json({ success: true, message: 'Camera recovered', revision: phone.revision });
    }
    return json({ success: false, message: `mock: no route for ${method} ${path}` }, { status: 404 });
  };

  // The desktop listens for state pushes; the poll covers everything in the mock.
  class MockEventSource extends EventTarget {
    onerror: ((event: Event) => void) | null = null;
    readonly url: string;
    constructor(url: string) {
      super();
      this.url = url;
    }
    close() { /* nothing to release */ }
  }
  const RealEventSource = window.EventSource;
  (window as any).EventSource = function (url: string, config?: EventSourceInit) {
    return String(url).startsWith(PHONE) ? new MockEventSource(url) : new RealEventSource(url, config);
  };

  // Handy for scripted QA from the console.
  (window as any).__ocbMock = { phone, desktop, cameras };
  console.info(`[mock] OpenCamBridge mock runtime installed (scenario: ${scenario})`);
}
