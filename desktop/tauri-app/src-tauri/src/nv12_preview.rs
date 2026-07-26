use std::ptr::copy_nonoverlapping;
use std::sync::atomic::{fence, AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::ipc::Response;
use windows::core::w;
use windows::Win32::Foundation::{CloseHandle, GENERIC_READ, HANDLE};
use windows::Win32::Storage::FileSystem::{
    CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING,
};
use windows::Win32::System::Memory::{
    CreateFileMappingW, MapViewOfFile, OpenFileMappingW, UnmapViewOfFile, FILE_MAP_READ,
    MEMORY_MAPPED_VIEW_ADDRESS, PAGE_READONLY,
};

use crate::sync_state::RecoverMutex;

// Desktop in-app preview only (never applied to the ring the virtual camera
// reads). A raw NV12 frame at source resolution is large (e.g. ~3.1MB at
// 1920x1080/1080x1920), and sending one over Tauri IPC every ~33ms is ~90MB/s
// of JS<->Rust transfer -- a real bottleneck that made the preview's own frame
// rate lag well behind the actual (fine) producer/ring rate. Downscaling here
// cuts that transfer ~4x with a cheap nearest-neighbor sample.
const PREVIEW_MAX_DIMENSION: usize = 960;

const OCBR_MAGIC: u32 = 0x5242_434f;
const RING_VERSION: u16 = 4;
const FORMAT_NV12: u32 = 2;
const MAX_NV12_SIZE: usize = 1920 * 1080 * 3 / 2;
const SLOT_SIZE: usize = SLOT_HEADER_SIZE + MAX_NV12_SIZE;
const MAPPING_SIZE: usize = RING_HEADER_SIZE + SLOT_COUNT * SLOT_SIZE;
const PREVIEW_HEADER_SIZE: usize = 48;

// ABI source of truth: protocol/ring-abi.schema.json. Compile-time generated
// checks below bind every field type, size, and offset to C++ and the producer.
#[repr(C)]
struct RingHeader {
    magic: u32,
    version: u16,
    header_size: u16,
    slot_count: u32,
    slot_size: u32,
    max_width: u32,
    max_height: u32,
    published_slot: AtomicU32,
    flags: u32,
    published_sequence: AtomicU64,
    producer_heartbeat_qpc: AtomicU64,
    consumer_width: AtomicU32,
    consumer_height: AtomicU32,
    consumer_fps_num: AtomicU32,
    consumer_fps_den: AtomicU32,
    virtual_camera_unique_frames: AtomicU64,
    repeated_virtual_camera_samples: AtomicU64,
    consumer_attached: AtomicU32,
    consumer_pid: AtomicU32,
    consumer_heartbeat_qpc: AtomicU64,
    sample_requests: AtomicU64,
    ring_read_attempts: AtomicU64,
    ring_read_successes: AtomicU64,
    ring_validation_failures: AtomicU64,
    sample_copy_failures: AtomicU64,
    last_ring_error: AtomicI32,
    negotiated_subtype: AtomicU32,
    last_accepted_sequence: AtomicU64,
    ring_abi_hash: u64,
    installed_dll_build_hash: [u8; 32],
    producer_build_hash: [u8; 32],
    producer_fps_num: AtomicU32,
    producer_fps_den: AtomicU32,
    resize_backend: AtomicU32,
    resize_failures: AtomicU32,
    /// Monotonic count of ring writes; a frame lands in
    /// `ring_write_sequence % slot_count`. This is what lets a consumer holding a
    /// cursor read frames in order and know when one it wanted was overwritten;
    /// `published_slot` only ever names the newest.
    ring_write_sequence: AtomicU64,
    /// Bumped on stream restart or geometry change, so the cursor resets rather
    /// than reading the discontinuity as loss.
    stream_generation: AtomicU64,
    ring_frames_overwritten: AtomicU64,
    /// Playout telemetry, written by the virtual camera. These answer WHY a correction
    /// happened, which the unique/repeated counters alone cannot.
    playout_buffer_depth_ns: AtomicU64,
    playout_target_delay_ns: AtomicU64,
    playout_late_dropped: AtomicU64,
    playout_underruns: AtomicU64,
    playout_scheduler_resets: AtomicU64,
    playout_clock_ppm: AtomicI32,
    playout_max_output_gap_ms: AtomicU32,
    reserved: [u64; 1],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SlotHeader {
    write_epoch: u64,
    sequence: u64,
    capture_timestamp_ns: u64,
    receive_timestamp_ns: u64,
    decode_timestamp_ns: u64,
    width: u32,
    height: u32,
    y_stride: u32,
    uv_stride: u32,
    pixel_format: u32,
    payload_size: u32,
    flags: u32,
    data_offset: u32,
    /// The `ring_write_sequence` this slot was written under; compared against the
    /// index a cursor derived, so a mismatch is an overwrite.
    ring_sequence: u64,
    stream_generation: u64,
    ring_write_timestamp_ns: u64,
    /// Sender cadence carried through from the OCB2 header; 0 when unknown.
    send_delta_us: u32,
    reserved_tail: u32,
    reserved: [u64; 2],
    committed_epoch: u64,
}

include!("ring_abi_generated.rs");

struct PreviewRead {
    bytes: Vec<u8>,
    frame_sequence: Option<u64>,
    stream_generation: u64,
    ring_write_sequence: u64,
    width: u32,
    height: u32,
    torn_slots_rejected: u64,
}

/// Scale down to at most `PREVIEW_MAX_DIMENSION` on the long edge, preserving
/// aspect ratio, with both output dimensions kept even (required for 4:2:0).
/// Returns the input unchanged if it is already within the cap.
fn downscale_dimensions(width: usize, height: usize) -> (usize, usize) {
    let max_dim = width.max(height);
    if max_dim <= PREVIEW_MAX_DIMENSION {
        return (width, height);
    }
    let scale = PREVIEW_MAX_DIMENSION as f64 / max_dim as f64;
    let new_w = (((width as f64 * scale) as usize) & !1).max(2);
    let new_h = (((height as f64 * scale) as usize) & !1).max(2);
    (new_w, new_h)
}

/// Nearest-neighbor downsample of a tightly-packed NV12 frame (Y plane
/// followed by interleaved UV) into `out`, which must be exactly
/// `out_width*out_height + out_width*(out_height/2)` bytes. Output strides are
/// equal to `out_width` (no padding) for both planes.
unsafe fn downscale_nv12(
    y_src: *const u8,
    y_src_stride: usize,
    uv_src: *const u8,
    uv_src_stride: usize,
    src_width: usize,
    src_height: usize,
    out_width: usize,
    out_height: usize,
    out: &mut [u8],
) {
    let y_out_len = out_width * out_height;
    let (y_out, uv_out) = out.split_at_mut(y_out_len);
    for oy in 0..out_height {
        let sy = oy * src_height / out_height;
        let src_row = y_src.add(sy * y_src_stride);
        let dst_row = &mut y_out[oy * out_width..(oy + 1) * out_width];
        for (ox, dst) in dst_row.iter_mut().enumerate() {
            let sx = ox * src_width / out_width;
            *dst = *src_row.add(sx);
        }
    }
    let uv_out_height = out_height / 2;
    let uv_out_pairs = out_width / 2;
    let uv_src_height = src_height / 2;
    let uv_src_pairs = src_width / 2;
    for oy in 0..uv_out_height {
        let sy = oy * uv_src_height / uv_out_height;
        let src_row = uv_src.add(sy * uv_src_stride);
        let dst_row = &mut uv_out[oy * out_width..(oy + 1) * out_width];
        for ox_pair in 0..uv_out_pairs {
            let sx_pair = ox_pair * uv_src_pairs / uv_out_pairs;
            let src = src_row.add(sx_pair * 2);
            dst_row[ox_pair * 2] = *src;
            dst_row[ox_pair * 2 + 1] = *src.add(1);
        }
    }
}

/// Fill the 48-byte NVPR preview header (magic/version/geometry/timestamps),
/// leaving the payload (already written at `PREVIEW_HEADER_SIZE`) untouched.
fn write_preview_header(
    response: &mut [u8],
    metadata: &SlotHeader,
    width: u32,
    height: u32,
    y_stride: u32,
    uv_stride: u32,
    payload_size: u32,
) {
    response[0..4].copy_from_slice(b"NVPR");
    response[4..6].copy_from_slice(&1u16.to_le_bytes());
    response[6..8].copy_from_slice(&(PREVIEW_HEADER_SIZE as u16).to_le_bytes());
    response[8..16].copy_from_slice(&metadata.sequence.to_le_bytes());
    response[16..24].copy_from_slice(&metadata.capture_timestamp_ns.to_le_bytes());
    response[24..28].copy_from_slice(&width.to_le_bytes());
    response[28..32].copy_from_slice(&height.to_le_bytes());
    response[32..36].copy_from_slice(&y_stride.to_le_bytes());
    response[36..40].copy_from_slice(&uv_stride.to_le_bytes());
    response[40..44].copy_from_slice(&payload_size.to_le_bytes());
    response[44..48].copy_from_slice(&metadata.flags.to_le_bytes());
}

struct Mapping {
    file: Option<HANDLE>,
    mapping: HANDLE,
    view: MEMORY_MAPPED_VIEW_ADDRESS,
}

unsafe impl Send for Mapping {}

impl Drop for Mapping {
    fn drop(&mut self) {
        unsafe {
            let _ = UnmapViewOfFile(self.view);
            let _ = CloseHandle(self.mapping);
            if let Some(file) = self.file {
                let _ = CloseHandle(file);
            }
        }
    }
}

impl Mapping {
    fn open() -> Result<Self, String> {
        // Match producer and virtual-camera preference. The named global map is
        // only a compatibility fallback when the service-safe file ring cannot
        // be opened.
        Self::open_file().or_else(|file_error| {
            Self::open_global().map_err(|global_error| format!("{file_error}; {global_error}"))
        })
    }

    fn open_file() -> Result<Self, String> {
        unsafe {
            let file = CreateFileW(
                w!("C:\\ProgramData\\OpenCamBridge\\framebuffer.bin"),
                GENERIC_READ.0,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                None,
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL,
                None,
            )
            .map_err(|e| format!("NV12 preview ring is not available: {e}"))?;
            let mapping =
                CreateFileMappingW(file, None, PAGE_READONLY, 0, MAPPING_SIZE as u32, None)
                    .map_err(|e| {
                        let _ = CloseHandle(file);
                        format!("NV12 preview file mapping failed: {e}")
                    })?;
            let view = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, MAPPING_SIZE);
            if view.Value.is_null() {
                let _ = CloseHandle(mapping);
                let _ = CloseHandle(file);
                return Err("NV12 preview MapViewOfFile failed".to_string());
            }
            Ok(Self {
                file: Some(file),
                mapping,
                view,
            })
        }
    }

    fn open_global() -> Result<Self, String> {
        unsafe {
            let mapping = OpenFileMappingW(
                FILE_MAP_READ.0,
                false,
                w!("Global\\OpenCamBridgeFrameBuffer"),
            )
            .map_err(|e| format!("NV12 global fallback mapping unavailable: {e}"))?;
            let view = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, MAPPING_SIZE);
            if view.Value.is_null() {
                let _ = CloseHandle(mapping);
                return Err("NV12 global fallback MapViewOfFile failed".into());
            }
            Ok(Self {
                file: None,
                mapping,
                view,
            })
        }
    }

    fn progress(&self) -> Result<(u64, u64, u64, u64, [u8; 32]), String> {
        unsafe {
            let ring = &*(self.view.Value as *const RingHeader);
            Self::validate_header(ring)?;
            if ring.producer_build_hash.iter().all(|value| *value == 0) {
                return Err("NV12 preview rejected ring without producer build hash".into());
            }
            Ok((
                ring.published_sequence.load(Ordering::Acquire),
                ring.producer_heartbeat_qpc.load(Ordering::Acquire),
                ring.ring_write_sequence.load(Ordering::Acquire),
                ring.stream_generation.load(Ordering::Acquire),
                ring.producer_build_hash,
            ))
        }
    }

    fn validate_header(ring: &RingHeader) -> Result<(), String> {
        if ring.magic != OCBR_MAGIC
            || ring.version != RING_VERSION
            || ring.header_size as usize != RING_HEADER_SIZE
            || ring.slot_count as usize != SLOT_COUNT
            || ring.slot_size as usize != SLOT_SIZE
            || ring.ring_abi_hash != RING_ABI_HASH
        {
            Err(format!(
                "NV12 preview rejected incompatible ring ABI (layout {})",
                &RING_ABI_LAYOUT_SHA256[..12]
            ))
        } else {
            Ok(())
        }
    }

    /// Read the newest committed frame without participating in virtual-camera
    /// playout. The in-app preview is an observer: it never advances consumer
    /// state and it returns empty only when the newest source sequence was
    /// already returned to this frontend.
    fn read_newest(
        &self,
        after_sequence: u64,
        last_returned_generation: u64,
    ) -> Result<PreviewRead, String> {
        unsafe {
            read_newest_from_base(
                self.view.Value as *const u8,
                after_sequence,
                last_returned_generation,
            )
        }
    }
}

unsafe fn read_newest_from_base(
    base: *const u8,
    after_sequence: u64,
    last_returned_generation: u64,
) -> Result<PreviewRead, String> {
    let ring = &*(base as *const RingHeader);
    Mapping::validate_header(ring)?;
    let ring_write_sequence = ring.ring_write_sequence.load(Ordering::Acquire);
    let stream_generation = ring.stream_generation.load(Ordering::Acquire);
    let mut newest: Option<(usize, SlotHeader, u64)> = None;
    let mut torn_slots_rejected = 0u64;

    // Select from stable header snapshots. Scanning all slots is deliberate:
    // `published_slot` is only a hint and a concurrent producer can move it
    // while this observer is choosing a frame.
    for index in 0..SLOT_COUNT {
        let slot_offset = RING_HEADER_SIZE + index * SLOT_SIZE;
        let slot_ptr = base.add(slot_offset) as *const SlotHeader;
        let before = std::ptr::read_volatile(&(*slot_ptr).committed_epoch);
        fence(Ordering::Acquire);
        let metadata = std::ptr::read(slot_ptr);
        fence(Ordering::Acquire);
        let after = std::ptr::read_volatile(&(*slot_ptr).committed_epoch);
        if before == 0 {
            continue;
        }
        if before != after || before != metadata.write_epoch || before & 1 == 0 {
            torn_slots_rejected += 1;
            continue;
        }
        if metadata.ring_sequence == 0
            || metadata.ring_sequence > ring_write_sequence
            || metadata.stream_generation != stream_generation
            || metadata.pixel_format != FORMAT_NV12
            || ((metadata.ring_sequence - 1) % SLOT_COUNT as u64) as usize != index
        {
            continue;
        }
        if newest
            .as_ref()
            .map(|(_, current, _)| metadata.ring_sequence > current.ring_sequence)
            .unwrap_or(true)
        {
            newest = Some((index, metadata, before));
        }
    }

    let Some((slot_index, metadata, first_epoch)) = newest else {
        return Ok(PreviewRead {
            bytes: Vec::new(),
            frame_sequence: None,
            stream_generation,
            ring_write_sequence,
            width: 0,
            height: 0,
            torn_slots_rejected,
        });
    };

    // Source sequence numbers may restart when generation changes. The first
    // valid frame of the new generation must therefore bypass after_sequence.
    if metadata.stream_generation == last_returned_generation && metadata.sequence == after_sequence
    {
        return Ok(PreviewRead {
            bytes: Vec::new(),
            frame_sequence: None,
            stream_generation,
            ring_write_sequence,
            width: 0,
            height: 0,
            torn_slots_rejected,
        });
    }

    let width = metadata.width as usize;
    let height = metadata.height as usize;
    let y_stride = metadata.y_stride as usize;
    let uv_stride = metadata.uv_stride as usize;
    if width == 0
        || height == 0
        || width > 1920
        || height > 1920
        || width & 1 != 0
        || height & 1 != 0
        || y_stride < width
        || uv_stride < width
        || y_stride > 8192
        || uv_stride > 8192
    {
        return Err("NV12 preview rejected invalid dimensions or strides".to_string());
    }
    let expected = y_stride
        .checked_mul(height)
        .and_then(|y| {
            uv_stride
                .checked_mul(height / 2)
                .and_then(|uv| y.checked_add(uv))
        })
        .ok_or("NV12 preview frame size overflow")?;
    let slot_offset = RING_HEADER_SIZE + slot_index * SLOT_SIZE;
    if expected != metadata.payload_size as usize
        || expected > MAX_NV12_SIZE
        || metadata.data_offset as usize != slot_offset + SLOT_HEADER_SIZE
        || metadata.data_offset as usize + expected > MAPPING_SIZE
    {
        return Err("NV12 preview rejected invalid payload bounds".to_string());
    }

    let (out_width, out_height) = downscale_dimensions(width, height);
    let y_src = base.add(metadata.data_offset as usize);
    let uv_src = y_src.add(y_stride * height);
    let response = if out_width == width && out_height == height {
        let mut response = vec![0u8; PREVIEW_HEADER_SIZE + expected];
        copy_nonoverlapping(
            y_src,
            response.as_mut_ptr().add(PREVIEW_HEADER_SIZE),
            expected,
        );
        write_preview_header(
            &mut response,
            &metadata,
            width as u32,
            height as u32,
            y_stride as u32,
            uv_stride as u32,
            expected as u32,
        );
        response
    } else {
        let out_payload = out_width * out_height + out_width * (out_height / 2);
        let mut response = vec![0u8; PREVIEW_HEADER_SIZE + out_payload];
        downscale_nv12(
            y_src,
            y_stride,
            uv_src,
            uv_stride,
            width,
            height,
            out_width,
            out_height,
            &mut response[PREVIEW_HEADER_SIZE..],
        );
        write_preview_header(
            &mut response,
            &metadata,
            out_width as u32,
            out_height as u32,
            out_width as u32,
            out_width as u32,
            out_payload as u32,
        );
        response
    };

    fence(Ordering::Acquire);
    let slot_ptr = base.add(slot_offset) as *const SlotHeader;
    let final_epoch = std::ptr::read_volatile(&(*slot_ptr).committed_epoch);
    let final_generation = ring.stream_generation.load(Ordering::Acquire);
    if final_epoch != first_epoch || final_generation != stream_generation {
        torn_slots_rejected += 1;
        return Ok(PreviewRead {
            bytes: Vec::new(),
            frame_sequence: None,
            stream_generation: final_generation,
            ring_write_sequence: ring.ring_write_sequence.load(Ordering::Acquire),
            width: 0,
            height: 0,
            torn_slots_rejected,
        });
    }

    Ok(PreviewRead {
        bytes: response,
        frame_sequence: Some(metadata.sequence),
        stream_generation,
        ring_write_sequence,
        width: out_width as u32,
        height: out_height as u32,
        torn_slots_rejected,
    })
}

pub struct Nv12PreviewReader {
    state: Mutex<ReaderState>,
}

struct ReaderState {
    mapping: Option<Mapping>,
    producer_instance: u64,
    producer_pid: Option<u32>,
    last_sequence: u64,
    last_heartbeat: u64,
    last_progress: Instant,
    last_returned_generation: u64,
    ring_write_sequence: u64,
    stream_generation: u64,
    preview_command_calls: u64,
    non_empty_responses: u64,
    empty_responses: u64,
    last_returned_sequence: u64,
    last_ipc_payload_bytes: usize,
    last_width: u32,
    last_height: u32,
    torn_slots_rejected: u64,
    last_error: String,
}

impl Nv12PreviewReader {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(ReaderState {
                mapping: None,
                producer_instance: 0,
                producer_pid: None,
                last_sequence: 0,
                last_heartbeat: 0,
                last_progress: Instant::now(),
                last_returned_generation: 0,
                ring_write_sequence: 0,
                stream_generation: 0,
                preview_command_calls: 0,
                non_empty_responses: 0,
                empty_responses: 0,
                last_returned_sequence: 0,
                last_ipc_payload_bytes: 0,
                last_width: 0,
                last_height: 0,
                torn_slots_rejected: 0,
                last_error: String::new(),
            }),
        }
    }
}

#[derive(serde::Serialize)]
pub struct Nv12PreviewDiagnostics {
    pub ring_alive: bool,
    pub ring_write_sequence: u64,
    pub stream_generation: u64,
    pub preview_command_calls: u64,
    pub non_empty_responses: u64,
    pub empty_responses: u64,
    pub last_returned_sequence: u64,
    pub last_ipc_payload_bytes: usize,
    pub last_width: u32,
    pub last_height: u32,
    pub torn_slots_rejected: u64,
    pub last_error: String,
}

#[tauri::command]
pub fn get_nv12_preview_diagnostics(
    state: tauri::State<'_, Nv12PreviewReader>,
) -> Nv12PreviewDiagnostics {
    let reader = state.state.lock_recover();
    Nv12PreviewDiagnostics {
        ring_alive: reader.mapping.is_some()
            && reader.last_progress.elapsed() < Duration::from_secs(2),
        ring_write_sequence: reader.ring_write_sequence,
        stream_generation: reader.stream_generation,
        preview_command_calls: reader.preview_command_calls,
        non_empty_responses: reader.non_empty_responses,
        empty_responses: reader.empty_responses,
        last_returned_sequence: reader.last_returned_sequence,
        last_ipc_payload_bytes: reader.last_ipc_payload_bytes,
        last_width: reader.last_width,
        last_height: reader.last_height,
        torn_slots_rejected: reader.torn_slots_rejected,
        last_error: reader.last_error.clone(),
    }
}

#[tauri::command]
pub fn get_nv12_preview_frame(
    after_sequence: u64,
    state: tauri::State<'_, Nv12PreviewReader>,
    manager: tauri::State<'_, crate::virtualcam::VirtualCamManager>,
) -> Result<Response, String> {
    let (producer_pid, producer_instance, producer_streaming, expected_build_hash) =
        manager.preview_identity();
    let mut reader = state.state.lock_recover();
    if reader.producer_instance != producer_instance || reader.producer_pid != producer_pid {
        reader.mapping = None;
        reader.producer_instance = producer_instance;
        reader.producer_pid = producer_pid;
        reader.last_sequence = 0;
        reader.last_heartbeat = 0;
        reader.last_progress = Instant::now();
        reader.last_returned_generation = 0;
        reader.ring_write_sequence = 0;
        reader.stream_generation = 0;
        reader.preview_command_calls = 0;
        reader.non_empty_responses = 0;
        reader.empty_responses = 0;
        reader.last_returned_sequence = 0;
        reader.last_ipc_payload_bytes = 0;
        reader.last_width = 0;
        reader.last_height = 0;
        reader.torn_slots_rejected = 0;
        reader.last_error.clear();
    }
    reader.preview_command_calls += 1;
    if reader.mapping.is_none() {
        match Mapping::open() {
            Ok(opened) => {
                reader.mapping = Some(opened);
                reader.last_error.clear();
            }
            Err(error) => {
                reader.empty_responses += 1;
                reader.last_ipc_payload_bytes = 0;
                reader.last_error = error;
                return Ok(Response::new(Vec::new()));
            }
        }
    }
    let (sequence, heartbeat, ring_write_sequence, stream_generation, build_hash) =
        match reader.mapping.as_ref().unwrap().progress() {
            Ok(progress) => progress,
            Err(error) => {
                reader.mapping = None;
                reader.last_error = error.clone();
                return Err(error);
            }
        };
    reader.ring_write_sequence = ring_write_sequence;
    reader.stream_generation = stream_generation;
    if let Some(expected) = expected_build_hash.filter(|value| !value.is_empty()) {
        let actual: String = build_hash
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        if !actual.eq_ignore_ascii_case(&expected) {
            reader.mapping = None;
            let error = "NV12 preview rejected ring from a different producer build".to_string();
            reader.last_error = error.clone();
            return Err(error);
        }
    }
    if sequence != reader.last_sequence || heartbeat != reader.last_heartbeat {
        reader.last_sequence = sequence;
        reader.last_heartbeat = heartbeat;
        reader.last_progress = Instant::now();
        reader.last_error.clear();
    } else if producer_streaming && reader.last_progress.elapsed() >= Duration::from_secs(2) {
        // A valid but stale fallback mapping is indistinguishable from a live
        // ring unless both sequence and producer heartbeat are observed.
        reader.mapping = None;
        reader.last_progress = Instant::now();
        reader.empty_responses += 1;
        reader.last_ipc_payload_bytes = 0;
        reader.last_error = "NV12 preview ring stopped advancing".into();
        return Ok(Response::new(Vec::new()));
    }
    let last_returned_generation = reader.last_returned_generation;
    let result = reader
        .mapping
        .as_ref()
        .unwrap()
        .read_newest(after_sequence, last_returned_generation);
    match result {
        Ok(frame) => {
            reader.ring_write_sequence = frame.ring_write_sequence;
            reader.stream_generation = frame.stream_generation;
            reader.torn_slots_rejected += frame.torn_slots_rejected;
            reader.last_ipc_payload_bytes = frame.bytes.len();
            if let Some(sequence) = frame.frame_sequence {
                reader.non_empty_responses += 1;
                reader.last_returned_sequence = sequence;
                reader.last_returned_generation = frame.stream_generation;
                reader.last_width = frame.width;
                reader.last_height = frame.height;
            } else {
                reader.empty_responses += 1;
            }
            Ok(Response::new(frame.bytes))
        }
        Err(error) => {
            reader.mapping = None;
            reader.last_error = error.clone();
            Err(error)
        }
    }
}

const _: () = {
    assert!(std::mem::size_of::<RingHeader>() == RING_HEADER_SIZE);
    assert!(std::mem::size_of::<SlotHeader>() == SLOT_HEADER_SIZE);
};

#[cfg(test)]
mod tests {
    use super::*;

    struct TestRing {
        // u64 backing guarantees the alignment required by RingHeader/SlotHeader.
        words: Vec<u64>,
    }

    impl TestRing {
        fn new() -> Self {
            let mut ring = Self {
                words: vec![0u64; MAPPING_SIZE.div_ceil(std::mem::size_of::<u64>())],
            };
            unsafe {
                let mut header: RingHeader = std::mem::zeroed();
                header.magic = OCBR_MAGIC;
                header.version = RING_VERSION;
                header.header_size = RING_HEADER_SIZE as u16;
                header.slot_count = SLOT_COUNT as u32;
                header.slot_size = SLOT_SIZE as u32;
                header.max_width = 1920;
                header.max_height = 1920;
                header.ring_abi_hash = RING_ABI_HASH;
                std::ptr::write(ring.base_mut() as *mut RingHeader, header);
            }
            ring
        }

        fn base(&self) -> *const u8 {
            self.words.as_ptr() as *const u8
        }

        fn base_mut(&mut self) -> *mut u8 {
            self.words.as_mut_ptr() as *mut u8
        }

        fn write_frame(
            &mut self,
            ring_sequence: u64,
            stream_generation: u64,
            source_sequence: u64,
            width: usize,
            height: usize,
        ) {
            let index = ((ring_sequence - 1) % SLOT_COUNT as u64) as usize;
            let slot_offset = RING_HEADER_SIZE + index * SLOT_SIZE;
            let payload_size = width * height * 3 / 2;
            let epoch = ring_sequence * 2 | 1;
            unsafe {
                let mut slot: SlotHeader = std::mem::zeroed();
                slot.write_epoch = epoch;
                slot.sequence = source_sequence;
                slot.capture_timestamp_ns = source_sequence * 1_000_000;
                slot.width = width as u32;
                slot.height = height as u32;
                slot.y_stride = width as u32;
                slot.uv_stride = width as u32;
                slot.pixel_format = FORMAT_NV12;
                slot.payload_size = payload_size as u32;
                slot.data_offset = (slot_offset + SLOT_HEADER_SIZE) as u32;
                slot.ring_sequence = ring_sequence;
                slot.stream_generation = stream_generation;
                slot.committed_epoch = epoch;
                std::ptr::write(self.base_mut().add(slot_offset) as *mut SlotHeader, slot);
                std::ptr::write_bytes(
                    self.base_mut().add(slot_offset + SLOT_HEADER_SIZE),
                    128,
                    payload_size,
                );
                let header = &*(self.base() as *const RingHeader);
                header
                    .stream_generation
                    .store(stream_generation, Ordering::Release);
                header
                    .published_sequence
                    .store(source_sequence, Ordering::Release);
                header.published_slot.store(index as u32, Ordering::Release);
                header
                    .ring_write_sequence
                    .store(ring_sequence, Ordering::Release);
            }
        }

        fn tear_frame(&mut self, ring_sequence: u64) {
            let index = ((ring_sequence - 1) % SLOT_COUNT as u64) as usize;
            let slot_offset = RING_HEADER_SIZE + index * SLOT_SIZE;
            unsafe {
                let slot = self.base_mut().add(slot_offset) as *mut SlotHeader;
                (*slot).write_epoch = ring_sequence * 2 | 1;
                (*slot).committed_epoch = ring_sequence * 2;
                (*slot).ring_sequence = ring_sequence;
                (*slot).stream_generation = (&*(self.base() as *const RingHeader))
                    .stream_generation
                    .load(Ordering::Acquire);
                (*slot).pixel_format = FORMAT_NV12;
                (&*(self.base() as *const RingHeader))
                    .ring_write_sequence
                    .store(ring_sequence, Ordering::Release);
            }
        }
    }

    fn preview_u64(bytes: &[u8], offset: usize) -> u64 {
        u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap())
    }

    fn preview_u32(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
    }

    #[test]
    fn ring_abi_layout_matches_producer_and_virtual_camera() {
        assert_eq!(std::mem::size_of::<RingHeader>(), RING_HEADER_SIZE);
        assert_eq!(std::mem::size_of::<SlotHeader>(), 128);
        assert_eq!(std::mem::offset_of!(RingHeader, consumer_attached), 80);
        assert_eq!(std::mem::offset_of!(RingHeader, producer_build_hash), 192);
    }

    #[test]
    fn newest_reader_returns_first_newer_and_new_generation_but_not_duplicates_or_torn_slots() {
        let mut ring = TestRing::new();
        ring.write_frame(1, 1, 41, 640, 480);

        let first = unsafe { read_newest_from_base(ring.base(), 0, 0) }.unwrap();
        assert!(
            !first.bytes.is_empty(),
            "populated ring must return immediately"
        );
        assert_eq!(preview_u64(&first.bytes, 8), 41);

        let duplicate = unsafe { read_newest_from_base(ring.base(), 41, 1) }.unwrap();
        assert!(
            duplicate.bytes.is_empty(),
            "same source sequence must be empty"
        );

        ring.write_frame(2, 1, 42, 640, 480);
        let newer = unsafe { read_newest_from_base(ring.base(), 41, 1) }.unwrap();
        assert_eq!(preview_u64(&newer.bytes, 8), 42);

        ring.tear_frame(3);
        let torn = unsafe { read_newest_from_base(ring.base(), 42, 1) }.unwrap();
        assert!(torn.bytes.is_empty());
        assert!(torn.torn_slots_rejected >= 1);

        // Source sequence restarted below after_sequence, but generation changed.
        ring.write_frame(4, 2, 1, 640, 480);
        let restarted = unsafe { read_newest_from_base(ring.base(), 42, 1) }.unwrap();
        assert_eq!(preview_u64(&restarted.bytes, 8), 1);
        assert_eq!(restarted.stream_generation, 2);
    }

    #[test]
    fn downscale_dimensions_preserves_aspect_and_caps_long_edge() {
        assert_eq!(downscale_dimensions(1920, 1080), (960, 540));
        assert_eq!(downscale_dimensions(1080, 1920), (540, 960));
        assert_eq!(downscale_dimensions(1280, 720), (960, 540));
        // Already within the cap: passed through unchanged.
        assert_eq!(downscale_dimensions(640, 480), (640, 480));
        assert_eq!(downscale_dimensions(960, 540), (960, 540));
    }

    #[test]
    fn newest_reader_downscales_landscape_and_portrait_with_valid_payloads() {
        let mut ring = TestRing::new();
        ring.write_frame(1, 1, 1, 1920, 1080);
        let landscape = unsafe { read_newest_from_base(ring.base(), 0, 0) }.unwrap();
        assert_eq!(
            (
                preview_u32(&landscape.bytes, 24),
                preview_u32(&landscape.bytes, 28)
            ),
            (960, 540)
        );
        assert_eq!(
            landscape.bytes.len(),
            PREVIEW_HEADER_SIZE + 960 * 540 * 3 / 2
        );

        ring.write_frame(2, 2, 1, 1080, 1920);
        let portrait = unsafe { read_newest_from_base(ring.base(), 1, 1) }.unwrap();
        assert_eq!(
            (
                preview_u32(&portrait.bytes, 24),
                preview_u32(&portrait.bytes, 28)
            ),
            (540, 960)
        );
        assert_eq!(
            portrait.bytes.len(),
            PREVIEW_HEADER_SIZE + 540 * 960 * 3 / 2
        );
    }

    #[test]
    fn incompatible_ring_abi_is_a_visible_error() {
        let mut ring = TestRing::new();
        unsafe {
            (*(ring.base_mut() as *mut RingHeader)).version = RING_VERSION + 1;
        }
        let error = unsafe { read_newest_from_base(ring.base(), 0, 0) }
            .err()
            .expect("ABI mismatch must fail");
        assert!(error.contains("incompatible ring ABI"));
    }

    #[test]
    fn downscale_nv12_produces_tightly_packed_output_of_expected_size() {
        // 4x4 source: solid Y=200, solid U=10,V=20. 2x2 downscale should
        // preserve the flat colour exactly (nearest-neighbor on a uniform
        // image can't introduce artifacts) and be tightly packed (stride ==
        // out_width, no padding).
        let src_w = 4usize;
        let src_h = 4usize;
        let y_src = vec![200u8; src_w * src_h];
        let uv_src = vec![10u8, 20u8].repeat((src_w / 2) * (src_h / 2));
        let out_w = 2usize;
        let out_h = 2usize;
        let mut out = vec![0u8; out_w * out_h + out_w * (out_h / 2)];
        unsafe {
            downscale_nv12(
                y_src.as_ptr(),
                src_w,
                uv_src.as_ptr(),
                src_w,
                src_w,
                src_h,
                out_w,
                out_h,
                &mut out,
            );
        }
        let y_len = out_w * out_h;
        assert!(out[..y_len].iter().all(|&b| b == 200));
        assert_eq!(&out[y_len..], &[10, 20]);
    }
}
