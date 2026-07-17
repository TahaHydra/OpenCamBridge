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

const OCBR_MAGIC: u32 = 0x5242_434f;
const RING_VERSION: u16 = 3;
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
    reserved: [u8; 16],
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
    reserved: [u64; 6],
    committed_epoch: u64,
}

include!("ring_abi_generated.rs");

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

    fn read_latest(&self, after_sequence: u64) -> Result<Vec<u8>, String> {
        unsafe {
            let base = self.view.Value as *const u8;
            let ring = &*(base as *const RingHeader);
            self.validate_header(ring)?;
            let published_sequence = ring.published_sequence.load(Ordering::Acquire);
            if published_sequence <= after_sequence {
                return Ok(Vec::new());
            }
            let slot_index = ring.published_slot.load(Ordering::Acquire) as usize;
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
            let mut response = vec![0u8; PREVIEW_HEADER_SIZE + expected];
            response[0..4].copy_from_slice(b"NVPR");
            response[4..6].copy_from_slice(&1u16.to_le_bytes());
            response[6..8].copy_from_slice(&(PREVIEW_HEADER_SIZE as u16).to_le_bytes());
            response[8..16].copy_from_slice(&metadata.sequence.to_le_bytes());
            response[16..24].copy_from_slice(&metadata.capture_timestamp_ns.to_le_bytes());
            response[24..28].copy_from_slice(&metadata.width.to_le_bytes());
            response[28..32].copy_from_slice(&metadata.height.to_le_bytes());
            response[32..36].copy_from_slice(&metadata.y_stride.to_le_bytes());
            response[36..40].copy_from_slice(&metadata.uv_stride.to_le_bytes());
            response[40..44].copy_from_slice(&metadata.payload_size.to_le_bytes());
            response[44..48].copy_from_slice(&metadata.flags.to_le_bytes());
            copy_nonoverlapping(
                base.add(metadata.data_offset as usize),
                response.as_mut_ptr().add(PREVIEW_HEADER_SIZE),
                expected,
            );
            fence(Ordering::Acquire);
            let final_epoch = std::ptr::read_volatile(&(*slot_ptr).committed_epoch);
            if final_epoch != first_epoch {
                return Ok(Vec::new());
            }
            Ok(response)
        }
    }
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
            }),
        }
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
    match reader.mapping.as_ref().unwrap().read_latest(after_sequence) {
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
}
