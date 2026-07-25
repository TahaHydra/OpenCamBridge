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

    fn progress(&self) -> Result<(u64, u64, [u8; 32]), String> {
        unsafe {
            let ring = &*(self.view.Value as *const RingHeader);
            self.validate_header(ring)?;
            if ring.producer_build_hash.iter().all(|value| *value == 0) {
                return Err("NV12 preview rejected ring without producer build hash".into());
            }
            Ok((
                ring.published_sequence.load(Ordering::Acquire),
                ring.producer_heartbeat_qpc.load(Ordering::Acquire),
                ring.producer_build_hash,
            ))
        }
    }

    fn validate_header(&self, ring: &RingHeader) -> Result<(), String> {
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

    fn read_latest(
        &self,
        after_sequence: u64,
        cursor: &mut PreviewCursor,
    ) -> Result<Vec<u8>, String> {
        unsafe {
            let base = self.view.Value as *const u8;
            let ring = &*(base as *const RingHeader);
            self.validate_header(ring)?;
            let published_sequence = ring.published_sequence.load(Ordering::Acquire);
            if published_sequence <= after_sequence {
                return Ok(Vec::new());
            }
            // Slot choice comes from the monotonic write sequence rather than
            // `published_slot`. Both name the same slot while the preview still takes
            // the newest frame; the difference is that the write sequence also says
            // what came before it, which is what the cursor accounting needs.
            let write_sequence = ring.ring_write_sequence.load(Ordering::Acquire);
            let generation = ring.stream_generation.load(Ordering::Acquire);
            let Some(selection) = ring_select_newest(
                cursor.next,
                cursor.generation,
                write_sequence,
                generation,
                SLOT_COUNT as u64,
            ) else {
                return Ok(Vec::new());
            };
            let slot_index = selection.slot_index;
            if slot_index >= SLOT_COUNT {
                return Err("NV12 preview rejected published slot index".to_string());
            }
            let slot_offset = RING_HEADER_SIZE + slot_index * SLOT_SIZE;
            let slot_ptr = base.add(slot_offset) as *const SlotHeader;
            let first_epoch = std::ptr::read_volatile(&(*slot_ptr).committed_epoch);
            fence(Ordering::Acquire);
            let metadata = std::ptr::read(slot_ptr);
            if first_epoch == 0
                || first_epoch != metadata.write_epoch
                || first_epoch & 1 == 0
                // The slot must still hold the write the cursor selected; if not, the
                // producer lapped us and the next poll picks up the newer frame.
                || metadata.ring_sequence != selection.ring_sequence
                || metadata.sequence != published_sequence
                || metadata.pixel_format != FORMAT_NV12
            {
                return Ok(Vec::new());
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
            let final_epoch = std::ptr::read_volatile(&(*slot_ptr).committed_epoch);
            if final_epoch != first_epoch {
                return Ok(Vec::new());
            }
            // Advance only after the frame is known good, so a torn read is retried
            // rather than silently counted as consumed.
            if selection.generation_changed {
                cursor.generation = generation;
                cursor.resets += 1;
            } else {
                cursor.skipped += selection.skipped;
                cursor.overwritten += selection.overwritten;
                // Counted locally and deliberately NOT written back into the ring: this
                // mapping is PAGE_READONLY/FILE_MAP_READ, so any store here would fault.
                // The virtual camera, which maps read/write, owns the shared counter.
            }
            cursor.next = selection.ring_sequence + 1;
            Ok(response)
        }
    }
}

pub struct Nv12PreviewReader {
    state: Mutex<ReaderState>,
}

/// This consumer's own place in the history ring.
///
/// Local, not shared: the preview and the virtual camera read at different rates, and
/// neither may disturb the other's accounting. The counters answer a question the
/// existing rate figures cannot — whether a hitch was this consumer reading at the
/// wrong moment (`skipped`) or the ring being too short to hold the frame at all
/// (`overwritten`).
#[derive(Default)]
struct PreviewCursor {
    next: u64,
    generation: u64,
    skipped: u64,
    overwritten: u64,
    resets: u64,
}

struct ReaderState {
    mapping: Option<Mapping>,
    producer_instance: u64,
    producer_pid: Option<u32>,
    last_sequence: u64,
    last_heartbeat: u64,
    last_progress: Instant,
    cursor: PreviewCursor,
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
                cursor: PreviewCursor::default(),
            }),
        }
    }
}

/// Preview-side ring cursor accounting, for diagnosing a hitch in the preview
/// specifically rather than in the virtual camera.
#[derive(serde::Serialize)]
pub struct Nv12PreviewCursorStats {
    /// Ring writes the preview never rendered.
    pub skipped: u64,
    /// Of those, how many the ring had already destroyed. A non-zero value here means
    /// the ring is too short for this consumer's polling interval; `skipped` alone only
    /// means it polled at the wrong moments.
    pub overwritten: u64,
    /// Producer restarts observed.
    pub resets: u64,
    /// Next ring write sequence the preview has not yet rendered.
    pub cursor: u64,
}

#[tauri::command]
pub fn get_nv12_preview_cursor_stats(
    state: tauri::State<'_, Nv12PreviewReader>,
) -> Nv12PreviewCursorStats {
    let reader = state.state.lock_recover();
    Nv12PreviewCursorStats {
        skipped: reader.cursor.skipped,
        overwritten: reader.cursor.overwritten,
        resets: reader.cursor.resets,
        cursor: reader.cursor.next,
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
        // A different producer means a different ring; the cursor cannot carry over.
        reader.cursor = PreviewCursor::default();
    }
    if reader.mapping.is_none() {
        match Mapping::open() {
            Ok(opened) => reader.mapping = Some(opened),
            Err(_) => return Ok(Response::new(Vec::new())),
        }
    }
    let (sequence, heartbeat, build_hash) = match reader.mapping.as_ref().unwrap().progress() {
        Ok(progress) => progress,
        Err(error) => {
            reader.mapping = None;
            return Err(error);
        }
    };
    if let Some(expected) = expected_build_hash.filter(|value| !value.is_empty()) {
        let actual: String = build_hash
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        if !actual.eq_ignore_ascii_case(&expected) {
            reader.mapping = None;
            return Err("NV12 preview rejected ring from a different producer build".into());
        }
    }
    if sequence != reader.last_sequence || heartbeat != reader.last_heartbeat {
        reader.last_sequence = sequence;
        reader.last_heartbeat = heartbeat;
        reader.last_progress = Instant::now();
    } else if producer_streaming && reader.last_progress.elapsed() >= Duration::from_secs(2) {
        // A valid but stale fallback mapping is indistinguishable from a live
        // ring unless both sequence and producer heartbeat are observed.
        reader.mapping = None;
        reader.last_progress = Instant::now();
        return Ok(Response::new(Vec::new()));
    }
    let ReaderState { mapping, cursor, .. } = &mut *reader;
    match mapping.as_ref().unwrap().read_latest(after_sequence, cursor) {
        Ok(bytes) => Ok(Response::new(bytes)),
        Err(error) => {
            reader.mapping = None;
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

    #[test]
    fn ring_abi_layout_matches_producer_and_virtual_camera() {
        assert_eq!(std::mem::size_of::<RingHeader>(), 256);
        assert_eq!(std::mem::size_of::<SlotHeader>(), 128);
        assert_eq!(std::mem::offset_of!(RingHeader, consumer_attached), 80);
        assert_eq!(std::mem::offset_of!(RingHeader, producer_build_hash), 192);
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
