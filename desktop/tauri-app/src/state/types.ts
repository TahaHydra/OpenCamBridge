/**
 * Shapes shared by the desktop UI. The native ones mirror the serde structs in
 * src-tauri/src/virtualcam.rs; the phone ones mirror the Android DTOs served by
 * ControlServer. Everything here is data only.
 */

export interface RingDiagnostics {
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

export interface VirtualCamMetrics {
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

export interface BinaryIdentity {
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
}

export interface VirtualCamState {
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
  binary_identity: BinaryIdentity;
}

/** One complete capture + encode tuple the phone says it can deliver. */
export interface CameraMode {
  width: number;
  height: number;
  fps: number;
}

export interface CameraInfo {
  id: string;
  facing: string;
  label: string;
  lensType?: string;
  isMonochrome?: boolean;
  hasTorch?: boolean;
  zoomRatioMin?: number;
  zoomRatioMax?: number;
  focalLengths?: number[];
  fpsByResolution?: { width: number; height: number; maxFps: number }[];
  supportsHighSpeed?: boolean;
  highSpeedFpsRanges?: { min: number; max: number }[];
  h264Modes?: CameraMode[];
  mjpegModes?: CameraMode[];
  /** Per H.264 mode: which phone capture paths can deliver it, and why not. */
  h264PathCapabilities?: H264ModePaths[];
}

export interface H264ModePaths {
  mode: CameraMode;
  paths: {
    /** 'REGULAR_SURFACE' | 'HIGH_SPEED_SURFACE' | 'HIGH_SPEED_GPU_BRIDGE' */
    engine: string;
    supported: boolean;
    reason?: string;
    cameraFps?: number;
  }[];
}

export type StreamMode = 'h264' | 'mjpeg';

/** The desktop's copy of the phone's desired settings (authoritative on the phone). */
export interface CameraSettings {
  cameraId: string;
  profile: string;
  width: number;
  height: number;
  outputWidth: number;
  outputHeight: number;
  fps: number;
  jpegQuality: number;
  displayRotation: string;
  aspectRatio: string;
  mirror: boolean;
  torchEnabled: boolean;
  linearZoom: number;
  streamMode: string;
  targetBandwidthMbps: number;
  h264Bitrate: number;
  h264KeyframeInterval: number;
}

export type SettingKey = keyof CameraSettings;

export interface PhoneInfo {
  app?: string;
  version?: string;
  platform?: string;
  serverPort?: number;
  manufacturer?: string;
  model?: string;
  batteryOptimizationExempt?: boolean;
}

export type ConnectMode = 'usb' | 'lan';

export interface AdbDevice {
  serial: string;
  state: string;
  model?: string;
}

export interface ConnectionInfo {
  mode: ConnectMode;
  port?: number;
  serial?: string;
  /** Phone base URL (LAN) — USB always connects through 127.0.0.1:port. */
  url?: string;
  /** Friendly name reported by adb (`model:`), when known. */
  model?: string;
}
