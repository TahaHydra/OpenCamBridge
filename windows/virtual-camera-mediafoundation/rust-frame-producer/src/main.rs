use clap::{Parser, ValueEnum};
use openh264::decoder::Decoder;
use openh264::formats::YUVSource;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::ffi::c_void;
use std::io::Read;
use std::ptr::{copy_nonoverlapping, null_mut};
use std::sync::mpsc::{sync_channel, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::{sleep, spawn};
use std::time::{Duration, Instant};
mod mf_decoder;
mod ocb2;
mod playout;
#[cfg(test)]
mod playout_tests;
use windows::core::PCWSTR;
use windows::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE};
use windows::Win32::Security::Authorization::{
    ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
};
use windows::Win32::Security::{
    SetKernelObjectSecurity, DACL_SECURITY_INFORMATION, PROTECTED_DACL_SECURITY_INFORMATION,
    PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES,
};
use windows::Win32::Storage::FileSystem::{
    CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_ALWAYS, WRITE_DAC,
};
use windows::Win32::System::Memory::{
    CreateFileMappingW, MapViewOfFile, UnmapViewOfFile, FILE_MAP_ALL_ACCESS, PAGE_READWRITE,
};
use windows::Win32::System::Performance::QueryPerformanceCounter;

const OCBR_MAGIC: u32 = 0x5242434F; // "OCBR"
const RING_VERSION: u16 = 4;
const FORMAT_NV12: u32 = 2;
const MAX_NV12_SIZE: usize = 1920 * 1080 * 3 / 2;
const SLOT_SIZE: usize = SLOT_HEADER_SIZE + MAX_NV12_SIZE;
const MAX_SHM_SIZE: u32 = (RING_HEADER_SIZE + SLOT_COUNT * SLOT_SIZE) as u32;

// ABI source of truth: protocol/ring-abi.schema.json. Compile-time generated
// checks below bind every field type, size, and offset to the C++/Tauri views.
#[repr(C)]
struct OpenCamBridgeRingHeader {
    magic: u32,
    version: u16,
    header_size: u16,
    slot_count: u32,
    slot_size: u32,
    max_width: u32,
    max_height: u32,
    published_slot: std::sync::atomic::AtomicU32,
    flags: u32,
    published_sequence: std::sync::atomic::AtomicU64,
    producer_heartbeat_qpc: std::sync::atomic::AtomicU64,
    consumer_width: std::sync::atomic::AtomicU32,
    consumer_height: std::sync::atomic::AtomicU32,
    consumer_fps_num: std::sync::atomic::AtomicU32,
    consumer_fps_den: std::sync::atomic::AtomicU32,
    virtual_camera_unique_frames: std::sync::atomic::AtomicU64,
    repeated_virtual_camera_samples: std::sync::atomic::AtomicU64,
    consumer_attached: std::sync::atomic::AtomicU32,
    consumer_pid: std::sync::atomic::AtomicU32,
    consumer_heartbeat_qpc: std::sync::atomic::AtomicU64,
    sample_requests: std::sync::atomic::AtomicU64,
    ring_read_attempts: std::sync::atomic::AtomicU64,
    ring_read_successes: std::sync::atomic::AtomicU64,
    ring_validation_failures: std::sync::atomic::AtomicU64,
    sample_copy_failures: std::sync::atomic::AtomicU64,
    last_ring_error: std::sync::atomic::AtomicI32,
    negotiated_subtype: std::sync::atomic::AtomicU32,
    last_accepted_sequence: std::sync::atomic::AtomicU64,
    ring_abi_hash: u64,
    installed_dll_build_hash: [u8; 32],
    producer_build_hash: [u8; 32],
    producer_fps_num: std::sync::atomic::AtomicU32,
    producer_fps_den: std::sync::atomic::AtomicU32,
    resize_backend: std::sync::atomic::AtomicU32,
    resize_failures: std::sync::atomic::AtomicU32,
    /// Monotonic count of ring writes. The slot a frame lands in is
    /// `ring_write_sequence % slot_count`, so a consumer holding a cursor can work
    /// out exactly which slots are still live and whether its own next frame has
    /// already been overwritten. `published_slot` alone cannot answer that: it says
    /// where the newest frame is and nothing about the ones before it.
    ring_write_sequence: std::sync::atomic::AtomicU64,
    /// Bumped whenever the stream restarts or its geometry changes. Consumers reset
    /// their cursor on a change instead of reading the sequence discontinuity as
    /// dropped frames.
    stream_generation: std::sync::atomic::AtomicU64,
    /// Frames the producer overwrote that no consumer had read. Distinguishes "the
    /// ring is too short" from "the consumer is reading at the wrong time".
    ring_frames_overwritten: std::sync::atomic::AtomicU64,
    /// Playout telemetry, written by the virtual camera. These answer WHY a correction
    /// happened, which the unique/repeated counters alone cannot.
    playout_buffer_depth_ns: std::sync::atomic::AtomicU64,
    playout_target_delay_ns: std::sync::atomic::AtomicU64,
    playout_late_dropped: std::sync::atomic::AtomicU64,
    playout_underruns: std::sync::atomic::AtomicU64,
    playout_scheduler_resets: std::sync::atomic::AtomicU64,
    playout_clock_ppm: std::sync::atomic::AtomicI32,
    playout_max_output_gap_ms: std::sync::atomic::AtomicU32,
    reserved: [u64; 1],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct OpenCamBridgeSlotHeader {
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
    /// The `ring_write_sequence` value this slot was written under. A consumer that
    /// computed a slot index from its own cursor compares this: a mismatch means the
    /// frame it wanted is gone, which is overwrite detection.
    ring_sequence: u64,
    stream_generation: u64,
    /// Producer-clock time the frame was committed. Phase 3's playout schedule is
    /// built from this; on its own it makes ring residency measurable.
    ring_write_timestamp_ns: u64,
    /// Sender-side cadence carried through from the OCB2 header; 0 when unknown.
    send_delta_us: u32,
    reserved_tail: u32,
    reserved: [u64; 2],
    committed_epoch: u64,
}

include!("ring_abi_generated.rs");

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Frame source: "h264" (OCB2 + Media Foundation), "mjpeg" compatibility,
    /// or "test-pattern".
    #[arg(short, long, default_value = "test-pattern")]
    source: String,

    /// Deterministic direct-NV12 diagnostic source. Supplying this option
    /// selects test-pattern mode even when --source is omitted.
    #[arg(long, value_enum)]
    test_pattern: Option<TestPatternKind>,

    #[arg(short, long)]
    url: Option<String>,

    #[arg(long)]
    width: Option<u32>,

    #[arg(long)]
    height: Option<u32>,

    #[arg(long)]
    fps: Option<u32>,

    /// Authoritative encoded source properties selected by Android. Width and
    /// height are diagnostics until the first decoded frame/OCB2 stream-info;
    /// source FPS controls MJPEG pacing and the ring's producer timing.
    #[arg(long)]
    source_width: Option<u32>,

    #[arg(long)]
    source_height: Option<u32>,

    #[arg(long)]
    source_fps: Option<u32>,

    #[arg(long, default_value = "custom")]
    profile: String,

    /// Populated only from OPENCAMBRIDGE_TOKEN and deliberately not accepted as
    /// a command-line argument (same-user processes can inspect argv).
    #[arg(skip)]
    token: Option<String>,
}

#[derive(Clone, Copy, Debug, ValueEnum)]
enum TestPatternKind {
    Nv12Bars,
    Nv12Gradient,
    SolidRed,
    SolidGreen,
    SolidBlue,
}

impl TestPatternKind {
    fn as_str(self) -> &'static str {
        match self {
            Self::Nv12Bars => "nv12-bars",
            Self::Nv12Gradient => "nv12-gradient",
            Self::SolidRed => "solid-red",
            Self::SolidGreen => "solid-green",
            Self::SolidBlue => "solid-blue",
        }
    }
}

#[derive(Serialize)]
struct ProducerEvent<'a> {
    #[serde(rename = "type")]
    event_type: &'static str,
    severity: &'a str,
    code: &'a str,
    message: &'a str,
    producer_state: Option<&'a str>,
    source_commit: &'static str,
}

fn emit_event(severity: &str, code: &str, message: &str, producer_state: Option<&str>) {
    let event = ProducerEvent {
        event_type: "event",
        severity,
        code,
        message,
        producer_state,
        source_commit: env!("OCB_SOURCE_COMMIT"),
    };
    if let Ok(json) = serde_json::to_string(&event) {
        println!("{json}");
    }
}

struct SharedMemoryIpc {
    h_file: HANDLE,
    h_map: HANDLE,
    p_map: *mut c_void,
    backend_name: String,
    producer_hash: [u8; 32],
    producer_hash_hex: String,
}

fn executable_sha256() -> ([u8; 32], String) {
    let bytes = std::env::current_exe()
        .ok()
        .and_then(|path| std::fs::read(path).ok());
    let hash: [u8; 32] = bytes
        .map(|data| Sha256::digest(data).into())
        .unwrap_or([0; 32]);
    let hex = hash.iter().map(|b| format!("{b:02x}")).collect();
    (hash, hex)
}

/// String SID of the user this process runs as (e.g. "S-1-5-21-...."), used to
/// scope the framebuffer ACL to the current user instead of broad groups.
fn current_user_sid() -> Option<String> {
    use windows::core::PWSTR;
    use windows::Win32::Foundation::{LocalFree, HLOCAL};
    use windows::Win32::Security::Authorization::ConvertSidToStringSidW;
    use windows::Win32::Security::{GetTokenInformation, TokenUser, TOKEN_QUERY, TOKEN_USER};
    use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

    unsafe {
        let mut token = HANDLE(0);
        OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token).ok()?;
        let mut len = 0u32;
        let _ = GetTokenInformation(token, TokenUser, None, 0, &mut len);
        if len == 0 {
            let _ = CloseHandle(token);
            return None;
        }
        let mut buf = vec![0u8; len as usize];
        let info = GetTokenInformation(
            token,
            TokenUser,
            Some(buf.as_mut_ptr() as *mut c_void),
            len,
            &mut len,
        );
        let _ = CloseHandle(token);
        info.ok()?;
        let user = &*(buf.as_ptr() as *const TOKEN_USER);
        let mut sid_str = PWSTR::null();
        ConvertSidToStringSidW(user.User.Sid, &mut sid_str).ok()?;
        let s = sid_str.to_string().ok();
        let _ = LocalFree(HLOCAL(sid_str.0 as *mut c_void));
        s
    }
}

impl SharedMemoryIpc {
    fn new() -> Result<Self, String> {
        let (producer_hash, producer_hash_hex) = executable_sha256();
        unsafe {
            let mut p_sd: PSECURITY_DESCRIPTOR = PSECURITY_DESCRIPTOR(null_mut());
            // Least-privilege ACL: SYSTEM and the current user get full access
            // (the producer and consumer apps run as the same user), and LOCAL
            // SERVICE keeps read/write — the Windows Camera Frame Server
            // service loads the vcam DLL under that account and MUST keep
            // access or the camera goes black. No more grants to broad groups
            // (BA/IU/AU), which let any authenticated process inject frames.
            let sddl_string = match current_user_sid() {
                Some(sid) => format!("D:P(A;;GA;;;SY)(A;;GRGW;;;LS)(A;;GA;;;{})", sid),
                None => {
                    return Err("Could not resolve the current user SID; refusing to create a broadly writable frame ring".into());
                }
            };
            emit_event("info", "FRAMEBUFFER_ACL", &sddl_string, Some("STARTING"));
            let sddl: Vec<u16> = sddl_string
                .encode_utf16()
                .chain(std::iter::once(0))
                .collect();

            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                PCWSTR(sddl.as_ptr()),
                SDDL_REVISION_1,
                &mut p_sd,
                None,
            )
            .is_err()
            {
                return Err("Failed to create preferred security descriptor".into());
            }

            let sa = SECURITY_ATTRIBUTES {
                nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
                lpSecurityDescriptor: p_sd.0,
                bInheritHandle: windows::Win32::Foundation::BOOL(0),
            };

            let _ = std::fs::create_dir_all("C:\\ProgramData\\OpenCamBridge");

            let path: Vec<u16> = "C:\\ProgramData\\OpenCamBridge\\framebuffer.bin\0"
                .encode_utf16()
                .collect();
            let h_file = CreateFileW(
                PCWSTR(path.as_ptr()),
                (windows::Win32::Storage::FileSystem::FILE_GENERIC_READ
                    | windows::Win32::Storage::FileSystem::FILE_GENERIC_WRITE
                    | WRITE_DAC)
                    .0,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                Some(&sa),
                OPEN_ALWAYS,
                FILE_ATTRIBUTE_NORMAL,
                None,
            );

            if let Ok(h_file) = h_file {
                if !h_file.is_invalid() {
                    if SetKernelObjectSecurity(
                        h_file,
                        DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                        p_sd,
                    )
                    .is_err()
                    {
                        let _ = CloseHandle(h_file);
                        return Err("Could not restrict the existing frame-ring file ACL".into());
                    }
                    let h_map = CreateFileMappingW(
                        h_file,
                        Some(&sa),
                        PAGE_READWRITE,
                        0,
                        MAX_SHM_SIZE,
                        PCWSTR(null_mut()),
                    );

                    if let Ok(h_map_val) = h_map {
                        if !h_map_val.is_invalid() {
                            let p_map = MapViewOfFile(h_map_val, FILE_MAP_ALL_ACCESS, 0, 0, 0);
                            if !p_map.Value.is_null() {
                                return Ok(Self {
                                    h_file,
                                    h_map: h_map_val,
                                    p_map: p_map.Value,
                                    backend_name: "C:\\ProgramData\\OpenCamBridge\\framebuffer.bin"
                                        .to_string(),
                                    producer_hash,
                                    producer_hash_hex,
                                });
                            }
                            let _ = CloseHandle(h_map_val);
                        }
                    }
                    let _ = CloseHandle(h_file);
                }
            }

            // The DLL always prefers the file-backed ring. If that file exists
            // but cannot be opened/resecured/mapped, using a different named
            // ring would leave the consumer reading stale data.
            if std::path::Path::new("C:\\ProgramData\\OpenCamBridge\\framebuffer.bin").exists() {
                return Err(
                    "The existing frame-ring file could not be opened and secured for this user"
                        .into(),
                );
            }

            let name_buffer: Vec<u16> = "Global\\OpenCamBridgeFrameBuffer\0"
                .encode_utf16()
                .collect();
            let h_map = CreateFileMappingW(
                INVALID_HANDLE_VALUE,
                Some(&sa),
                PAGE_READWRITE,
                0,
                MAX_SHM_SIZE,
                PCWSTR(name_buffer.as_ptr()),
            )
            .map_err(|e| format!("CreateFileMappingW failed: {}", e))?;
            if SetKernelObjectSecurity(
                h_map,
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                p_sd,
            )
            .is_err()
            {
                let _ = CloseHandle(h_map);
                return Err("Could not restrict the named frame-ring ACL".into());
            }

            let p_map = MapViewOfFile(h_map, FILE_MAP_ALL_ACCESS, 0, 0, 0);
            if p_map.Value.is_null() {
                let _ = CloseHandle(h_map);
                return Err("MapViewOfFile failed".into());
            }

            std::ptr::write_bytes(p_map.Value as *mut u8, 0, MAX_SHM_SIZE as usize);

            Ok(Self {
                h_file: HANDLE(0),
                h_map,
                p_map: p_map.Value,
                backend_name: "Global\\OpenCamBridgeFrameBuffer".to_string(),
                producer_hash,
                producer_hash_hex,
            })
        }
    }

    fn initialize_ring_if_needed(&self) {
        unsafe {
            let header = self.p_map as *mut OpenCamBridgeRingHeader;
            if (*header).magic == OCBR_MAGIC
                && (*header).version == RING_VERSION
                && (*header).header_size as usize == RING_HEADER_SIZE
                && (*header).slot_size as usize == SLOT_SIZE
                && (*header).ring_abi_hash == RING_ABI_HASH
            {
                (*header).max_width = 1920;
                (*header).max_height = 1920;
                (*header).ring_abi_hash = RING_ABI_HASH;
                (*header).producer_build_hash = self.producer_hash;
                return;
            }
            std::ptr::write_bytes(self.p_map as *mut u8, 0, MAX_SHM_SIZE as usize);
            (*header).magic = OCBR_MAGIC;
            (*header).version = RING_VERSION;
            (*header).header_size = RING_HEADER_SIZE as u16;
            (*header).slot_count = SLOT_COUNT as u32;
            (*header).slot_size = SLOT_SIZE as u32;
            (*header).max_width = 1920;
            (*header).max_height = 1920;
            (*header).ring_abi_hash = RING_ABI_HASH;
            (*header).producer_build_hash = self.producer_hash;
            (*header)
                .consumer_width
                .store(1920, std::sync::atomic::Ordering::Relaxed);
            (*header)
                .consumer_height
                .store(1080, std::sync::atomic::Ordering::Relaxed);
            (*header)
                .consumer_fps_num
                .store(60, std::sync::atomic::Ordering::Relaxed);
            (*header)
                .consumer_fps_den
                .store(1, std::sync::atomic::Ordering::Relaxed);
        }
    }

    fn set_source_fps(&self, numerator: u32, denominator: u32) {
        self.initialize_ring_if_needed();
        unsafe {
            let ring = &*(self.p_map as *const OpenCamBridgeRingHeader);
            ring.producer_fps_num
                .store(numerator, std::sync::atomic::Ordering::Release);
            ring.producer_fps_den
                .store(denominator.max(1), std::sync::atomic::Ordering::Release);
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn write_nv12_frame(
        &self,
        sequence: u64,
        capture_timestamp_ns: u64,
        receive_timestamp_ns: u64,
        decode_timestamp_ns: u64,
        width: u32,
        height: u32,
        y_stride: u32,
        uv_stride: u32,
        flags: u32,
        send_delta_us: u32,
        data: &[u8],
    ) -> bool {
        let Some(payload_size) =
            validate_nv12_metadata(width, height, y_stride, uv_stride, data.len())
        else {
            eprintln!(
                "NV12 ring write rejected: invalid {}x{} strides {}/{} size {}",
                width,
                height,
                y_stride,
                uv_stride,
                data.len()
            );
            return false;
        };
        self.initialize_ring_if_needed();

        unsafe {
            let ring = &*(self.p_map as *const OpenCamBridgeRingHeader);
            // Slot choice comes from the monotonic write sequence, not from
            // `published_slot`. Both pick the same slot, but only the counter tells a
            // consumer WHICH frames the ring currently holds, which is what reading in
            // order (rather than always taking the newest) requires. 1-based, so a
            // `ring_sequence` of 0 still means "never written".
            let write_sequence = ring
                .ring_write_sequence
                .load(std::sync::atomic::Ordering::Acquire)
                + 1;
            let generation = ring
                .stream_generation
                .load(std::sync::atomic::Ordering::Acquire);
            let slot_index = ((write_sequence - 1) % SLOT_COUNT as u64) as usize;
            let slot_base = (self.p_map as *mut u8).add(RING_HEADER_SIZE + slot_index * SLOT_SIZE);
            let slot = slot_base as *mut OpenCamBridgeSlotHeader;
            let epoch = write_sequence.wrapping_mul(2) | 1;
            std::ptr::write_volatile(&mut (*slot).committed_epoch, 0);
            std::ptr::write_volatile(&mut (*slot).write_epoch, epoch);
            (*slot).ring_sequence = write_sequence;
            (*slot).stream_generation = generation;
            (*slot).ring_write_timestamp_ns = monotonic_ns();
            (*slot).send_delta_us = send_delta_us;
            (*slot).reserved_tail = 0;
            (*slot).sequence = sequence;
            (*slot).capture_timestamp_ns = capture_timestamp_ns;
            (*slot).receive_timestamp_ns = receive_timestamp_ns;
            (*slot).decode_timestamp_ns = decode_timestamp_ns;
            (*slot).width = width;
            (*slot).height = height;
            (*slot).y_stride = y_stride;
            (*slot).uv_stride = uv_stride;
            (*slot).pixel_format = FORMAT_NV12;
            (*slot).payload_size = payload_size as u32;
            (*slot).flags = flags;
            (*slot).data_offset =
                (RING_HEADER_SIZE + slot_index * SLOT_SIZE + SLOT_HEADER_SIZE) as u32;
            copy_nonoverlapping(data.as_ptr(), slot_base.add(SLOT_HEADER_SIZE), payload_size);
            std::sync::atomic::fence(std::sync::atomic::Ordering::Release);
            std::ptr::write_volatile(&mut (*slot).committed_epoch, epoch);

            let mut qpc = 0i64;
            let _ = QueryPerformanceCounter(&mut qpc);
            ring.producer_heartbeat_qpc
                .store(qpc as u64, std::sync::atomic::Ordering::Release);
            ring.published_slot
                .store(slot_index as u32, std::sync::atomic::Ordering::Release);
            ring.published_sequence
                .store(sequence, std::sync::atomic::Ordering::Release);
            // Published last, and only after the slot is committed, so
            // `ring_write_sequence` is exactly "how many frames are fully written".
            // A consumer can then treat
            // `(ring_write_sequence - slot_count, ring_write_sequence]` as the live
            // window without ever racing a half-written slot.
            ring.ring_write_sequence
                .store(write_sequence, std::sync::atomic::Ordering::Release);
        }
        true
    }

    /// Invalidate every consumer cursor, because frame sequences are about to
    /// restart or the geometry is about to change.
    ///
    /// Without this a consumer cannot tell a restart from catastrophic loss: both
    /// look like the sequence jumping backwards or far forwards. A generation bump
    /// says "reset, this is a new stream" instead.
    fn bump_stream_generation(&self) {
        self.initialize_ring_if_needed();
        unsafe {
            let ring = &*(self.p_map as *const OpenCamBridgeRingHeader);
            ring.stream_generation
                .fetch_add(1, std::sync::atomic::Ordering::AcqRel);
        }
    }

    /// Compatibility path: MJPEG/test-pattern pixels are converted once to the
    /// same NV12 ring consumed by the virtual camera. H.264 never calls this.
    fn write_frame(&self, frame_counter: u64, bgra: &[u8], width: u32, height: u32) {
        let needed = width as usize * height as usize * 4;
        if bgra.len() < needed || width % 2 != 0 || height % 2 != 0 {
            return;
        }
        thread_local! { static NV12_SCRATCH: std::cell::RefCell<Vec<u8>> = const { std::cell::RefCell::new(Vec::new()) }; }
        NV12_SCRATCH.with(|scratch| {
            let mut nv12 = scratch.borrow_mut();
            let nv12_len = width as usize * height as usize * 3 / 2;
            nv12.resize(nv12_len, 0);
            bgra_to_nv12(bgra, width, height, &mut nv12);
            let now = monotonic_ns();
            let _ = self.write_nv12_frame(
                frame_counter,
                now,
                now,
                now,
                width,
                height,
                width,
                width,
                0,
                // No OCB2 sender cadence on this path.
                0,
                &nv12,
            );
        });
    }

    fn virtual_camera_counters(&self) -> (u64, u64) {
        self.initialize_ring_if_needed();
        unsafe {
            let ring = &*(self.p_map as *const OpenCamBridgeRingHeader);
            (
                ring.virtual_camera_unique_frames
                    .load(std::sync::atomic::Ordering::Acquire),
                ring.repeated_virtual_camera_samples
                    .load(std::sync::atomic::Ordering::Acquire),
            )
        }
    }

    fn diagnostics(&self) -> RingDiagnostics {
        self.initialize_ring_if_needed();
        unsafe {
            let ring = &*(self.p_map as *const OpenCamBridgeRingHeader);
            RingDiagnostics {
                consumer_attached: ring
                    .consumer_attached
                    .load(std::sync::atomic::Ordering::Acquire)
                    != 0,
                consumer_pid: ring.consumer_pid.load(std::sync::atomic::Ordering::Acquire),
                consumer_heartbeat_qpc: ring
                    .consumer_heartbeat_qpc
                    .load(std::sync::atomic::Ordering::Acquire),
                sample_requests: ring
                    .sample_requests
                    .load(std::sync::atomic::Ordering::Acquire),
                ring_read_attempts: ring
                    .ring_read_attempts
                    .load(std::sync::atomic::Ordering::Acquire),
                ring_read_successes: ring
                    .ring_read_successes
                    .load(std::sync::atomic::Ordering::Acquire),
                ring_validation_failures: ring
                    .ring_validation_failures
                    .load(std::sync::atomic::Ordering::Acquire),
                sample_copy_failures: ring
                    .sample_copy_failures
                    .load(std::sync::atomic::Ordering::Acquire),
                last_ring_error: ring
                    .last_ring_error
                    .load(std::sync::atomic::Ordering::Acquire),
                last_accepted_sequence: ring
                    .last_accepted_sequence
                    .load(std::sync::atomic::Ordering::Acquire),
                negotiated_subtype: ring
                    .negotiated_subtype
                    .load(std::sync::atomic::Ordering::Acquire),
                negotiated_width: ring
                    .consumer_width
                    .load(std::sync::atomic::Ordering::Acquire),
                negotiated_height: ring
                    .consumer_height
                    .load(std::sync::atomic::Ordering::Acquire),
                negotiated_fps_num: ring
                    .consumer_fps_num
                    .load(std::sync::atomic::Ordering::Acquire),
                negotiated_fps_den: ring
                    .consumer_fps_den
                    .load(std::sync::atomic::Ordering::Acquire),
                source_fps_num: ring
                    .producer_fps_num
                    .load(std::sync::atomic::Ordering::Acquire),
                source_fps_den: ring
                    .producer_fps_den
                    .load(std::sync::atomic::Ordering::Acquire),
                resize_backend: match ring
                    .resize_backend
                    .load(std::sync::atomic::Ordering::Acquire)
                {
                    0 => "native-match",
                    1 => "gpu",
                    2 => "cpu-fallback",
                    _ => "unknown",
                }
                .to_string(),
                resize_failures: ring
                    .resize_failures
                    .load(std::sync::atomic::Ordering::Acquire),
                ring_write_sequence: ring
                    .ring_write_sequence
                    .load(std::sync::atomic::Ordering::Acquire),
                stream_generation: ring
                    .stream_generation
                    .load(std::sync::atomic::Ordering::Acquire),
                ring_frames_overwritten: ring
                    .ring_frames_overwritten
                    .load(std::sync::atomic::Ordering::Acquire),
                playout_buffer_depth_ms: ring
                    .playout_buffer_depth_ns
                    .load(std::sync::atomic::Ordering::Acquire)
                    / 1_000_000,
                playout_target_delay_ms: ring
                    .playout_target_delay_ns
                    .load(std::sync::atomic::Ordering::Acquire)
                    / 1_000_000,
                playout_late_dropped: ring
                    .playout_late_dropped
                    .load(std::sync::atomic::Ordering::Acquire),
                playout_underruns: ring
                    .playout_underruns
                    .load(std::sync::atomic::Ordering::Acquire),
                playout_scheduler_resets: ring
                    .playout_scheduler_resets
                    .load(std::sync::atomic::Ordering::Acquire),
                playout_clock_ppm: ring
                    .playout_clock_ppm
                    .load(std::sync::atomic::Ordering::Acquire),
                playout_max_output_gap_ms: ring
                    .playout_max_output_gap_ms
                    .load(std::sync::atomic::Ordering::Acquire),
                installed_dll_build_hash: hex_hash(&ring.installed_dll_build_hash),
                producer_build_hash: self.producer_hash_hex.clone(),
                ring_abi_hash: ring.ring_abi_hash,
            }
        }
    }

    fn consumer_format(&self) -> (u32, u32, u32) {
        self.initialize_ring_if_needed();
        unsafe {
            let ring = &*(self.p_map as *const OpenCamBridgeRingHeader);
            let width = ring
                .consumer_width
                .load(std::sync::atomic::Ordering::Acquire);
            let height = ring
                .consumer_height
                .load(std::sync::atomic::Ordering::Acquire);
            let numerator = ring
                .consumer_fps_num
                .load(std::sync::atomic::Ordering::Acquire);
            let denominator = ring
                .consumer_fps_den
                .load(std::sync::atomic::Ordering::Acquire)
                .max(1);
            (width, height, numerator / denominator)
        }
    }
}

#[derive(Clone, Debug, Serialize)]
struct RingDiagnostics {
    consumer_attached: bool,
    consumer_pid: u32,
    consumer_heartbeat_qpc: u64,
    sample_requests: u64,
    ring_read_attempts: u64,
    ring_read_successes: u64,
    ring_validation_failures: u64,
    sample_copy_failures: u64,
    last_ring_error: i32,
    last_accepted_sequence: u64,
    negotiated_subtype: u32,
    negotiated_width: u32,
    negotiated_height: u32,
    negotiated_fps_num: u32,
    negotiated_fps_den: u32,
    source_fps_num: u32,
    source_fps_den: u32,
    resize_backend: String,
    resize_failures: u32,
    /// Monotonic ring writes so far; the newest frame is this sequence.
    ring_write_sequence: u64,
    /// Incremented on every stream restart or geometry change.
    stream_generation: u64,
    /// Frames a consumer found already overwritten when it came to read them.
    /// Separates "the ring is too short" from "the consumer read at the wrong
    /// moment", which the skipped-frame counters alone cannot.
    ring_frames_overwritten: u64,
    /// Playout scheduler telemetry, written by the virtual camera. The unique and
    /// repeated counters say THAT a correction happened; these say why.
    playout_buffer_depth_ms: u64,
    playout_target_delay_ms: u64,
    playout_late_dropped: u64,
    playout_underruns: u64,
    playout_scheduler_resets: u64,
    playout_clock_ppm: i32,
    playout_max_output_gap_ms: u32,
    installed_dll_build_hash: String,
    producer_build_hash: String,
    ring_abi_hash: u64,
}

#[derive(Clone, Debug, Serialize)]
struct ConsumerReadinessStatus {
    ready: bool,
    consumer_attached: bool,
    consumer_pid: u32,
    heartbeat_recent: bool,
    sample_requests_recent: bool,
    ring_reads_recent: bool,
    accepted_sequence_recent: bool,
}

struct ConsumerReadiness {
    consumer_pid: u32,
    heartbeat: u64,
    sample_requests: u64,
    ring_reads: u64,
    accepted_sequence: u64,
    heartbeat_at: Option<Instant>,
    sample_requests_at: Option<Instant>,
    ring_reads_at: Option<Instant>,
    accepted_sequence_at: Option<Instant>,
}

impl ConsumerReadiness {
    fn new() -> Self {
        Self {
            consumer_pid: 0,
            heartbeat: 0,
            sample_requests: 0,
            ring_reads: 0,
            accepted_sequence: 0,
            heartbeat_at: None,
            sample_requests_at: None,
            ring_reads_at: None,
            accepted_sequence_at: None,
        }
    }

    fn reset(&mut self) {
        *self = Self::new();
    }

    fn observe(&mut self, ring: &RingDiagnostics, now: Instant) -> ConsumerReadinessStatus {
        if !ring.consumer_attached
            || ring.consumer_pid == 0
            || ring.consumer_pid != self.consumer_pid
        {
            self.reset();
            self.consumer_pid = ring.consumer_pid;
            self.heartbeat = ring.consumer_heartbeat_qpc;
            self.sample_requests = ring.sample_requests;
            self.ring_reads = ring.ring_read_successes;
            self.accepted_sequence = ring.last_accepted_sequence;
        } else {
            if ring.consumer_heartbeat_qpc != self.heartbeat {
                self.heartbeat = ring.consumer_heartbeat_qpc;
                self.heartbeat_at = Some(now);
            }
            if ring.sample_requests != self.sample_requests {
                self.sample_requests = ring.sample_requests;
                self.sample_requests_at = Some(now);
            }
            if ring.ring_read_successes != self.ring_reads {
                self.ring_reads = ring.ring_read_successes;
                self.ring_reads_at = Some(now);
            }
            if ring.last_accepted_sequence != self.accepted_sequence {
                self.accepted_sequence = ring.last_accepted_sequence;
                self.accepted_sequence_at = Some(now);
            }
        }
        let recent = |value: Option<Instant>| {
            value
                .map(|at| now.duration_since(at) < Duration::from_secs(2))
                .unwrap_or(false)
        };
        let heartbeat_recent = recent(self.heartbeat_at);
        let sample_requests_recent = recent(self.sample_requests_at);
        let ring_reads_recent = recent(self.ring_reads_at);
        let accepted_sequence_recent = recent(self.accepted_sequence_at);
        ConsumerReadinessStatus {
            ready: ring.consumer_attached
                && heartbeat_recent
                && sample_requests_recent
                && ring_reads_recent
                && accepted_sequence_recent,
            consumer_attached: ring.consumer_attached,
            consumer_pid: ring.consumer_pid,
            heartbeat_recent,
            sample_requests_recent,
            ring_reads_recent,
            accepted_sequence_recent,
        }
    }
}

fn hex_hash(hash: &[u8; 32]) -> String {
    hash.iter().map(|b| format!("{b:02x}")).collect()
}

fn validate_nv12_metadata(
    width: u32,
    height: u32,
    y_stride: u32,
    uv_stride: u32,
    available: usize,
) -> Option<usize> {
    if width == 0
        || height == 0
        || width > 1920
        || height > 1920
        || width % 2 != 0
        || height % 2 != 0
    {
        return None;
    }
    if y_stride < width || uv_stride < width || y_stride > 8192 || uv_stride > 8192 {
        return None;
    }
    let y = (y_stride as usize).checked_mul(height as usize)?;
    let uv = (uv_stride as usize).checked_mul((height / 2) as usize)?;
    let total = y.checked_add(uv)?;
    if total > MAX_NV12_SIZE || total > available {
        None
    } else {
        Some(total)
    }
}

/// H.264 decoder continuity only depends on the encoded picture geometry and
/// cadence. Rotation and mirroring are post-decode NV12 transforms, so a phone
/// orientation update must not flush a healthy decoder or pause until the next
/// IDR frame.
fn h264_decode_format_changed(
    previous: Option<&ocb2::StreamInfo>,
    next: &ocb2::StreamInfo,
) -> bool {
    previous
        .map(|old| {
            old.width != next.width
                || old.height != next.height
                || old.fps_numerator != next.fps_numerator
                || old.fps_denominator != next.fps_denominator
        })
        .unwrap_or(true)
}

fn monotonic_ns() -> u64 {
    static START: std::sync::OnceLock<Instant> = std::sync::OnceLock::new();
    START
        .get_or_init(Instant::now)
        .elapsed()
        .as_nanos()
        .min(u64::MAX as u128) as u64
}

fn clamp_u8(value: i32) -> u8 {
    value.clamp(0, 255) as u8
}

fn bgra_to_nv12(src: &[u8], width: u32, height: u32, dst: &mut [u8]) {
    let w = width as usize;
    let h = height as usize;
    for y in 0..h {
        for x in 0..w {
            let p = (y * w + x) * 4;
            let b = src[p] as i32;
            let g = src[p + 1] as i32;
            let r = src[p + 2] as i32;
            dst[y * w + x] = clamp_u8(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        }
    }
    let uv_base = w * h;
    for y in (0..h).step_by(2) {
        for x in (0..w).step_by(2) {
            let mut r = 0i32;
            let mut g = 0i32;
            let mut b = 0i32;
            for dy in 0..2 {
                for dx in 0..2 {
                    let p = ((y + dy) * w + x + dx) * 4;
                    b += src[p] as i32;
                    g += src[p + 1] as i32;
                    r += src[p + 2] as i32;
                }
            }
            r /= 4;
            g /= 4;
            b /= 4;
            let uv = uv_base + (y / 2) * w + x;
            dst[uv] = clamp_u8(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
            dst[uv + 1] = clamp_u8(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }
    }
}

#[cfg(test)]
mod ring_tests {
    use super::*;
    use std::io::Write;
    use std::net::{TcpListener, TcpStream};

    fn test_stream_info(rotation: u32) -> ocb2::StreamInfo {
        ocb2::StreamInfo {
            codec: "H264".into(),
            framing: "annex-b-access-units".into(),
            width: 1920,
            height: 1080,
            fps_numerator: 60,
            fps_denominator: 1,
            bitrate: 16_000_000,
            camera_id: "0".into(),
            encoder_name: "test".into(),
            hardware_encoder: true,
            pixel_format: "NV12".into(),
            effective_rotation: rotation,
            mirror: false,
            sensor_orientation: 90,
            device_rotation: rotation,
        }
    }

    #[test]
    fn h264_transform_metadata_preserves_decoder_continuity() {
        let old = test_stream_info(0);
        let rotated = test_stream_info(90);
        assert!(!h264_decode_format_changed(Some(&old), &rotated));
    }

    #[test]
    fn h264_geometry_change_requires_decoder_restart() {
        let old = test_stream_info(0);
        let mut resized = test_stream_info(0);
        resized.width = 1280;
        resized.height = 720;
        assert!(h264_decode_format_changed(Some(&old), &resized));
        assert!(h264_decode_format_changed(None, &resized));
    }

    #[test]
    fn h264_1080p60_fallback_selects_canonical_mjpeg_1080p30() {
        let selected = select_canonical_mjpeg_fallback(
            MjpegMode {
                width: 1920,
                height: 1080,
                fps: 60,
            },
            &[
                MjpegMode {
                    width: 1280,
                    height: 720,
                    fps: 30,
                },
                MjpegMode {
                    width: 1920,
                    height: 1080,
                    fps: 30,
                },
            ],
            &[],
        );
        assert_eq!(
            selected,
            Some(MjpegMode {
                width: 1920,
                height: 1080,
                fps: 30
            })
        );
    }

    #[test]
    fn unprocessable_alternatives_are_parsed_as_complete_tuples() {
        assert_eq!(
            parse_mjpeg_alternative("MJPEG 1920x1080@30"),
            Some(MjpegMode {
                width: 1920,
                height: 1080,
                fps: 30
            })
        );
        assert_eq!(parse_mjpeg_alternative("not a mode"), None);
    }

    #[test]
    fn fallback_http_retries_conflict_uses_422_alternatives_and_returns_accepted_tuple() {
        let status = |revision| {
            format!(
                r#"{{"revision":{revision},"snapshot":{{"desired":{{"cameraId":"0","streamMode":"h264","width":1920,"height":1080,"fps":60}}}}}}"#
            )
        };
        let capabilities = r#"{"cameras":[{"id":"0","mjpegModes":[{"width":1920,"height":1080,"fps":30},{"width":1280,"height":720,"fps":30}]}]}"#;
        let exchanges = vec![
            MockExchange::get("/api/camera/status", status(20)),
            MockExchange::get("/api/pipeline/capabilities", capabilities),
            MockExchange::post(
                409,
                r#"{"message":"revision conflict","authoritativeState":{"revision":21}}"#,
            ),
            MockExchange::get("/api/camera/status", status(21)),
            MockExchange::get("/api/pipeline/capabilities", capabilities),
            MockExchange::post(
                422,
                r#"{"message":"unsupported","alternatives":["MJPEG 1280x720@30"],"authoritativeState":{"revision":22}}"#,
            ),
            MockExchange::get("/api/camera/status", status(22)),
            MockExchange::get("/api/pipeline/capabilities", capabilities),
            MockExchange::post(
                200,
                r#"{"success":true,"authoritativeState":{"snapshot":{"selected":{"streamMode":"mjpeg","width":1280,"height":720,"fps":30}}}}"#,
            ),
        ];
        let (base_url, server, captured) = spawn_mock_phone(exchanges);
        let args = Args {
            source: "h264".into(),
            test_pattern: None,
            url: Some(format!("{base_url}/stream.ocb2")),
            width: Some(1920),
            height: Some(1080),
            fps: Some(60),
            source_width: Some(1920),
            source_height: Some(1080),
            source_fps: Some(60),
            profile: "adaptive".into(),
            token: Some("secret-token".into()),
        };

        assert_eq!(
            request_phone_mjpeg_fallback(&args).unwrap(),
            MjpegMode {
                width: 1280,
                height: 720,
                fps: 30
            }
        );
        server.join().unwrap();
        let requests = captured.lock().unwrap();
        let posts: Vec<&CapturedRequest> = requests
            .iter()
            .filter(|request| request.method == "POST")
            .collect();
        assert_eq!(posts.len(), 3);
        let first: serde_json::Value = serde_json::from_str(&posts[0].body).unwrap();
        let retry: serde_json::Value = serde_json::from_str(&posts[1].body).unwrap();
        let alternative: serde_json::Value = serde_json::from_str(&posts[2].body).unwrap();
        assert_eq!(
            (
                first["width"].as_u64(),
                first["height"].as_u64(),
                first["fps"].as_u64()
            ),
            (Some(1920), Some(1080), Some(30))
        );
        assert_eq!(first["baseRevision"], 20);
        assert_eq!(retry["baseRevision"], 21);
        assert_eq!(
            (
                alternative["width"].as_u64(),
                alternative["height"].as_u64(),
                alternative["fps"].as_u64()
            ),
            (Some(1280), Some(720), Some(30))
        );
        assert_eq!(alternative["baseRevision"], 22);
        assert!(requests.iter().all(|request| request
            .headers
            .to_ascii_lowercase()
            .contains("x-opencambridge-token: secret-token")));
    }

    struct MockExchange {
        method: &'static str,
        path: &'static str,
        status: u16,
        body: String,
    }

    impl MockExchange {
        fn get(path: &'static str, body: impl Into<String>) -> Self {
            Self {
                method: "GET",
                path,
                status: 200,
                body: body.into(),
            }
        }
        fn post(status: u16, body: impl Into<String>) -> Self {
            Self {
                method: "POST",
                path: "/api/settings",
                status,
                body: body.into(),
            }
        }
    }

    struct CapturedRequest {
        method: String,
        path: String,
        headers: String,
        body: String,
    }

    fn spawn_mock_phone(
        exchanges: Vec<MockExchange>,
    ) -> (
        String,
        std::thread::JoinHandle<()>,
        Arc<Mutex<Vec<CapturedRequest>>>,
    ) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let captured = Arc::new(Mutex::new(Vec::new()));
        let thread_capture = captured.clone();
        let server = std::thread::spawn(move || {
            for expected in exchanges {
                let (mut stream, _) = listener.accept().unwrap();
                let request = read_mock_request(&mut stream);
                assert_eq!(request.method, expected.method);
                assert_eq!(request.path, expected.path);
                thread_capture.lock().unwrap().push(request);
                let reason = match expected.status {
                    200 => "OK",
                    409 => "Conflict",
                    422 => "Unprocessable Entity",
                    _ => "Error",
                };
                write!(stream, "HTTP/1.1 {} {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", expected.status, reason, expected.body.len(), expected.body).unwrap();
                stream.flush().unwrap();
            }
        });
        (format!("http://{address}"), server, captured)
    }

    fn read_mock_request(stream: &mut TcpStream) -> CapturedRequest {
        stream
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let mut bytes = Vec::new();
        let mut chunk = [0u8; 1024];
        let header_end = loop {
            let count = stream.read(&mut chunk).unwrap();
            assert!(count > 0);
            bytes.extend_from_slice(&chunk[..count]);
            if let Some(index) = bytes.windows(4).position(|window| window == b"\r\n\r\n") {
                break index + 4;
            }
        };
        let headers = String::from_utf8(bytes[..header_end].to_vec()).unwrap();
        let content_length = headers
            .lines()
            .find_map(|line| {
                line.to_ascii_lowercase()
                    .strip_prefix("content-length:")?
                    .trim()
                    .parse::<usize>()
                    .ok()
            })
            .unwrap_or(0);
        while bytes.len() < header_end + content_length {
            let count = stream.read(&mut chunk).unwrap();
            assert!(count > 0);
            bytes.extend_from_slice(&chunk[..count]);
        }
        let first = headers.lines().next().unwrap();
        let mut parts = first.split_whitespace();
        CapturedRequest {
            method: parts.next().unwrap().to_owned(),
            path: parts.next().unwrap().to_owned(),
            headers,
            body: String::from_utf8(bytes[header_end..header_end + content_length].to_vec())
                .unwrap(),
        }
    }

    fn readiness_ring(pid: u32, value: u64) -> RingDiagnostics {
        RingDiagnostics {
            consumer_attached: true,
            consumer_pid: pid,
            consumer_heartbeat_qpc: value,
            sample_requests: value,
            ring_read_attempts: value,
            ring_read_successes: value,
            ring_validation_failures: 0,
            sample_copy_failures: 0,
            last_ring_error: 0,
            last_accepted_sequence: value,
            negotiated_subtype: FORMAT_NV12,
            negotiated_width: 1280,
            negotiated_height: 720,
            negotiated_fps_num: 60,
            negotiated_fps_den: 1,
            source_fps_num: 60,
            source_fps_den: 1,
            resize_backend: "native-match".into(),
            resize_failures: 0,
            ring_write_sequence: value,
            stream_generation: 1,
            ring_frames_overwritten: 0,
            playout_buffer_depth_ms: 80,
            playout_target_delay_ms: 80,
            playout_late_dropped: 0,
            playout_underruns: 0,
            playout_scheduler_resets: 1,
            playout_clock_ppm: 0,
            playout_max_output_gap_ms: 33,
            installed_dll_build_hash: "a".repeat(64),
            producer_build_hash: "b".repeat(64),
            ring_abi_hash: RING_ABI_HASH,
        }
    }

    #[test]
    fn virtual_camera_readiness_requires_recent_counter_progress() {
        let now = Instant::now();
        let mut readiness = ConsumerReadiness::new();
        assert!(!readiness.observe(&readiness_ring(10, 1), now).ready);
        assert!(
            readiness
                .observe(&readiness_ring(10, 2), now + Duration::from_millis(50))
                .ready
        );
        assert!(
            !readiness
                .observe(&readiness_ring(10, 2), now + Duration::from_secs(3))
                .ready
        );
    }

    #[test]
    fn virtual_camera_readiness_resets_when_consumer_pid_changes() {
        let now = Instant::now();
        let mut readiness = ConsumerReadiness::new();
        readiness.observe(&readiness_ring(10, 1), now);
        assert!(
            readiness
                .observe(&readiness_ring(10, 2), now + Duration::from_millis(10))
                .ready
        );
        assert!(
            !readiness
                .observe(&readiness_ring(11, 3), now + Duration::from_millis(20))
                .ready
        );
    }

    #[test]
    fn ring_struct_sizes_are_stable_for_cpp_consumer() {
        assert_eq!(
            std::mem::size_of::<OpenCamBridgeRingHeader>(),
            RING_HEADER_SIZE
        );
        assert_eq!(
            std::mem::size_of::<OpenCamBridgeSlotHeader>(),
            SLOT_HEADER_SIZE
        );
    }

    #[test]
    fn ring_buffer_bounds_and_invalid_metadata() {
        assert_eq!(
            validate_nv12_metadata(1920, 1080, 1920, 1920, MAX_NV12_SIZE),
            Some(MAX_NV12_SIZE)
        );
        assert_eq!(
            validate_nv12_metadata(1921, 1080, 1921, 1921, usize::MAX),
            None
        );
        assert_eq!(
            validate_nv12_metadata(1280, 720, 1279, 1280, usize::MAX),
            None
        );
        assert_eq!(
            validate_nv12_metadata(1280, 721, 1280, 1280, usize::MAX),
            None
        );
        assert_eq!(validate_nv12_metadata(1280, 720, 1280, 1280, 16), None);
        assert_eq!(
            validate_nv12_metadata(u32::MAX, u32::MAX, u32::MAX, u32::MAX, usize::MAX),
            None
        );
        assert_eq!(
            validate_nv12_metadata(1080, 1920, 1080, 1080, MAX_NV12_SIZE),
            Some(MAX_NV12_SIZE)
        );
    }

    #[derive(Default)]
    struct UniqueRepeatCounter {
        last: Option<u64>,
        unique: u64,
        repeated: u64,
    }
    impl UniqueRepeatCounter {
        fn observe(&mut self, sequence: u64) {
            if self.last == Some(sequence) {
                self.repeated += 1;
            } else {
                self.unique += 1;
                self.last = Some(sequence);
            }
        }
    }

    #[test]
    fn unique_frames_are_not_inflated_by_repeated_samples() {
        let mut counter = UniqueRepeatCounter::default();
        for sequence in [1, 1, 1, 2, 2, 3] {
            counter.observe(sequence);
        }
        assert_eq!((counter.unique, counter.repeated), (3, 3));
    }

    #[test]
    fn media_foundation_pacing_is_exact_at_30_and_60_fps() {
        fn duration_100ns(fps: u32) -> i64 {
            10_000_000 / fps as i64
        }
        assert_eq!(duration_100ns(30), 333_333);
        assert_eq!(duration_100ns(60), 166_666);
        assert!(duration_100ns(30) > duration_100ns(60));
        let sixty_samples = (0..60).map(|n| n * duration_100ns(60)).collect::<Vec<_>>();
        assert!(sixty_samples.windows(2).all(|w| w[1] > w[0]));
    }

    #[test]
    fn deterministic_nv12_patterns_have_valid_planes_and_colours() {
        let mut frame = Vec::new();
        for (kind, expected) in [
            (TestPatternKind::SolidRed, (82, 90, 240)),
            (TestPatternKind::SolidGreen, (145, 54, 34)),
            (TestPatternKind::SolidBlue, (41, 240, 110)),
        ] {
            generate_nv12_pattern(kind, 0, 1280, 720, &mut frame);
            assert_eq!(frame.len(), 1280 * 720 * 3 / 2);
            assert_eq!(frame[0], expected.0);
            assert_eq!(frame[1280 * 720], expected.1);
            assert_eq!(frame[1280 * 720 + 1], expected.2);
            assert_eq!(
                validate_nv12_metadata(1280, 720, 1280, 1280, frame.len()),
                Some(frame.len())
            );
        }
    }

    #[test]
    fn ring_v4_diagnostic_layout_is_stable() {
        assert_eq!(RING_VERSION, 4);
        assert_eq!(std::mem::size_of::<OpenCamBridgeRingHeader>(), 320);
        assert_eq!(
            std::mem::offset_of!(OpenCamBridgeRingHeader, consumer_attached),
            80
        );
        assert_eq!(
            std::mem::offset_of!(OpenCamBridgeRingHeader, last_accepted_sequence),
            144
        );
        assert_eq!(
            std::mem::offset_of!(OpenCamBridgeRingHeader, producer_build_hash),
            192
        );
    }

    #[test]
    fn nv12_orientation_rotates_both_planes_without_rgb_conversion() {
        let input = vec![
            0, 1, 2, 3, // Y row 0
            4, 5, 6, 7, // Y row 1
            10, 11, 20, 21, // UV row: two interleaved chroma samples
        ];
        let mut output = Vec::new();
        let dimensions = orient_nv12(&input, 4, 2, 4, 4, 90, false, &mut output).unwrap();
        assert_eq!(dimensions, (2, 4));
        assert_eq!(output, vec![4, 0, 5, 1, 6, 2, 7, 3, 10, 11, 20, 21]);
    }

    #[test]
    fn nv12_orientation_mirrors_luma_and_chroma_samples() {
        let input = vec![
            0, 1, 2, 3, // Y row 0
            4, 5, 6, 7, // Y row 1
            10, 11, 20, 21, // UV row: two interleaved chroma samples
        ];
        let mut output = Vec::new();
        let dimensions = orient_nv12(&input, 4, 2, 4, 4, 0, true, &mut output).unwrap();
        assert_eq!(dimensions, (4, 2));
        assert_eq!(output, vec![3, 2, 1, 0, 7, 6, 5, 4, 20, 21, 10, 11]);
    }

    /// The original per-pixel mapping, kept verbatim as the oracle for the
    /// specialised row/tile paths in `orient_nv12`. If a future optimisation
    /// changes any output sample, this fails rather than shipping a subtly
    /// rotated or mirrored image.
    fn orientation_reference(
        input: &[u8],
        width: u32,
        height: u32,
        y_stride: u32,
        uv_stride: u32,
        rotation: u32,
        mirror: bool,
    ) -> Vec<u8> {
        let (out_width, out_height) = if rotation == 90 || rotation == 270 {
            (height, width)
        } else {
            (width, height)
        };
        let mut output = vec![0u8; out_width as usize * out_height as usize * 3 / 2];
        let map = |x: u32, y: u32, source_width: u32, source_height: u32, output_width: u32| {
            let oriented_x = if mirror { output_width - 1 - x } else { x };
            match rotation {
                0 => (oriented_x, y),
                90 => (y, source_height - 1 - oriented_x),
                180 => (source_width - 1 - oriented_x, source_height - 1 - y),
                270 => (source_width - 1 - y, oriented_x),
                _ => unreachable!(),
            }
        };
        for y in 0..out_height {
            for x in 0..out_width {
                let (source_x, source_y) = map(x, y, width, height, out_width);
                output[(y * out_width + x) as usize] =
                    input[(source_y * y_stride + source_x) as usize];
            }
        }
        let source_uv = y_stride as usize * height as usize;
        let output_uv = out_width as usize * out_height as usize;
        for y in 0..out_height / 2 {
            for x in 0..out_width / 2 {
                let (source_x, source_y) = map(x, y, width / 2, height / 2, out_width / 2);
                let source_offset =
                    source_uv + (source_y * (uv_stride / 2) + source_x) as usize * 2;
                let output_offset = output_uv + (y * (out_width / 2) + x) as usize * 2;
                output[output_offset] = input[source_offset];
                output[output_offset + 1] = input[source_offset + 1];
            }
        }
        output
    }

    fn orientation_fixture(width: u32, height: u32, y_stride: u32, uv_stride: u32) -> Vec<u8> {
        // Distinct, position-dependent values so a transposed or flipped sample
        // cannot coincidentally match. Padding beyond the visible width is
        // poisoned so reading it would show up as a mismatch.
        let mut input = vec![0xEEu8; y_stride as usize * height as usize * 3 / 2];
        for y in 0..height as usize {
            for x in 0..width as usize {
                input[y * y_stride as usize + x] = ((y * 37 + x * 11) % 251) as u8;
            }
        }
        let uv = y_stride as usize * height as usize;
        for y in 0..height as usize / 2 {
            for x in 0..width as usize / 2 {
                input[uv + y * uv_stride as usize + x * 2] = ((y * 53 + x * 7) % 251) as u8;
                input[uv + y * uv_stride as usize + x * 2 + 1] = ((y * 29 + x * 17) % 251) as u8;
            }
        }
        input
    }

    #[test]
    fn optimised_orientation_matches_the_per_pixel_reference() {
        // Sizes chosen to cover a partial trailing tile in both axes (the tiled
        // transpose steps 32 at a time), plus a non-square and a padded-stride
        // case so stride handling is exercised independently of width.
        for &(width, height, y_stride, uv_stride) in
            &[(8u32, 4u32, 8u32, 8u32), (64, 36, 64, 64), (40, 20, 48, 48)]
        {
            let input = orientation_fixture(width, height, y_stride, uv_stride);
            for &rotation in &[0u32, 90, 180, 270] {
                for &mirror in &[false, true] {
                    let mut actual = Vec::new();
                    let (out_width, out_height) = orient_nv12(
                        &input,
                        width,
                        height,
                        y_stride,
                        uv_stride,
                        rotation,
                        mirror,
                        &mut actual,
                    )
                    .unwrap();
                    let expected = orientation_reference(
                        &input, width, height, y_stride, uv_stride, rotation, mirror,
                    );
                    assert_eq!(
                        (out_width, out_height),
                        if rotation == 90 || rotation == 270 {
                            (height, width)
                        } else {
                            (width, height)
                        },
                        "{width}x{height} rot={rotation} mirror={mirror} dimensions"
                    );
                    assert_eq!(
                        actual, expected,
                        "{width}x{height} stride={y_stride} rot={rotation} mirror={mirror}"
                    );
                }
            }
        }
    }

    #[test]
    fn a_clean_restart_reconnects_without_the_failure_backoff() {
        // The regression this pins: the phone rebinds its camera routinely (the
        // adaptive watchdog downgrades a profile that cannot hold its rate) and
        // announces end of stream. Pacing that like a failure put a full second of
        // nothing into the NV12 ring, which both the desktop preview and the virtual
        // camera render as a freeze followed by a burst of catch-up frames.
        assert!(
            reconnect_delay(true, 0) <= Duration::from_millis(100),
            "a clean restart must reconnect promptly"
        );
        // A clean restart must stay prompt even if failures were counted earlier,
        // because the counter is reset rather than consulted.
        assert!(reconnect_delay(true, 7) <= Duration::from_millis(100));
    }

    #[test]
    fn a_failed_connection_still_backs_off() {
        // The other half: a phone that is genuinely gone must not be hammered.
        assert!(reconnect_delay(false, 1) >= Duration::from_secs(1));
        assert!(reconnect_delay(false, 9) >= Duration::from_secs(1));
        // ...and the wait stays bounded so recovery is not left to a long sleep.
        assert!(reconnect_delay(false, 9) <= Duration::from_secs(2));
    }

    #[test]
    fn the_ring_cursor_maps_write_sequences_onto_slots_the_producer_actually_used() {
        // The one invariant every consumer depends on: the slot the producer chose for
        // write N and the slot a consumer derives for write N must be the same, or the
        // consumer reads a different frame than the one it is accounting for.
        for write_sequence in 1u64..=(SLOT_COUNT as u64 * 3) {
            let producer_slot = ((write_sequence - 1) % SLOT_COUNT as u64) as usize;
            let selection =
                ring_select_newest(write_sequence, 0, write_sequence, 0, SLOT_COUNT as u64)
                    .expect("a committed write is always readable");
            assert_eq!(
                producer_slot, selection.slot_index,
                "write {write_sequence} slot"
            );
            assert_eq!(0, selection.skipped);
            assert_eq!(0, selection.overwritten);
        }
    }

    #[test]
    fn a_caught_up_cursor_still_gets_the_newest_frame_marked_as_a_repeat() {
        // Media Foundation must be handed a sample for every request, so a caught-up
        // consumer has to receive the previous frame again rather than an error. Only
        // `fresh` distinguishes the two, and only fresh frames may count as losses.
        let repeat = ring_select_newest(11, 0, 10, 0, SLOT_COUNT as u64).unwrap();
        assert!(!repeat.fresh);
        assert_eq!(10, repeat.ring_sequence);
        assert_eq!(0, repeat.skipped);
        assert_eq!(0, repeat.overwritten);
        // An empty ring genuinely has nothing to hand over.
        assert_eq!(None, ring_select_newest(1, 0, 0, 0, SLOT_COUNT as u64));
    }

    #[test]
    fn the_ring_cursor_separates_frames_skipped_from_frames_destroyed() {
        let slots = SLOT_COUNT as u64;
        // One frame behind: one skipped, still recoverable, so nothing was destroyed.
        let one_behind = ring_select_newest(10, 0, 11, 0, slots).unwrap();
        assert_eq!(1, one_behind.skipped);
        assert_eq!(0, one_behind.overwritten);

        // Far behind: every frame outside the window is gone for good. This is the
        // number that says "the ring is too short", as opposed to skipped, which says
        // "this consumer read at the wrong moment".
        let far_behind = ring_select_newest(1, 0, 100, 0, slots).unwrap();
        assert_eq!(99, far_behind.skipped);
        let oldest_safe = 100 - (slots - 2);
        assert_eq!(oldest_safe - 1, far_behind.overwritten);
        assert!(
            far_behind.overwritten < far_behind.skipped,
            "frames still inside the window must not be counted as destroyed"
        );

        // Exactly at the oldest safe frame: nothing destroyed yet.
        assert_eq!(
            0,
            ring_select_newest(oldest_safe, 0, 100, 0, slots)
                .unwrap()
                .overwritten
        );
        // One older than that: exactly one destroyed. This is the boundary that
        // decides whether the window is off by one.
        assert_eq!(
            1,
            ring_select_newest(oldest_safe - 1, 0, 100, 0, slots)
                .unwrap()
                .overwritten
        );
    }

    #[test]
    fn a_generation_change_resets_the_cursor_instead_of_reporting_mass_loss() {
        // A restart makes the phone's sequence begin again. Without the generation
        // check the cursor would read that as thousands of lost frames and the metrics
        // would blame the ring for a reconnect.
        let restarted = ring_select_newest(9_000, 3, 5, 4, SLOT_COUNT as u64).unwrap();
        assert!(restarted.generation_changed);
        assert_eq!(0, restarted.skipped);
        assert_eq!(0, restarted.overwritten);
        assert_eq!(5, restarted.ring_sequence);
        // A stale cursor from a PREVIOUS generation must still be honoured as a reset
        // even when its sequence looks plausible.
        assert!(
            ring_select_newest(4, 1, 5, 2, SLOT_COUNT as u64)
                .unwrap()
                .generation_changed
        );
    }

    #[test]
    fn the_frame_trace_is_silent_until_asked_and_honours_the_gap_threshold() {
        let mut trace = FrameTrace::from_env();
        // Default must be off: this runs per frame at 30fps and stderr is shared with
        // the desktop's event stream.
        trace.every_frame = false;
        trace.gap_threshold_us = None;
        assert!(!trace.enabled());
        assert!(!trace.should_report());

        // Threshold mode reports only the late arrivals, which is what makes a long
        // capture readable.
        trace.gap_threshold_us = Some(50_000);
        assert!(trace.enabled());
        trace.arrival_delta_us = 49_999;
        assert!(!trace.should_report());
        trace.arrival_delta_us = 50_001;
        assert!(trace.should_report());

        // The first frame of a session has no predecessor, so its delta is zero and
        // must not be mistaken for an arrival that beat the threshold.
        trace.arrival_delta_us = 0;
        assert!(!trace.should_report());

        trace.every_frame = true;
        assert!(trace.should_report());
    }

    #[test]
    fn orientation_rejects_unsupported_angles_and_oversized_output() {
        let input = orientation_fixture(8, 4, 8, 8);
        let mut output = Vec::new();
        assert!(orient_nv12(&input, 8, 4, 8, 8, 45, false, &mut output)
            .unwrap_err()
            .contains("invalid NV12 rotation"));
        // A source larger than one ring slot must be refused, not truncated.
        let huge = vec![0u8; 4096 * 4096 * 3 / 2];
        assert!(
            orient_nv12(&huge, 4096, 4096, 4096, 4096, 90, false, &mut output).is_err(),
            "an oriented frame larger than a ring slot must be rejected"
        );
    }
}

impl Drop for SharedMemoryIpc {
    fn drop(&mut self) {
        unsafe {
            if !self.p_map.is_null() {
                let _ =
                    UnmapViewOfFile(windows::Win32::System::Memory::MEMORY_MAPPED_VIEW_ADDRESS {
                        Value: self.p_map,
                    });
            }
            if !self.h_map.is_invalid() && self.h_map.0 != 0 {
                let _ = CloseHandle(self.h_map);
            }
            if !self.h_file.is_invalid() && self.h_file.0 != 0 {
                let _ = CloseHandle(self.h_file);
            }
        }
    }
}

/// Direct-NV12 diagnostics isolate the ring/DLL/registration path from Android,
/// transport and H.264 decoding. No BGRA conversion is involved.
fn generate_nv12_pattern(
    kind: TestPatternKind,
    frame_counter: u64,
    width: u32,
    height: u32,
    output: &mut Vec<u8>,
) {
    let w = width as usize;
    let h = height as usize;
    output.resize(w * h * 3 / 2, 0);
    let (y_plane, uv_plane) = output.split_at_mut(w * h);
    match kind {
        TestPatternKind::Nv12Gradient => {
            for row in 0..h {
                for col in 0..w {
                    y_plane[row * w + col] = 16 + ((col * 219 / w.max(1)) as u8);
                }
            }
            uv_plane.fill(128);
        }
        TestPatternKind::SolidRed => fill_nv12(y_plane, uv_plane, 82, 90, 240),
        TestPatternKind::SolidGreen => fill_nv12(y_plane, uv_plane, 145, 54, 34),
        TestPatternKind::SolidBlue => fill_nv12(y_plane, uv_plane, 41, 240, 110),
        TestPatternKind::Nv12Bars => {
            const BARS: [(u8, u8, u8); 8] = [
                (235, 128, 128),
                (210, 16, 146),
                (170, 166, 16),
                (145, 54, 34),
                (106, 202, 222),
                (82, 90, 240),
                (41, 240, 110),
                (16, 128, 128),
            ];
            for row in 0..h {
                for col in 0..w {
                    let bar = (col * BARS.len() / w.max(1)).min(BARS.len() - 1);
                    y_plane[row * w + col] = BARS[bar].0;
                }
            }
            for row in 0..h / 2 {
                for col in (0..w).step_by(2) {
                    let bar = (col * BARS.len() / w.max(1)).min(BARS.len() - 1);
                    uv_plane[row * w + col] = BARS[bar].1;
                    uv_plane[row * w + col + 1] = BARS[bar].2;
                }
            }
            let scan = frame_counter as usize % h.max(1);
            y_plane[scan * w..(scan + 1) * w].fill(235);
        }
    }
}

fn fill_nv12(y_plane: &mut [u8], uv_plane: &mut [u8], y: u8, u: u8, v: u8) {
    y_plane.fill(y);
    for pair in uv_plane.chunks_exact_mut(2) {
        pair[0] = u;
        pair[1] = v;
    }
}

/// Minimal JSON string escaping for the single-line metrics output.
fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' | '\r' | '\t' => out.push(' '),
            c if (c as u32) < 0x20 => out.push(' '),
            c => out.push(c),
        }
    }
    out
}

fn build_http_client() -> Result<reqwest::blocking::Client, String> {
    // IMPORTANT: the default blocking client applies a 30s TOTAL request timeout,
    // which silently kills long-lived streaming responses and forces a reconnect
    // loop. Disable the total timeout, keep a connect timeout, and rely on TCP
    // keepalive to detect dead peers.
    reqwest::blocking::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(None::<Duration>)
        .tcp_keepalive(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())
}

fn set_error(slot: &Arc<Mutex<Option<String>>>, msg: String) {
    emit_event("error", "PRODUCER_ERROR", &msg, Some("FAILED"));
    *slot.lock().unwrap() = Some(msg);
}

fn clear_error(slot: &Arc<Mutex<Option<String>>>) {
    *slot.lock().unwrap() = None;
}

/// Per-frame timing trace across every stage the pipeline owns.
///
/// A smoothness complaint cannot be answered from frame-rate averages: a run of
/// frames arriving in a burst and a run arriving evenly both average to the same
/// rate. This records, for each access unit, where in the chain its time went, so
/// the stage responsible is read off data rather than argued about.
///
/// Opt-in and silent by default:
///   `OCB_TRACE_FRAMES=1`  every frame
///   `OCB_AU_GAP_MS=<ms>`  only frames whose ARRIVAL gap exceeded the threshold
///
/// Written to stderr, because stdout carries the metrics JSON the desktop parses.
///
/// The pairing that matters is `send_dus` against `arrive_dus`: the phone's own
/// send cadence versus our arrival cadence. Even send deltas with uneven arrivals
/// means the transport batched; uneven send deltas means it left the phone that
/// way.
struct FrameTrace {
    every_frame: bool,
    gap_threshold_us: Option<u64>,
    sequence: u64,
    keyframe: bool,
    bytes: usize,
    capture_pts_us: i64,
    send_delta_us: u32,
    arrival_delta_us: u64,
    received_at: Option<Instant>,
}

/// What one access unit cost inside the producer, in microseconds.
struct FrameStages {
    decode_us: u64,
    rotate_us: u64,
    write_us: u64,
    /// Frames the decoder emitted for this access unit. Zero is normal while the
    /// MFT builds its pipeline, and a persistent zero is itself the finding.
    outputs: u32,
}

impl FrameTrace {
    fn from_env() -> Self {
        Self {
            every_frame: std::env::var("OCB_TRACE_FRAMES")
                .map(|v| v != "0" && !v.is_empty())
                .unwrap_or(false),
            gap_threshold_us: std::env::var("OCB_AU_GAP_MS")
                .ok()
                .and_then(|v| v.parse::<f64>().ok())
                .map(|ms| (ms * 1000.0) as u64),
            sequence: 0,
            keyframe: false,
            bytes: 0,
            capture_pts_us: 0,
            send_delta_us: 0,
            arrival_delta_us: 0,
            received_at: None,
        }
    }

    fn enabled(&self) -> bool {
        self.every_frame || self.gap_threshold_us.is_some()
    }

    fn should_report(&self) -> bool {
        self.every_frame
            || self
                .gap_threshold_us
                .is_some_and(|threshold| self.arrival_delta_us > threshold)
    }

    fn begin(&mut self, record: &ocb2::Record, arrival_delta_us: u64) {
        if !self.enabled() {
            return;
        }
        self.sequence = record.sequence;
        self.keyframe = record.is_keyframe();
        self.bytes = record.payload.len();
        self.capture_pts_us = record.encoder_timestamp_us;
        self.send_delta_us = record.send_delta_us;
        self.arrival_delta_us = arrival_delta_us;
        self.received_at = Some(Instant::now());
    }

    /// Called when an access unit is discarded before reaching the decoder.
    ///
    /// Worth a line of its own: discarding everything up to the next IDR is how a
    /// reconnect turns into a visible freeze, and that is invisible in the rate
    /// counters because the frames never became decode attempts.
    fn skipped(&mut self, reason: &str) {
        if !self.enabled() || self.received_at.take().is_none() {
            return;
        }
        if self.should_report() {
            eprintln!(
                "OCB2-TRACE seq={} key={} bytes={} arrive_dus={} skipped={}",
                self.sequence,
                u8::from(self.keyframe),
                self.bytes,
                self.arrival_delta_us,
                reason
            );
        }
    }

    /// Called once the access unit has been decoded, oriented and offered to the ring.
    fn finish(&mut self, stages: &FrameStages) {
        if !self.enabled() {
            return;
        }
        let Some(received) = self.received_at.take() else {
            return;
        };
        // `total` is wall time held by this access unit; the three stage figures sum
        // to it, so a gap between them and `total` is time spent somewhere not yet
        // instrumented, which is worth seeing.
        let total_us = received.elapsed().as_micros() as u64;
        if !self.should_report() {
            return;
        }
        eprintln!(
            "OCB2-TRACE seq={} key={} bytes={} pts_us={} send_dus={} arrive_dus={} \
             decode_dus={} rotate_dus={} write_dus={} total_dus={} outputs={}",
            self.sequence,
            u8::from(self.keyframe),
            self.bytes,
            self.capture_pts_us,
            self.send_delta_us,
            self.arrival_delta_us,
            stages.decode_us,
            stages.rotate_us,
            stages.write_us,
            total_us,
            stages.outputs
        );
    }
}

/// How long to wait before re-opening the OCB2 stream.
///
/// A clean restart is NOT a failure and must not be paced like one. The phone
/// rebinds its camera routinely — the adaptive watchdog downgrades a profile that
/// cannot hold its frame rate, and a rebind announces end of stream — and applying
/// the failure backoff to that put a full second of nothing into the NV12 ring.
/// Both the desktop preview and the virtual camera read that ring, so it showed up
/// on both as a freeze followed by a burst of catch-up frames, while the phone's
/// own preview stayed smooth because it never leaves the phone.
fn reconnect_delay(clean: bool, consecutive_failures: u32) -> Duration {
    if clean {
        // Long enough not to spin if the phone is briefly not accepting, short
        // enough to cost a frame or two rather than a visible stall.
        Duration::from_millis(50)
    } else {
        Duration::from_secs(backoff_secs(consecutive_failures).min(2))
    }
}

fn backoff_secs(consecutive_failures: u32) -> u64 {
    match consecutive_failures {
        0 | 1 => 1,
        2 => 2,
        3 => 4,
        _ => 8,
    }
}

/// Compatibility resize applied after decoding the source-authoritative MJPEG
/// pixels. Android has already applied rotation and mirror before JPEG encode;
/// the producer must never transform them a second time.
struct StageTimings {
    rotate_ms: u32,
    resize_ms: u32,
    write_ms: u32,
    /// Which resize path ran: "simd", "standard", or "skipped".
    resize_backend: &'static str,
    /// Rotation applied to this frame (0/90/180/270), for the metrics log.
    rotation: u32,
}

/// SIMD-accelerated BGRA resize via fast_image_resize. Returns None on any
/// error so the caller falls back to the standard `image` resize. Resizing is
/// per-channel, so BGRA-in-an-RgbaImage is handled correctly as U8x4.
fn simd_resize(src: &image::RgbaImage, new_w: u32, new_h: u32) -> Option<Vec<u8>> {
    use fast_image_resize as fr;
    if new_w == 0 || new_h == 0 {
        return None;
    }
    // Borrow the source pixels directly (avoids a full-frame copy per frame).
    let src_img =
        fr::images::ImageRef::new(src.width(), src.height(), src.as_raw(), fr::PixelType::U8x4)
            .ok()?;
    let mut dst_img = fr::images::Image::new(new_w, new_h, fr::PixelType::U8x4);
    // A Resizer caches internal conversion buffers, so keep one per thread
    // instead of rebuilding it every frame.
    thread_local! {
        static RESIZER: std::cell::RefCell<fast_image_resize::Resizer> =
            std::cell::RefCell::new(fast_image_resize::Resizer::new());
    }
    RESIZER
        .with(|r| r.borrow_mut().resize(&src_img, &mut dst_img, None))
        .ok()?;
    Some(dst_img.into_vec())
}

/// Resizes `src` to new_w x new_h. Prefers the SIMD path; falls back to the
/// standard `image` resize if SIMD is disabled, errors, or returns an
/// unexpected buffer size (defensive: a wrong-size SIMD result must never reach
/// the framebuffer). Returns the BGRA bytes and the backend that produced them.
fn resize_rgba(
    src: &image::RgbaImage,
    new_w: u32,
    new_h: u32,
    allow_simd: bool,
) -> (Vec<u8>, &'static str) {
    if allow_simd {
        if let Some(out) = simd_resize(src, new_w, new_h) {
            if out.len() == (new_w as usize) * (new_h as usize) * 4 {
                return (out, "simd");
            }
        }
    }
    (
        image::imageops::resize(src, new_w, new_h, image::imageops::FilterType::Triangle)
            .into_raw(),
        "standard",
    )
}

/// Scales `src` to fit inside `out_w` x `out_h` while preserving its aspect
/// ratio, centered on an opaque black canvas (BGRA). Used when a 90/270
/// rotation leaves portrait content that would otherwise be stretched into a
/// landscape output. No pixels are cropped; unused space becomes black bars.
fn letterbox_into(
    src: &image::RgbaImage,
    out_w: u32,
    out_h: u32,
    allow_simd: bool,
) -> (Vec<u8>, &'static str) {
    let sw = src.width().max(1) as f64;
    let sh = src.height().max(1) as f64;
    let scale = (out_w as f64 / sw).min(out_h as f64 / sh);
    let new_w = ((sw * scale).round() as u32).clamp(1, out_w);
    let new_h = ((sh * scale).round() as u32).clamp(1, out_h);
    let (resized_raw, backend) = resize_rgba(src, new_w, new_h, allow_simd);

    let mut canvas = vec![0u8; (out_w as usize) * (out_h as usize) * 4];
    // Opaque black background (BGRA: alpha in byte 3).
    for px in canvas.chunks_exact_mut(4) {
        px[3] = 255;
    }

    let off_x = (out_w - new_w) / 2;
    let off_y = (out_h - new_h) / 2;
    let row_bytes = (new_w as usize) * 4;
    for row in 0..new_h as usize {
        let dst = (((off_y as usize + row) * out_w as usize + off_x as usize) * 4) as usize;
        let sptr = row * row_bytes;
        canvas[dst..dst + row_bytes].copy_from_slice(&resized_raw[sptr..sptr + row_bytes]);
    }
    (canvas, backend)
}

/// Decodes a JPEG to an RgbaImage whose bytes are BGRA (the framebuffer format)
/// using the faster pure-Rust zune-jpeg decoder. Returns None on any error so
/// the caller falls back to the image crate.
fn fast_jpeg_decode_bgra(jpeg: &[u8]) -> Option<image::RgbaImage> {
    use zune_jpeg::zune_core::colorspace::ColorSpace;
    use zune_jpeg::zune_core::options::DecoderOptions;
    use zune_jpeg::JpegDecoder;

    let opts = DecoderOptions::default().jpeg_set_out_colorspace(ColorSpace::RGBA);
    let mut decoder = JpegDecoder::new_with_options(jpeg, opts);
    let mut pixels = decoder.decode().ok()?;
    let (w, h) = decoder.dimensions()?;
    if pixels.len() < w * h * 4 {
        return None;
    }
    // RGBA -> BGRA in place.
    for px in pixels.chunks_exact_mut(4) {
        px.swap(0, 2);
    }
    image::RgbaImage::from_raw(w as u32, h as u32, pixels)
}

#[allow(clippy::too_many_arguments)]
fn resize_authoritative_mjpeg_write(
    rgba: image::RgbaImage,
    out_w: u32,
    out_h: u32,
    ipc: &SharedMemoryIpc,
    frame_counter: u64,
    allow_simd: bool,
) -> StageTimings {
    let oriented = rgba;
    let rotate_ms = 0;
    let rotation = 0;

    let resize_start = Instant::now();
    // The Media Foundation virtual camera renders a fixed output size, so the
    // frame must land in an `out_w` x `out_h` buffer no matter how it was
    // rotated.
    //
    // - Same orientation as the output box (no rotation, or a rotation that
    //   keeps landscape/portrait): stretch to fill, exactly as before. For a
    //   matching aspect ratio (the stable 16:9 MJPEG case) this is a plain
    //   resize with no visible change.
    // - Orientation flipped by an explicit 90/270 rotation (portrait content in
    //   a landscape box): fit while preserving aspect and pad with black.
    //   Stretching here is what produced the "vertically cropped"/squashed
    //   image; letterboxing rotates correctly without cropping.
    // Never distort the image. Only three cases produce a frame:
    //  - exact match: bit-for-bit copy (fast path, the stable 16:9 MJPEG/OBS case)
    //  - same aspect ratio: plain aspect-preserving scale, no bars
    //  - different aspect ratio (either an orientation flip or a 16:9-vs-4:3
    //    mismatch): aspect-fit with black bars (letterbox/pillarbox). This
    //    replaces the previous "same orientation -> stretch to fill", which
    //    squashed e.g. a 4:3 source into a 16:9 box.
    let same_aspect =
        (oriented.width() as u64) * (out_h as u64) == (out_w as u64) * (oriented.height() as u64);
    let (final_frame, resize_backend) = if oriented.width() == out_w && oriented.height() == out_h {
        (oriented.into_raw(), "skipped")
    } else if same_aspect {
        resize_rgba(&oriented, out_w, out_h, allow_simd)
    } else {
        letterbox_into(&oriented, out_w, out_h, allow_simd)
    };
    let resize_ms = resize_start.elapsed().as_millis() as u32;

    let write_start = Instant::now();
    ipc.write_frame(frame_counter, &final_frame, out_w, out_h);
    let write_ms = write_start.elapsed().as_millis() as u32;

    StageTimings {
        rotate_ms,
        resize_ms,
        write_ms,
        resize_backend,
        rotation,
    }
}

/// Case-insensitive `Content-Length:` lookup in a multipart part-header block.
/// Returns the parsed value, or None when the header is absent or malformed.
fn parse_content_length(headers: &[u8]) -> Option<usize> {
    const NEEDLE: &[u8] = b"content-length:";
    let mut i = 0;
    while i + NEEDLE.len() <= headers.len() {
        if headers[i..i + NEEDLE.len()].eq_ignore_ascii_case(NEEDLE) {
            let mut val = 0usize;
            let mut seen_digit = false;
            for &b in &headers[i + NEEDLE.len()..] {
                match b {
                    b' ' | b'\t' if !seen_digit => {}
                    b'0'..=b'9' => {
                        val = val.checked_mul(10)?.checked_add((b - b'0') as usize)?;
                        seen_digit = true;
                    }
                    _ => break,
                }
            }
            return if seen_digit { Some(val) } else { None };
        }
        i += 1;
    }
    None
}

#[allow(clippy::too_many_arguments)]
fn start_mjpeg_reader(
    url: String,
    token: Option<String>,
    latest_jpeg: Arc<Mutex<Option<Vec<u8>>>>,
    http_jpeg_counter: Arc<Mutex<u32>>,
    dropped_jpeg_counter: Arc<Mutex<u32>>,
    mjpeg_bytes_counter: Arc<Mutex<u64>>,
    wake_tx: SyncSender<()>,
    last_error: Arc<Mutex<Option<String>>>,
) {
    spawn(move || {
        let publish_jpeg = |jpeg_data: &[u8]| {
            {
                let mut lock = latest_jpeg.lock().unwrap();
                // dropped_jpegs counts STALE frames: a fully
                // received JPEG that the writer never consumed
                // because a newer one arrived first (latest-only
                // overwrite). This is the network/decode-can't-
                // keep-up signal surfaced in the metrics line; it
                // is normal when the phone sends faster than the
                // PC writes, and is NOT a decode error.
                if lock.is_some() {
                    let mut drops = dropped_jpeg_counter.lock().unwrap();
                    *drops += 1;
                }
                *lock = Some(jpeg_data.to_vec());
                let mut http_count = http_jpeg_counter.lock().unwrap();
                *http_count += 1;
                let mut bytes_lock = mjpeg_bytes_counter.lock().unwrap();
                *bytes_lock += jpeg_data.len() as u64;
            }
            // Wake the writer immediately instead of letting the frame wait
            // for the next pacing tick. Capacity-1 channel; a pending wake
            // already covers this frame.
            let _ = wake_tx.try_send(());
        };

        let mut failures: u32 = 0;
        loop {
            let client = match build_http_client() {
                Ok(c) => c,
                Err(e) => {
                    set_error(&last_error, format!("HTTP client init failed: {}", e));
                    sleep(Duration::from_secs(backoff_secs(failures)));
                    failures = failures.saturating_add(1);
                    continue;
                }
            };

            let mut request = client.get(&url);
            if let Some(t) = &token {
                request = request.header("X-OpenCamBridge-Token", t.as_str());
            }

            match request.send() {
                Ok(mut res) => {
                    if !res.status().is_success() {
                        let hint = if res.status().as_u16() == 401 {
                            " (unauthorized: check the LAN access token)"
                        } else {
                            ""
                        };
                        set_error(
                            &last_error,
                            format!("MJPEG stream returned HTTP {}{}", res.status(), hint),
                        );
                        failures = failures.saturating_add(1);
                    } else {
                        failures = 0;
                        clear_error(&last_error);

                        let mut buf = [0u8; 32768];
                        let mut frame_buffer: Vec<u8> = Vec::new();
                        // Content-Length mode: (payload start, payload length)
                        // once the part headers announced the exact JPEG size.
                        let mut exact: Option<(usize, usize)> = None;
                        // Marker-scan fallback: absolute offset the EOI search
                        // resumes from, so a growing partial frame is never
                        // re-scanned from the start on every chunk.
                        let mut eoi_scan_from: usize = 0;

                        loop {
                            match res.read(&mut buf) {
                                Ok(0) => {
                                    set_error(&last_error, "MJPEG stream ended (server closed connection); reconnecting".to_string());
                                    break;
                                }
                                Ok(n) => {
                                    frame_buffer.extend_from_slice(&buf[..n]);

                                    loop {
                                        if let Some((start, len)) = exact {
                                            // Exact-size read: wait until the whole
                                            // announced payload has arrived, then take
                                            // it without scanning its bytes.
                                            if frame_buffer.len() < start + len {
                                                break;
                                            }
                                            let end = start + len;
                                            publish_jpeg(&frame_buffer[start..end]);
                                            frame_buffer.drain(..end);
                                            exact = None;
                                            eoi_scan_from = 0;
                                            continue;
                                        }

                                        // The JPEG payload begins at the next SOI marker;
                                        // anything before it is the (short) multipart
                                        // boundary + part headers.
                                        let Some(start) =
                                            frame_buffer.windows(2).position(|w| w == [0xFF, 0xD8])
                                        else {
                                            break;
                                        };

                                        // Prefer the Content-Length announced in the part
                                        // headers (exact read); fall back to the EOI
                                        // marker scan when absent or implausible.
                                        if let Some(len) =
                                            parse_content_length(&frame_buffer[..start])
                                        {
                                            if len >= 2 && len <= 10_000_000 {
                                                exact = Some((start, len));
                                                continue;
                                            }
                                        }

                                        let from = eoi_scan_from.max(start + 2);
                                        if let Some(end_offset) = frame_buffer[from..]
                                            .windows(2)
                                            .position(|w| w == [0xFF, 0xD9])
                                        {
                                            let end = from + end_offset + 2;
                                            publish_jpeg(&frame_buffer[start..end]);
                                            frame_buffer.drain(..end);
                                            eoi_scan_from = 0;
                                        } else {
                                            eoi_scan_from = frame_buffer.len().saturating_sub(1);
                                            break;
                                        }
                                    }

                                    if frame_buffer.len() > 10_000_000 {
                                        frame_buffer.clear();
                                        exact = None;
                                        eoi_scan_from = 0;
                                    }
                                }
                                Err(e) => {
                                    set_error(
                                        &last_error,
                                        format!("MJPEG stream read error: {}; reconnecting", e),
                                    );
                                    break;
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    set_error(
                        &last_error,
                        format!("MJPEG connect failed: {}; retrying", e),
                    );
                    failures = failures.saturating_add(1);
                }
            }
            sleep(Duration::from_secs(backoff_secs(failures)));
        }
    });
}

// ---------------------------------------------------------------------------
// H.264 V2: OCB2 complete access units -> Media Foundation/D3D11 -> NV12 ring.
// OpenH264 below is a compatibility decoder only. OCB2 always feeds complete
// access units; no V2 code scans TCP chunks for Annex-B start codes.
// ---------------------------------------------------------------------------

enum V2Decoder {
    MediaFoundation(mf_decoder::MfH264Decoder),
    Software { decoder: Decoder, scratch: Vec<u8> },
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
struct MjpegMode {
    width: u32,
    height: u32,
    fps: u32,
}

fn select_canonical_mjpeg_fallback(
    desired: MjpegMode,
    supported: &[MjpegMode],
    rejected: &[MjpegMode],
) -> Option<MjpegMode> {
    supported
        .iter()
        .copied()
        .filter(|mode| {
            mode.width <= desired.width
                && mode.height <= desired.height
                && mode.fps <= desired.fps
                && !rejected.contains(mode)
        })
        .max_by_key(|mode| {
            (
                u8::from(mode.width == desired.width && mode.height == desired.height),
                mode.width as u64 * mode.height as u64,
                mode.fps,
            )
        })
}

fn parse_mjpeg_alternative(value: &str) -> Option<MjpegMode> {
    let tuple = value.trim().strip_prefix("MJPEG ").unwrap_or(value.trim());
    let (resolution, fps) = tuple.split_once('@')?;
    let (width, height) = resolution.split_once('x')?;
    Some(MjpegMode {
        width: width.trim().parse().ok()?,
        height: height.trim().parse().ok()?,
        fps: fps.trim().parse().ok()?,
    })
}

fn phone_get_json(
    client: &reqwest::blocking::Client,
    url: &str,
    token: Option<&str>,
) -> Result<serde_json::Value, String> {
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.header("X-OpenCamBridge-Token", token);
    }
    let response = request
        .send()
        .map_err(|e| format!("GET {url} failed: {e}"))?;
    let status = response.status();
    let text = response
        .text()
        .map_err(|e| format!("could not read {url} response: {e}"))?;
    if !status.is_success() {
        return Err(format!("GET {url} rejected with HTTP {status}: {text}"));
    }
    serde_json::from_str(&text).map_err(|e| format!("invalid JSON from {url}: {e}"))
}

fn pipeline_desired(status: &serde_json::Value) -> Result<(u64, String, MjpegMode), String> {
    let revision = status
        .get("revision")
        .and_then(|v| v.as_u64())
        .ok_or("phone status omitted authoritative revision")?;
    let desired = status
        .pointer("/snapshot/desired")
        .ok_or("phone status omitted snapshot.desired")?;
    let camera_id = desired
        .get("cameraId")
        .and_then(|v| v.as_str())
        .ok_or("phone status omitted desired cameraId")?
        .to_owned();
    let mode = MjpegMode {
        width: desired
            .get("width")
            .and_then(|v| v.as_u64())
            .ok_or("phone status omitted desired width")? as u32,
        height: desired
            .get("height")
            .and_then(|v| v.as_u64())
            .ok_or("phone status omitted desired height")? as u32,
        fps: desired
            .get("fps")
            .and_then(|v| v.as_u64())
            .ok_or("phone status omitted desired fps")? as u32,
    };
    Ok((revision, camera_id, mode))
}

fn camera_mjpeg_modes(
    capabilities: &serde_json::Value,
    camera_id: &str,
) -> Result<Vec<MjpegMode>, String> {
    let cameras = capabilities
        .get("cameras")
        .and_then(|v| v.as_array())
        .ok_or("pipeline capabilities omitted cameras")?;
    let camera = cameras
        .iter()
        .find(|camera| camera.get("id").and_then(|v| v.as_str()) == Some(camera_id))
        .ok_or_else(|| format!("camera {camera_id} is absent from pipeline capabilities"))?;
    serde_json::from_value(camera.get("mjpegModes").cloned().unwrap_or_default())
        .map_err(|e| format!("invalid canonical mjpegModes for camera {camera_id}: {e}"))
}

fn accepted_mjpeg_mode(response: &serde_json::Value) -> Option<MjpegMode> {
    let state = response.get("authoritativeState").unwrap_or(response);
    for path in ["/snapshot/selected", "/snapshot/desired"] {
        let Some(mode) = state.pointer(path) else {
            continue;
        };
        if mode.get("streamMode").and_then(|v| v.as_str()) != Some("mjpeg") {
            continue;
        }
        return Some(MjpegMode {
            width: mode.get("width")?.as_u64()? as u32,
            height: mode.get("height")?.as_u64()? as u32,
            fps: mode.get("fps")?.as_u64()? as u32,
        });
    }
    None
}

fn request_phone_mjpeg_fallback(args: &Args) -> Result<MjpegMode, String> {
    let stream_url = args.url.as_ref().ok_or("missing stream URL")?;
    let base_url = stream_url
        .strip_suffix("/stream.ocb2")
        .ok_or("OCB2 URL does not end in /stream.ocb2")?;
    let settings_url = format!("{base_url}/api/settings");
    let status_url = format!("{base_url}/api/camera/status");
    let capabilities_url = format!("{base_url}/api/pipeline/capabilities");
    let client = build_http_client()?;
    let mut rejected = Vec::new();
    let mut alternatives: Option<Vec<MjpegMode>> = None;

    for _ in 0..4 {
        let status = phone_get_json(&client, &status_url, args.token.as_deref())?;
        let capabilities = phone_get_json(&client, &capabilities_url, args.token.as_deref())?;
        let (revision, camera_id, desired) = pipeline_desired(&status)?;
        let canonical = camera_mjpeg_modes(&capabilities, &camera_id)?;
        let eligible = alternatives
            .take()
            .map(|values| {
                values
                    .into_iter()
                    .filter(|m| canonical.contains(m))
                    .collect()
            })
            .unwrap_or_else(|| canonical.clone());
        let selected =
            select_canonical_mjpeg_fallback(desired, &eligible, &rejected).ok_or_else(|| {
                format!(
                    "camera {camera_id} has no canonical MJPEG fallback at or below {}x{}@{}",
                    desired.width, desired.height, desired.fps
                )
            })?;
        let request_id = format!("producer-{}-{}", std::process::id(), monotonic_ns());
        let body = serde_json::json!({
            "streamMode": "mjpeg",
            "width": selected.width,
            "height": selected.height,
            "fps": selected.fps,
            "clientType": "producer",
            "baseRevision": revision,
            "requestId": request_id
        })
        .to_string();
        let mut request = client
            .post(&settings_url)
            .header(reqwest::header::CONTENT_TYPE, "application/json")
            .body(body);
        if let Some(token) = &args.token {
            request = request.header("X-OpenCamBridge-Token", token);
        }
        let response = request
            .send()
            .map_err(|e| format!("could not request phone MJPEG fallback: {e}"))?;
        let response_status = response.status();
        let response_text = response
            .text()
            .map_err(|e| format!("could not read MJPEG fallback response: {e}"))?;
        let response_json: serde_json::Value = serde_json::from_str(&response_text)
            .unwrap_or_else(|_| serde_json::json!({"message": response_text}));
        if response_status.is_success() {
            return Ok(accepted_mjpeg_mode(&response_json).unwrap_or(selected));
        }
        match response_status.as_u16() {
            409 => continue,
            422 => {
                rejected.push(selected);
                let parsed: Vec<MjpegMode> = response_json
                    .get("alternatives")
                    .and_then(|v| v.as_array())
                    .into_iter()
                    .flatten()
                    .filter_map(|v| v.as_str().and_then(parse_mjpeg_alternative))
                    .collect();
                if parsed.is_empty() {
                    return Err(format!("phone rejected MJPEG fallback with HTTP 422 and no parseable alternatives: {response_text}"));
                }
                alternatives = Some(parsed);
            }
            _ => {
                return Err(format!(
                    "phone rejected MJPEG fallback with HTTP {response_status}: {response_text}"
                ))
            }
        }
    }
    Err("phone MJPEG fallback did not converge after revision/alternative retries".into())
}

impl V2Decoder {
    fn name(&self) -> &str {
        match self {
            Self::MediaFoundation(d) => &d.name,
            Self::Software { .. } => "OpenH264 software fallback",
        }
    }
    fn is_media_foundation(&self) -> bool {
        matches!(self, Self::MediaFoundation(_))
    }
    fn d3d11_output_active(&self) -> bool {
        matches!(self, Self::MediaFoundation(d) if d.d3d11_output_active)
    }
    fn hardware_decode(&self) -> Option<bool> {
        match self {
            Self::MediaFoundation(_) => None,
            Self::Software { .. } => Some(false),
        }
    }
}

fn create_v2_decoder(
    info: &ocb2::StreamInfo,
    config: &[u8],
    force_software: bool,
) -> Result<V2Decoder, String> {
    if !force_software {
        match mf_decoder::MfH264Decoder::new(
            info.width,
            info.height,
            info.fps_numerator / info.fps_denominator.max(1),
            config,
        ) {
            Ok(decoder) => return Ok(V2Decoder::MediaFoundation(decoder)),
            Err(e) => emit_event(
                "warning",
                "HARDWARE_DECODER_UNAVAILABLE",
                &e,
                Some("FALLBACK"),
            ),
        }
    }
    let mut decoder =
        Decoder::new().map_err(|e| format!("OpenH264 fallback initialization failed: {e}"))?;
    if !config.is_empty() {
        let _ = decoder.decode(config);
    }
    Ok(V2Decoder::Software {
        decoder,
        scratch: vec![0; info.width as usize * info.height as usize * 3 / 2],
    })
}

fn copy_i420_to_nv12(yuv: &impl YUVSource, scratch: &mut Vec<u8>) -> Result<(u32, u32), String> {
    let (width, height) = yuv.dimensions();
    if width == 0
        || height == 0
        || width > 1920
        || height > 1080
        || width % 2 != 0
        || height % 2 != 0
    {
        return Err(format!("invalid software decoder frame {width}x{height}"));
    }
    let needed = width * height * 3 / 2;
    scratch.resize(needed, 0);
    let (sy, su, sv) = yuv.strides();
    let y = yuv.y();
    let u = yuv.u();
    let v = yuv.v();
    if y.len() < sy * height || u.len() < su * (height / 2) || v.len() < sv * (height / 2) {
        return Err("software decoder returned invalid plane bounds".to_string());
    }
    for row in 0..height {
        scratch[row * width..(row + 1) * width].copy_from_slice(&y[row * sy..row * sy + width]);
    }
    let uv_base = width * height;
    for row in 0..height / 2 {
        for col in 0..width / 2 {
            scratch[uv_base + row * width + col * 2] = u[row * su + col];
            scratch[uv_base + row * width + col * 2 + 1] = v[row * sv + col];
        }
    }
    Ok((width as u32, height as u32))
}

/// Rotate/mirror directly in NV12. This is used only for explicit orientation
/// controls and never passes through RGBA/BGRA. The following consumer-side
/// D3D11 video-processor pass performs any aspect-preserving resize once.
///
/// The transform is dispatched ONCE per plane rather than once per sample. The
/// earlier form called a mapping closure containing `if mirror` and
/// `match rotation` for every pixel — roughly 2.6M branchy iterations per 1080p
/// frame, on the same thread that drains the OCB2 socket. 0° and 180° are now
/// row operations, and 90°/270° use a tiled transpose so neither the source nor
/// the destination walk is a cache miss per sample. `orientation_reference` in
/// the tests below pins the output to the original per-pixel mapping.
fn orient_nv12(
    input: &[u8],
    width: u32,
    height: u32,
    y_stride: u32,
    uv_stride: u32,
    rotation: u32,
    mirror: bool,
    output: &mut Vec<u8>,
) -> Result<(u32, u32), String> {
    if !matches!(rotation, 0 | 90 | 180 | 270) {
        return Err(format!("invalid NV12 rotation {rotation}"));
    }
    validate_nv12_metadata(width, height, y_stride, uv_stride, input.len())
        .ok_or_else(|| "invalid source NV12 metadata for orientation".to_string())?;
    let (out_width, out_height) = if rotation == 90 || rotation == 270 {
        (height, width)
    } else {
        (width, height)
    };
    let needed = out_width as usize * out_height as usize * 3 / 2;
    if needed > MAX_NV12_SIZE {
        return Err("oriented NV12 frame exceeds ring slot".to_string());
    }
    output.resize(needed, 0);

    // Luma: one sample per pixel, source rows `y_stride` bytes apart.
    let (luma_out, chroma_out) = output.split_at_mut(out_width as usize * out_height as usize);
    transform_plane(
        input,
        0,
        y_stride as usize,
        width as usize,
        height as usize,
        luma_out,
        1,
        rotation,
        mirror,
    );
    // Chroma: interleaved U/V pairs, so a 2-byte sample on a (w/2 x h/2) grid
    // whose rows are `uv_stride` bytes apart.
    transform_plane(
        input,
        y_stride as usize * height as usize,
        uv_stride as usize,
        width as usize / 2,
        height as usize / 2,
        chroma_out,
        2,
        rotation,
        mirror,
    );
    Ok((out_width, out_height))
}

/// Rotate/mirror one plane of `elem`-byte samples from `src[src_offset..]` into
/// `dst`, which is exactly the destination plane and tightly packed.
///
/// Callers guarantee the bounds (`validate_nv12_metadata` on the source, and a
/// `resize` to the oriented size on the destination), so this cannot fail.
#[allow(clippy::too_many_arguments)]
fn transform_plane(
    src: &[u8],
    src_offset: usize,
    src_row_stride: usize,
    src_w: usize,
    src_h: usize,
    dst: &mut [u8],
    elem: usize,
    rotation: u32,
    mirror: bool,
) {
    let src_row = |y: usize| {
        let start = src_offset + y * src_row_stride;
        &src[start..start + src_w * elem]
    };

    match (rotation, mirror) {
        // Straight copy. Reached only when the caller asks for a plain resize of
        // an already-correct orientation; the streaming loop skips this case.
        (0, false) => {
            for y in 0..src_h {
                let d = y * src_w * elem;
                dst[d..d + src_w * elem].copy_from_slice(src_row(y));
            }
        }
        // Horizontal flip: same row order, samples reversed within the row.
        (0, true) => {
            for y in 0..src_h {
                reverse_row(src_row(y), &mut dst[y * src_w * elem..], src_w, elem);
            }
        }
        // 180° with a horizontal flip is a pure vertical flip: the two
        // horizontal reversals cancel, so every row is a straight copy.
        (180, true) => {
            for y in 0..src_h {
                let d = y * src_w * elem;
                dst[d..d + src_w * elem].copy_from_slice(src_row(src_h - 1 - y));
            }
        }
        // 180°: rows bottom-to-top, samples reversed within each row.
        (180, false) => {
            for y in 0..src_h {
                reverse_row(
                    src_row(src_h - 1 - y),
                    &mut dst[y * src_w * elem..],
                    src_w,
                    elem,
                );
            }
        }
        // 90°/270° transpose, so out_w == src_h and out_h == src_w. Walk output
        // tiles to keep both sides of the copy inside cache.
        _ => {
            let out_w = src_h;
            let out_h = src_w;
            const TILE: usize = 32;
            macro_rules! tiled {
                ($x:ident, $y:ident, $sx:expr, $sy:expr) => {{
                    let mut ty = 0;
                    while ty < out_h {
                        let ty_end = (ty + TILE).min(out_h);
                        let mut tx = 0;
                        while tx < out_w {
                            let tx_end = (tx + TILE).min(out_w);
                            for $y in ty..ty_end {
                                let dst_row = $y * out_w * elem;
                                for $x in tx..tx_end {
                                    let s = src_offset + ($sy) * src_row_stride + ($sx) * elem;
                                    let d = dst_row + $x * elem;
                                    dst[d..d + elem].copy_from_slice(&src[s..s + elem]);
                                }
                            }
                            tx = tx_end;
                        }
                        ty = ty_end;
                    }
                }};
            }
            match (rotation, mirror) {
                (90, false) => tiled!(x, y, y, src_h - 1 - x),
                (90, true) => tiled!(x, y, y, x),
                (270, false) => tiled!(x, y, src_w - 1 - y, x),
                _ => tiled!(x, y, src_w - 1 - y, src_h - 1 - x),
            }
        }
    }
}

/// Copy one row of `count` `elem`-byte samples in reverse sample order.
#[inline]
fn reverse_row(src: &[u8], dst: &mut [u8], count: usize, elem: usize) {
    if elem == 1 {
        for (out, sample) in dst[..count].iter_mut().zip(src[..count].iter().rev()) {
            *out = *sample;
        }
        return;
    }
    for x in 0..count {
        let s = (count - 1 - x) * elem;
        let d = x * elem;
        dst[d..d + elem].copy_from_slice(&src[s..s + elem]);
    }
}

/// Depth of the queue between the socket reader and the decoder.
///
/// Deliberately shallow. In steady state it stays empty, so it adds no latency;
/// its only job is to absorb a per-frame decode hiccup so the reader keeps
/// draining the socket. If the decoder is *sustainably* slower than realtime the
/// queue fills and the reader blocks, which is the correct signal — masking that
/// with a deep buffer would trade a visible stall for growing latency.
const READER_QUEUE_DEPTH: usize = 4;

enum ReaderEvent {
    Record(ocb2::Record),
    /// The connection ended. `expected` distinguishes a clean close — the phone
    /// rebinding its camera, which it does routinely — from a genuine failure.
    /// Reconnect pacing depends on which it was.
    Ended {
        reason: String,
        expected: bool,
    },
}

/// Aligns the phone's capture clock to this machine's monotonic clock.
///
/// The clocks have an unknown constant skew, so latency needs an offset.
/// Estimating that offset from a single frame bakes that frame's own queueing
/// delay into every later measurement — and the first frame of a connection is
/// the worst possible sample, because it is the first IDR behind encoder warm-up
/// and decoder initialisation. This tracks the MINIMUM observed
/// `receive - capture` instead: the least-delayed frame is the closest thing to
/// pure clock skew that is observable without clock synchronisation.
///
/// Reported latency is therefore capture-to-commit minus the best transit seen,
/// i.e. a tight lower bound rather than the near-zero number a first-frame
/// offset produces.
struct CaptureClock {
    offset: Option<i128>,
    window_min: Option<i128>,
    window_started: Option<Instant>,
}

impl CaptureClock {
    /// How long a minimum is trusted before it is re-based. Long enough to see
    /// a good sample, short enough that a one-off outlier cannot pin the
    /// estimate for the whole session.
    const WINDOW: Duration = Duration::from_secs(4);

    fn new() -> Self {
        Self {
            offset: None,
            window_min: None,
            window_started: None,
        }
    }

    /// Feed one frame and return its latency in nanoseconds.
    fn latency_ns(
        &mut self,
        capture_ns: u64,
        receive_ns: u64,
        committed_ns: u64,
        now: Instant,
    ) -> u64 {
        let sample = receive_ns as i128 - capture_ns as i128;
        self.window_min = Some(match self.window_min {
            Some(current) => current.min(sample),
            None => sample,
        });
        let started = *self.window_started.get_or_insert(now);

        match self.offset {
            // Adopt a tighter estimate at once. This also self-heals a change of
            // timestamp base (a camera switch can change the phone's clock
            // source), which would otherwise read as permanently zero latency.
            Some(current) if sample < current => self.offset = Some(sample),
            Some(_) if now.duration_since(started) >= Self::WINDOW => {
                self.offset = self.window_min;
                // Re-base so the estimate can rise again if the old minimum was
                // a fluke.
                self.window_min = Some(sample);
                self.window_started = Some(now);
            }
            Some(_) => {}
            None => {
                self.offset = Some(sample);
                self.window_started = Some(now);
            }
        }

        let offset = self.offset.unwrap_or(0);
        let aligned_capture = capture_ns as i128 + offset;
        (committed_ns as i128 - aligned_capture).max(0) as u64
    }
}

/// Owns the socket for one connection: reads, frames OCB2 records, and hands
/// them to the decoder. Split from the decode loop so that decoding, rotating
/// and copying a frame never stops the socket being drained — the phone
/// disconnects a client whose queue overflows, so a stall on this side used to
/// cost a full reconnect and a fresh keyframe rather than a hiccup.
fn spawn_ocb2_reader(
    mut response: reqwest::blocking::Response,
    events: SyncSender<ReaderEvent>,
    recycled: std::sync::mpsc::Receiver<Vec<u8>>,
    bytes_received: Arc<std::sync::atomic::AtomicU64>,
) {
    spawn(move || {
        let mut parser = ocb2::Parser::new();
        let mut network_buffer = [0u8; 64 * 1024];
        loop {
            // Reclaim allocations the decoder has finished with, so steady-state
            // access units reuse storage instead of allocating per frame.
            while let Ok(payload) = recycled.try_recv() {
                parser.recycle_payload(payload);
            }
            let count = match response.read(&mut network_buffer) {
                Ok(0) => {
                    // The phone closed the stream. Normal during a rebind.
                    let _ = events.send(ReaderEvent::Ended {
                        reason: "OCB2 connection ended; reconnecting at a keyframe".into(),
                        expected: true,
                    });
                    return;
                }
                Err(e) => {
                    let _ = events.send(ReaderEvent::Ended {
                        reason: format!("OCB2 read failed: {e}"),
                        expected: false,
                    });
                    return;
                }
                Ok(count) => count,
            };
            bytes_received.fetch_add(count as u64, std::sync::atomic::Ordering::Relaxed);
            parser.push(&network_buffer[..count]);
            loop {
                match parser.next() {
                    Ok(Some(record)) => {
                        // A send error means the decode loop has moved on to a
                        // new connection; this thread is done.
                        if events.send(ReaderEvent::Record(record)).is_err() {
                            return;
                        }
                    }
                    Ok(None) => break,
                    Err(e) => {
                        let _ = events.send(ReaderEvent::Ended {
                            reason: format!("Malformed OCB2 record: {e:?}"),
                            expected: false,
                        });
                        return;
                    }
                }
            }
        }
    });
}

fn run_h264_v2(args: &Args, ipc: &SharedMemoryIpc) -> Result<(), String> {
    emit_event(
        "info",
        "PRODUCER_STATE",
        "Starting OCB2 pipeline",
        Some("STARTING"),
    );
    let url = args.url.clone().ok_or("URL is required for OCB2 H.264")?;
    let mut stream_info: Option<ocb2::StreamInfo> = None;
    let mut codec_config = Vec::new();
    let mut decoder: Option<V2Decoder> = None;
    let mut waiting_for_keyframe: bool;
    let mut force_software = false;
    let mut consecutive_decode_errors = 0u32;
    let mut failures = 0u32;
    let mut last_print = Instant::now();
    let mut received_frames = 0u32;
    let mut decoded_unique = 0u32;
    let mut replaced_frames = 0u64;
    let mut last_published_sequence = 0u64;
    let mut ring_frames_committed = 0u64;
    // Nanosecond accumulators, split per stage. `decode_ns_sum` is time inside
    // the decoder MINUS the rotate and ring-write work our own callback does
    // while the decoder call is still on the stack, so the three add up to the
    // real per-frame cost instead of one opaque figure.
    let mut decode_ns_sum = 0u64;
    let mut rotate_ns_sum = 0u64;
    let mut write_ns_sum = 0u64;
    let mut latency_ms_sum = 0u64;
    let mut latency_samples = 0u64;
    let mut first_ring_frame_emitted = false;
    let mut low_software_windows = 0u32;
    let mut capture_clock = CaptureClock::new();
    let mut last_error: Option<String> = None;
    let mut oriented_nv12 = Vec::with_capacity(MAX_NV12_SIZE);
    let mut phone_encoder_error: bool;
    let (mut last_vcam_unique, mut last_vcam_repeated) = ipc.virtual_camera_counters();
    let mut consumer_readiness = ConsumerReadiness::new();
    // Per-frame stage timing; see FrameTrace. Silent unless OCB_TRACE_FRAMES or
    // OCB_AU_GAP_MS is set.
    //
    // This is how the "freezes then jumps" report was diagnosed: it showed that
    // frames are not lost (transport and decoded rates both hold, replaced stays
    // zero) but ARRIVE IN BURSTS of two or three, so every second or third access
    // unit shows a ~75ms gap while the ones between arrive back to back. Keep it
    // for the next time a smoothness complaint needs evidence rather than theory.
    let mut last_au_instant: Option<Instant> = None;
    let mut frame_trace = FrameTrace::from_env();

    loop {
        consumer_readiness.reset();
        waiting_for_keyframe = true;
        phone_encoder_error = false;
        // Each connection restarts the phone's frame sequence, so retire every
        // consumer cursor before the first frame of the new one lands.
        ipc.bump_stream_generation();
        if let Some(V2Decoder::MediaFoundation(d)) = decoder.as_mut() {
            let _ = d.flush();
        }

        emit_event(
            "info",
            "PRODUCER_STATE",
            "Connecting to OCB2 endpoint",
            Some("CONNECTING"),
        );
        let client = build_http_client()?;
        let mut request = client.get(&url);
        if let Some(token) = &args.token {
            request = request.header("X-OpenCamBridge-Token", token);
        }
        let response = request.send();
        let response = match response {
            Ok(r) if r.status().is_success() => {
                failures = 0;
                emit_event(
                    "info",
                    "PRODUCER_STATE",
                    "OCB2 connected; waiting for stream information",
                    Some("WAITING_FOR_STREAM_INFO"),
                );
                r
            }
            Ok(r) => {
                failures += 1;
                last_error = Some(format!("OCB2 endpoint returned HTTP {}", r.status()));
                sleep(Duration::from_secs(backoff_secs(failures)));
                continue;
            }
            Err(e) => {
                failures += 1;
                last_error = Some(format!("OCB2 connect failed: {e}"));
                sleep(Duration::from_secs(backoff_secs(failures)));
                continue;
            }
        };

        let bytes_received = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let (event_tx, event_rx) = sync_channel::<ReaderEvent>(READER_QUEUE_DEPTH);
        let (recycle_tx, recycle_rx) = sync_channel::<Vec<u8>>(READER_QUEUE_DEPTH * 2);
        spawn_ocb2_reader(response, event_tx, recycle_rx, Arc::clone(&bytes_received));

        // Set when the disconnect was the phone restarting cleanly rather than a
        // failure. Backing off a full second on a clean restart was the visible
        // freeze: the adaptive watchdog rebinds, the phone announces end of stream,
        // and the ring then received nothing for a second before frames resumed in
        // a burst.
        let mut clean_disconnect = false;
        'connection: loop {
            // The metrics tick runs before the record wait, so a pipeline that is
            // starved — waiting for a keyframe, or receiving nothing at all —
            // still publishes state instead of going silent.
            if last_print.elapsed() >= Duration::from_secs(1) {
                let seconds = last_print.elapsed().as_secs_f64().max(0.001);
                let window_bytes = bytes_received.swap(0, std::sync::atomic::Ordering::Relaxed);
                let transport_fps = (received_frames as f64 / seconds).round() as u32;
                let decoded_fps = (decoded_unique as f64 / seconds).round() as u32;
                let per_frame_ms = |total_ns: u64| {
                    if decoded_unique > 0 {
                        total_ns / decoded_unique as u64 / 1_000_000
                    } else {
                        0
                    }
                };
                let decode_avg = per_frame_ms(decode_ns_sum);
                let rotate_avg = per_frame_ms(rotate_ns_sum);
                let write_avg = per_frame_ms(write_ns_sum);
                let total_avg = per_frame_ms(decode_ns_sum + rotate_ns_sum + write_ns_sum);
                let latency = if latency_samples > 0 {
                    latency_ms_sum / latency_samples
                } else {
                    0
                };
                let (decoder_name, d3d11_output, hardware_decode, media_foundation) = decoder
                    .as_ref()
                    .map(|d| {
                        (
                            d.name(),
                            d.d3d11_output_active(),
                            d.hardware_decode(),
                            d.is_media_foundation(),
                        )
                    })
                    .unwrap_or(("initializing", false, None, false));
                let hardware_decode_json = hardware_decode
                    .map(|value| value.to_string())
                    .unwrap_or_else(|| "null".into());
                let info = stream_info.as_ref();
                let (consumer_width, consumer_height, _) = ipc.consumer_format();
                let (vcam_unique_total, vcam_repeated_total) = ipc.virtual_camera_counters();
                let vcam_unique_fps = ((vcam_unique_total.saturating_sub(last_vcam_unique)) as f64
                    / seconds)
                    .round() as u32;
                let repeated_samples = vcam_repeated_total.saturating_sub(last_vcam_repeated);
                last_vcam_unique = vcam_unique_total;
                last_vcam_repeated = vcam_repeated_total;
                let err_json = last_error
                    .as_ref()
                    .map(|e| format!("\"{}\"", json_escape(e)))
                    .unwrap_or_else(|| "null".into());
                let ring = ipc.diagnostics();
                let ring_json = serde_json::to_string(&ring).unwrap_or_else(|_| "{}".into());
                let readiness = consumer_readiness.observe(&ring, Instant::now());
                let readiness_json =
                    serde_json::to_string(&readiness).unwrap_or_else(|_| "{}".into());
                let virtual_camera_ready = readiness.ready;
                println!(
                    r#"{{"type":"metrics","producer_state":"WRITING_RING","ring_frames_committed":{},"source":"ocb2-h264","profile":"{}","source_width":{},"source_height":{},"output_width":{},"output_height":{},"fps_target":{},"http_jpeg_fps":0,"decoded_fps":{},"written_fps":{},"transport_fps":{},"decoded_unique_fps":{},"virtual_camera_unique_fps":{},"repeated_samples":{},"dropped_jpegs":0,"replaced_frames":{},"jpeg_queue_len":0,"decode_ms_avg":{},"rotate_ms_avg":{},"resize_ms_avg":0,"write_ms_avg":{},"total_pipeline_ms":{},"latency_ms":{},"bytes_per_sec":{},"estimated_mbps":"{:.2}","pixel_format":"NV12","decode_backend":"{}","decoder_name":"{}","d3d11_output":{},"hardware_decoder":{},"encoder_name":"{}","hardware_encoder":{},"camera_id":"{}","fallback_reason":"{}","resize_backend":"nv12-transform","rotation":{},"mirror":{},"sensor_orientation":{},"device_rotation":{},"last_error":{},"ring":{},"virtual_camera_readiness":{},"virtual_camera_ready":{}}}"#,
                    ring_frames_committed,
                    json_escape(&args.profile),
                    info.map(|i| i.width).unwrap_or(0),
                    info.map(|i| i.height).unwrap_or(0),
                    consumer_width,
                    consumer_height,
                    info.map(|i| i.fps_numerator / i.fps_denominator.max(1))
                        .unwrap_or(0),
                    decoded_fps,
                    decoded_fps,
                    transport_fps,
                    decoded_fps,
                    vcam_unique_fps,
                    repeated_samples,
                    replaced_frames,
                    decode_avg,
                    rotate_avg,
                    write_avg,
                    total_avg,
                    latency,
                    (window_bytes as f64 / seconds) as u64,
                    (window_bytes as f64 * 8.0 / seconds) / 1_000_000.0,
                    if media_foundation {
                        "media-foundation-d3d11"
                    } else {
                        "software-fallback"
                    },
                    json_escape(decoder_name),
                    d3d11_output,
                    hardware_decode_json,
                    info.map(|i| json_escape(&i.encoder_name))
                        .unwrap_or_default(),
                    info.map(|i| i.hardware_encoder).unwrap_or(false),
                    info.map(|i| json_escape(&i.camera_id)).unwrap_or_default(),
                    if media_foundation {
                        ""
                    } else {
                        "hardware decoder unavailable"
                    },
                    info.map(|i| i.effective_rotation).unwrap_or(0),
                    info.map(|i| i.mirror).unwrap_or(false),
                    info.map(|i| i.sensor_orientation).unwrap_or(0),
                    info.map(|i| i.device_rotation).unwrap_or(0),
                    err_json,
                    ring_json,
                    readiness_json,
                    virtual_camera_ready
                );
                if matches!(&decoder, Some(V2Decoder::Software { .. })) {
                    let target = info
                        .map(|i| i.fps_numerator / i.fps_denominator.max(1))
                        .unwrap_or(0);
                    low_software_windows = if target > 0 && decoded_fps * 100 < target * 80 {
                        low_software_windows + 1
                    } else {
                        0
                    };
                    if low_software_windows >= 3 {
                        return Err(format!(
                            "software H.264 decode sustained only {decoded_fps}/{target} FPS"
                        ));
                    }
                } else {
                    low_software_windows = 0;
                }
                received_frames = 0;
                decoded_unique = 0;
                decode_ns_sum = 0;
                rotate_ns_sum = 0;
                write_ns_sum = 0;
                latency_ms_sum = 0;
                latency_samples = 0;
                last_print = Instant::now();
            }

            let record = match event_rx.recv_timeout(Duration::from_millis(100)) {
                Ok(ReaderEvent::Record(record)) => record,
                Ok(ReaderEvent::Ended { reason, expected }) => {
                    last_error = Some(reason);
                    clean_disconnect = expected;
                    break 'connection;
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue 'connection,
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    last_error = Some("OCB2 reader stopped".into());
                    break 'connection;
                }
            };
            let receive_ns = monotonic_ns();
            // The discontinuity flag is a property of the STREAM, not of one
            // record type: the protocol says a client flushes its decoder on a
            // discontinuity and waits for a keyframe. Handling it here rather
            // than only inside the video arm lets the phone signal a transport
            // reset with an empty record — it drops a slow client's backlog and
            // marks a heartbeat as discontinuous instead of closing the
            // connection, which used to cost a full reconnect.
            if record.is_discontinuity() {
                if let Some(V2Decoder::MediaFoundation(d)) = decoder.as_mut() {
                    let _ = d.flush();
                }
                waiting_for_keyframe = true;
            }
            match record.record_type {
                ocb2::TYPE_STREAM_INFO => {
                    match serde_json::from_slice::<ocb2::StreamInfo>(&record.payload) {
                        Ok(info)
                            if info.codec == "H264"
                                && info.framing == "annex-b-access-units"
                                && info.bitrate <= 100_000_000
                                && info.pixel_format == "NV12"
                                && info.has_valid_transform()
                                && validate_nv12_metadata(
                                    info.width,
                                    info.height,
                                    info.width,
                                    info.width,
                                    info.width as usize * info.height as usize * 3 / 2,
                                )
                                .is_some() =>
                        {
                            let decode_format_changed =
                                h264_decode_format_changed(stream_info.as_ref(), &info);
                            ipc.set_source_fps(info.fps_numerator, info.fps_denominator);
                            stream_info = Some(info);
                            if decode_format_changed {
                                consumer_readiness.reset();
                                // New geometry means the frames already in the ring
                                // describe a different picture; cursors must not carry
                                // across it.
                                ipc.bump_stream_generation();
                                decoder = None;
                                codec_config.clear();
                                waiting_for_keyframe = true;
                                emit_event(
                                    "info",
                                    "PRODUCER_STATE",
                                    "Stream information accepted",
                                    Some("WAITING_FOR_CODEC_CONFIG"),
                                );
                            } else {
                                emit_event(
                                    "info",
                                    "STREAM_METADATA_UPDATED",
                                    "Transform metadata updated without interrupting H.264 decode",
                                    None,
                                );
                            }
                        }
                        Ok(_) => {
                            last_error = Some("Unsupported OCB2 stream information".into());
                            break 'connection;
                        }
                        Err(e) => {
                            last_error = Some(format!("Invalid OCB2 stream information: {e}"));
                            break 'connection;
                        }
                    }
                }
                ocb2::TYPE_CODEC_CONFIG if record.flags & ocb2::FLAG_CODEC_CONFIG != 0 => {
                    codec_config.clear();
                    codec_config.extend_from_slice(&record.payload);
                    decoder = None;
                    waiting_for_keyframe = true;
                    emit_event(
                        "info",
                        "PRODUCER_STATE",
                        "Codec configuration accepted",
                        Some("WAITING_FOR_KEYFRAME"),
                    );
                }
                ocb2::TYPE_VIDEO_ACCESS_UNIT => {
                    received_frames += 1;
                    let arrival_delta_us = last_au_instant
                        .map(|previous: Instant| previous.elapsed().as_micros() as u64)
                        .unwrap_or(0);
                    last_au_instant = Some(Instant::now());
                    frame_trace.begin(&record, arrival_delta_us);
                    // A discontinuity on this record was already handled above.
                    if waiting_for_keyframe && !record.is_keyframe() {
                        frame_trace.skipped("waiting_for_keyframe");
                        let _ = recycle_tx.try_send(record.payload);
                        continue;
                    }
                    if stream_info.is_none() {
                        frame_trace.skipped("no_stream_info");
                        let _ = recycle_tx.try_send(record.payload);
                        continue;
                    }
                    let info = stream_info.as_ref().expect("checked above");
                    // AVC permits SPS/PPS to be prepended in-band to an IDR.
                    // Some Qualcomm MediaCodec encoders do this without a
                    // separate config buffer, so allow the keyframe to
                    // bootstrap the decoder.
                    if decoder.is_none() {
                        decoder = Some(create_v2_decoder(info, &codec_config, force_software)?);
                        let selected = decoder.as_ref().unwrap();
                        emit_event(
                            "info",
                            "DECODER_SELECTED",
                            &format!(
                                "{} (D3D11 output={}, hardware decode={})",
                                selected.name(),
                                selected.d3d11_output_active(),
                                selected
                                    .hardware_decode()
                                    .map(|v| v.to_string())
                                    .unwrap_or_else(|| "unknown".into())
                            ),
                            Some("DECODING"),
                        );
                    }
                    waiting_for_keyframe = false;
                    let start = Instant::now();
                    let mut decoded_this_au = 0u32;
                    // Accumulated inside the decode callback so the decoder's own
                    // cost can be separated from our rotate and ring write.
                    let mut rotate_ns_this_au = 0u64;
                    let mut write_ns_this_au = 0u64;
                    let was_media_foundation = decoder.as_ref().unwrap().is_media_foundation();
                    let decode_result = match decoder.as_mut().unwrap() {
                        V2Decoder::MediaFoundation(d) => d.decode(
                            &record.payload,
                            record.encoder_timestamp_us,
                            record.is_keyframe(),
                            |frame| {
                                let sequence = record.sequence;
                                if last_published_sequence != 0
                                    && sequence > last_published_sequence + 1
                                {
                                    replaced_frames += sequence - last_published_sequence - 1;
                                }
                                let rotation = info.effective_rotation;
                                let mirror = info.mirror;
                                let rotate_start = Instant::now();
                                let oriented = if rotation != 0 || mirror {
                                    orient_nv12(
                                        frame.bytes,
                                        frame.width,
                                        frame.height,
                                        frame.y_stride,
                                        frame.uv_stride,
                                        rotation,
                                        mirror,
                                        &mut oriented_nv12,
                                    )
                                    .ok()
                                    .map(|(w, h)| (oriented_nv12.as_slice(), w, h, w, w))
                                } else {
                                    Some((
                                        frame.bytes,
                                        frame.width,
                                        frame.height,
                                        frame.y_stride,
                                        frame.uv_stride,
                                    ))
                                };
                                rotate_ns_this_au += rotate_start.elapsed().as_nanos() as u64;
                                let Some((
                                    pixels,
                                    output_width,
                                    output_height,
                                    output_y_stride,
                                    output_uv_stride,
                                )) = oriented
                                else {
                                    last_error = Some("NV12 orientation failed".to_string());
                                    return;
                                };
                                let decode_ns = monotonic_ns();
                                let write_start = Instant::now();
                                let committed = ipc.write_nv12_frame(
                                    sequence,
                                    record.capture_timestamp_ns,
                                    receive_ns,
                                    decode_ns,
                                    output_width,
                                    output_height,
                                    output_y_stride,
                                    output_uv_stride,
                                    record.flags,
                                    record.send_delta_us,
                                    pixels,
                                );
                                write_ns_this_au += write_start.elapsed().as_nanos() as u64;
                                if committed {
                                    if !first_ring_frame_emitted {
                                        emit_event(
                                            "info",
                                            "FIRST_RING_FRAME",
                                            "First decoded keyframe committed to NV12 ring",
                                            Some("WRITING_RING"),
                                        );
                                        first_ring_frame_emitted = true;
                                    }
                                    last_published_sequence = sequence;
                                    ring_frames_committed += 1;
                                    decoded_unique += 1;
                                    decoded_this_au += 1;
                                    latency_ms_sum += capture_clock.latency_ns(
                                        record.capture_timestamp_ns,
                                        receive_ns,
                                        monotonic_ns(),
                                        Instant::now(),
                                    ) / 1_000_000;
                                    latency_samples += 1;
                                }
                            },
                        ),
                        V2Decoder::Software { decoder, scratch } => {
                            // Every failure here must become an Err VALUE, not an
                            // early return: `?` would leave run_h264_v2, and the
                            // caller treats that as terminal and permanently
                            // demotes the session to MJPEG. Route them through
                            // decode_result so they hit the same
                            // consecutive_decode_errors handling as any other
                            // decode failure.
                            match decoder.decode(&record.payload) {
                                Ok(Some(yuv)) => match copy_i420_to_nv12(&yuv, scratch) {
                                    Err(e) => Err(e),
                                    Ok((w, h)) => {
                                        let rotation = info.effective_rotation;
                                        let mirror = info.mirror;
                                        let rotate_start = Instant::now();
                                        let oriented = if rotation != 0 || mirror {
                                            orient_nv12(
                                                scratch,
                                                w,
                                                h,
                                                w,
                                                w,
                                                rotation,
                                                mirror,
                                                &mut oriented_nv12,
                                            )
                                            .map(|(ow, oh)| (oriented_nv12.as_slice(), ow, oh))
                                        } else {
                                            Ok((scratch.as_slice(), w, h))
                                        };
                                        rotate_ns_this_au +=
                                            rotate_start.elapsed().as_nanos() as u64;
                                        match oriented {
                                            Err(e) => Err(e),
                                            Ok((pixels, output_width, output_height)) => {
                                                let decode_ns = monotonic_ns();
                                                let write_start = Instant::now();
                                                let committed = ipc.write_nv12_frame(
                                                    record.sequence,
                                                    record.capture_timestamp_ns,
                                                    receive_ns,
                                                    decode_ns,
                                                    output_width,
                                                    output_height,
                                                    output_width,
                                                    output_width,
                                                    record.flags,
                                                    record.send_delta_us,
                                                    pixels,
                                                );
                                                write_ns_this_au +=
                                                    write_start.elapsed().as_nanos() as u64;
                                                if committed {
                                                    if !first_ring_frame_emitted {
                                                        emit_event(
                                                            "info",
                                                            "FIRST_RING_FRAME",
                                                            "First decoded keyframe committed to NV12 ring",
                                                            Some("WRITING_RING"),
                                                        );
                                                        first_ring_frame_emitted = true;
                                                    }
                                                    last_published_sequence = record.sequence;
                                                    ring_frames_committed += 1;
                                                    decoded_unique += 1;
                                                    decoded_this_au = 1;
                                                    latency_ms_sum += capture_clock.latency_ns(
                                                        record.capture_timestamp_ns,
                                                        receive_ns,
                                                        monotonic_ns(),
                                                        Instant::now(),
                                                    ) / 1_000_000;
                                                    latency_samples += 1;
                                                }
                                                Ok(1)
                                            }
                                        }
                                    }
                                },
                                Ok(None) => Ok(0),
                                Err(e) => Err(format!("OpenH264 decode: {e}")),
                            }
                        }
                    };
                    let total_ns = start.elapsed().as_nanos() as u64;
                    let decode_only_ns =
                        total_ns.saturating_sub(rotate_ns_this_au + write_ns_this_au);
                    decode_ns_sum += decode_only_ns;
                    rotate_ns_sum += rotate_ns_this_au;
                    write_ns_sum += write_ns_this_au;
                    frame_trace.finish(&FrameStages {
                        decode_us: decode_only_ns / 1_000,
                        rotate_us: rotate_ns_this_au / 1_000,
                        write_us: write_ns_this_au / 1_000,
                        outputs: decoded_this_au,
                    });
                    match decode_result {
                        Ok(_) => {
                            consecutive_decode_errors = 0;
                            if decoded_this_au > 0 {
                                last_error = None;
                            }
                        }
                        Err(e) => {
                            consecutive_decode_errors += 1;
                            last_error = Some(e);
                            waiting_for_keyframe = true;
                            if !was_media_foundation && consecutive_decode_errors >= 3 {
                                return Err(last_error
                                    .take()
                                    .unwrap_or_else(|| "software H.264 decoder failed".into()));
                            } else if was_media_foundation && consecutive_decode_errors >= 3 {
                                force_software = true;
                                decoder = None;
                                consecutive_decode_errors = 0;
                            } else if was_media_foundation {
                                // Recreate the D3D11 device/MFT after any hardware
                                // failure; this is also the device-loss recovery path.
                                decoder = None;
                            }
                        }
                    }
                }
                ocb2::TYPE_HEARTBEAT => {}
                ocb2::TYPE_END_OF_STREAM => {
                    if phone_encoder_error {
                        return Err("phone H.264 encoder ended after an error".into());
                    }
                    last_error =
                        Some("Phone restarted the OCB2 stream; reconnecting at a keyframe".into());
                    clean_disconnect = true;
                    break 'connection;
                }
                ocb2::TYPE_ERROR => {
                    phone_encoder_error = true;
                    last_error = Some(format!(
                        "Phone encoder error: {}",
                        String::from_utf8_lossy(&record.payload)
                    ));
                }
                _ => {}
            }
            if record.flags & ocb2::FLAG_END_OF_STREAM != 0 {
                if phone_encoder_error {
                    return Err("phone marked the OCB2 stream ended after an encoder error".into());
                }
                clean_disconnect = true;
                break 'connection;
            }
            let _ = recycle_tx.try_send(record.payload);
        }
        // Dropping the receiver makes the reader's next send fail, which is how
        // it learns to exit. It is not joined: it may be parked in read() on a
        // connection the phone has already abandoned, and TCP keepalive is what
        // bounds that. A new connection gets a new reader.
        drop(event_rx);
        if clean_disconnect {
            // Not a failure, so do not accumulate one. The stream is already coming
            // back; reconnect at once and let the keyframe the phone sends on
            // subscribe close the gap.
            failures = 0;
        } else {
            failures = failures.saturating_add(1);
        }
        sleep(reconnect_delay(clean_disconnect, failures));
    }
}

fn main() {
    let mut args = Args::parse();
    if args.test_pattern.is_some() {
        args.source = "test-pattern".to_owned();
    }

    // The OPENCAMBRIDGE_TOKEN environment variable takes precedence over
    // so the launcher can hand over the token without it appearing in the
    // process command line (readable by any same-user process).
    if let Ok(t) = std::env::var("OPENCAMBRIDGE_TOKEN") {
        if !t.is_empty() {
            args.token = Some(t);
        }
    }
    // Do not leave the credential in this process environment after handoff.
    std::env::remove_var("OPENCAMBRIDGE_TOKEN");

    match args.source.as_str() {
        "mjpeg" | "test-pattern" | "h264" => {}
        other => {
            eprintln!(
                "Unknown --source '{}'. Expected mjpeg, test-pattern, or h264.",
                other
            );
            std::process::exit(2);
        }
    }

    let defaults = match args.profile.as_str() {
        "low-latency" => (960, 540, 30),
        "balanced" => (1280, 720, 30),
        "balanced-720p60" => (1280, 720, 60),
        "quality" => (1920, 1080, 30),
        "experimental-1080p60" => (1920, 1080, 60),
        _ => (1280, 720, 30),
    };

    let width = args.width.unwrap_or(defaults.0);
    let height = args.height.unwrap_or(defaults.1);
    let mut fps = args.source_fps.or(args.fps).unwrap_or(defaults.2).max(1);

    // Every V2 slot is sized for one 1920x1080 NV12 frame.
    let needed = (width as u64) * (height as u64) * 3 / 2;
    if width == 0
        || height == 0
        || width > 1920
        || height > 1080
        || width % 2 != 0
        || height % 2 != 0
        || needed > MAX_NV12_SIZE as u64
    {
        eprintln!(
            "Requested output {}x{} needs {} bytes but the shared framebuffer holds {} bytes (max 1920x1080).",
            width, height, needed, MAX_NV12_SIZE
        );
        std::process::exit(2);
    }

    let ipc = SharedMemoryIpc::new().expect("Failed to initialize IPC");
    ipc.initialize_ring_if_needed();
    ipc.set_source_fps(fps, 1);
    emit_event(
        "info",
        "PRODUCER_BUILD",
        &format!(
            "commit={} sha256={} abi=0x{:016x}",
            env!("OCB_SOURCE_COMMIT"),
            ipc.producer_hash_hex,
            RING_ABI_HASH
        ),
        Some("STARTING"),
    );
    emit_event(
        "info",
        "FRAMEBUFFER_BACKEND",
        &ipc.backend_name,
        Some("STARTING"),
    );
    let mut active_fallback_reason = if args.source == "mjpeg" {
        "MJPEG compatibility mode selected".to_string()
    } else {
        String::new()
    };

    // SIMD resize is on by default (with an automatic fallback to the standard
    // resize on any error). Set OCB_DISABLE_SIMD_RESIZE=1 to force the standard
    // path — the kill switch if a SIMD build ever produces bad output.
    let allow_simd = std::env::var("OCB_DISABLE_SIMD_RESIZE").is_err();
    emit_event(
        "info",
        "RESIZE_BACKEND",
        if allow_simd {
            "simd (fallback: standard)"
        } else {
            "standard (simd disabled)"
        },
        Some("STARTING"),
    );
    // Faster pure-Rust JPEG decode (fallback: image crate). Disable with
    // OCB_DISABLE_FASTJPEG if it ever misdecodes.
    let allow_fastjpeg = std::env::var("OCB_DISABLE_FASTJPEG").is_err();
    emit_event(
        "info",
        "JPEG_DECODER",
        if allow_fastjpeg {
            "zune (fallback: image)"
        } else {
            "image (fastjpeg disabled)"
        },
        Some("STARTING"),
    );

    if args.source == "h264" {
        if let Err(e) = run_h264_v2(&args, &ipc) {
            eprintln!(
                "OCB2 H.264 pipeline unavailable ({e}); switching to MJPEG compatibility mode"
            );
            active_fallback_reason = format!("H.264 pipeline failed: {e}");
            let fallback = match request_phone_mjpeg_fallback(&args) {
                Ok(selection) => selection,
                Err(fallback_error) => {
                    eprintln!("{fallback_error}");
                    std::process::exit(1);
                }
            };
            args.source_width = Some(fallback.width);
            args.source_height = Some(fallback.height);
            args.source_fps = Some(fallback.fps);
            fps = fallback.fps.max(1);
            ipc.set_source_fps(fps, 1);
            active_fallback_reason = format!(
                "{}; phone selected MJPEG {}x{}@{}",
                active_fallback_reason, fallback.width, fallback.height, fallback.fps
            );
            sleep(Duration::from_millis(500));
            args.source = "mjpeg".to_string();
            args.url = args
                .url
                .take()
                .map(|url| url.replace("/stream.ocb2", "/stream.mjpeg"));
        } else {
            return;
        }
    }

    let mut frame_counter = 0u64;
    let target_duration = Duration::from_secs_f64(1.0 / fps as f64);
    // 10% tolerance so normal jitter does not halve the effective rate.
    let pace_threshold = target_duration.mul_f64(0.9);
    let mut last_write = Instant::now() - target_duration;
    let mut next_frame_deadline = Instant::now();
    let mut last_print = Instant::now();
    let mut output_fps_counter = 0;
    let (mut last_vcam_unique, mut last_vcam_repeated) = ipc.virtual_camera_counters();
    let mut consumer_readiness = ConsumerReadiness::new();

    // MJPEG source state
    let latest_jpeg = Arc::new(Mutex::new(None));
    let http_jpeg_counter = Arc::new(Mutex::new(0));
    let dropped_jpeg_counter = Arc::new(Mutex::new(0));
    let mjpeg_bytes_counter = Arc::new(Mutex::new(0u64));
    // Reader -> writer wake: the reader signals the moment a new JPEG lands so
    // a frame never sits waiting for the next pacing tick (capacity 1: a
    // pending wake covers any number of newer frames). The main thread keeps
    // its own sender alive, so recv_timeout never sees Disconnected.
    let (wake_tx, wake_rx) = sync_channel::<()>(1);

    let last_error = Arc::new(Mutex::new(None::<String>));

    if args.source == "mjpeg" {
        let url = args.url.clone().expect("URL is required for mjpeg source");
        start_mjpeg_reader(
            url,
            args.token.clone(),
            latest_jpeg.clone(),
            http_jpeg_counter.clone(),
            dropped_jpeg_counter.clone(),
            mjpeg_bytes_counter.clone(),
            wake_tx.clone(),
            last_error.clone(),
        );
    }

    let mut sum_decode_ms = 0;
    let mut sum_rotate_ms = 0;
    let mut sum_resize_ms = 0;
    let mut sum_write_ms = 0;
    let mut sum_total_ms = 0;
    let mut decoded_fps_counter = 0;
    let mut source_w = args.source_width.unwrap_or(0);
    let mut source_h = args.source_height.unwrap_or(0);
    let mut last_backend: &str = "skipped";
    let mut last_rotation: u32 = 0;
    let mut last_decode_backend: &str = "standard";
    let test_pattern = args.test_pattern.unwrap_or(TestPatternKind::Nv12Bars);
    let mut test_nv12 = Vec::with_capacity(width as usize * height as usize * 3 / 2);

    if args.source == "test-pattern" {
        emit_event(
            "info",
            "TEST_PATTERN_SELECTED",
            test_pattern.as_str(),
            Some("WRITING_RING"),
        );
    }

    loop {
        let next_print = last_print + Duration::from_secs(1);

        if args.source == "mjpeg" {
            // Event-driven wait: woken by the reader the moment a new JPEG
            // arrives, by the pacing deadline of an already pending frame, or
            // by the metrics tick. Including the pacing deadline fixes the
            // last-frame stall: a frame arriving just before its deadline is
            // written at that deadline even if no later frame arrives.
            let pending = latest_jpeg.lock().unwrap().is_some();
            let pacing_deadline = last_write + pace_threshold;
            let wake_at = if pending {
                next_print.min(pacing_deadline)
            } else {
                next_print
            };
            let timeout = wake_at.saturating_duration_since(Instant::now());
            let _ = wake_rx.recv_timeout(timeout);
        } else {
            // test-pattern: pure time-based pacing (no reader wakes arrive).
            let wake_at = next_frame_deadline.min(next_print);
            let now = Instant::now();
            if wake_at > now {
                sleep(wake_at - now);
            }
        }

        let start_time = Instant::now();
        let mut loop_total_ms = 0;

        if args.source == "test-pattern" {
            if Instant::now() >= next_frame_deadline {
                generate_nv12_pattern(test_pattern, frame_counter, width, height, &mut test_nv12);
                let write_start = Instant::now();
                let now_ns = monotonic_ns();
                let _ = ipc.write_nv12_frame(
                    frame_counter + 1,
                    now_ns,
                    now_ns,
                    now_ns,
                    width,
                    height,
                    width,
                    width,
                    0,
                    // Locally generated, so there is no sender cadence to report.
                    0,
                    &test_nv12,
                );
                sum_write_ms += write_start.elapsed().as_millis() as u32;
                frame_counter += 1;
                output_fps_counter += 1;
                next_frame_deadline += target_duration;
                // If we are too far behind, reset the clock instead of
                // accumulating lag.
                let now = Instant::now();
                if now > next_frame_deadline
                    && now.duration_since(next_frame_deadline) > target_duration
                {
                    next_frame_deadline = now;
                }
                loop_total_ms = start_time.elapsed().as_millis() as u32;
            }
        } else if args.source == "mjpeg" && last_write.elapsed() >= pace_threshold {
            // WRITE PACING: the pace check above only rate-limits how often we
            // look for and write a NEW frame; it never manufactures output.
            // When we are ahead of schedule the frame stays in the slot
            // (possibly replaced by a newer one) and is picked up by a later
            // wake or the metrics tick.
            //
            // HONEST FPS: this is latest-only. We `take()` the newest decoded
            // JPEG and leave None behind. If no new JPEG has arrived since the
            // last tick, `jpeg_opt` is None and we DO NOT write anything this
            // iteration -- the previously written shared-memory frame simply
            // stays in place (the MF virtual camera re-serves it), but we never
            // re-copy or re-count it. Consequently written_fps / output_fps
            // counts only DISTINCT decoded frames actually written, so it
            // reflects real throughput and is never inflated by duplicating a
            // frame to hit the target FPS.
            let jpeg_opt = {
                let mut lock = latest_jpeg.lock().unwrap();
                lock.take() // Takes the newest JPEG, leaving None (latest-only)
            };

            if let Some(jpeg_data) = jpeg_opt {
                let decode_start = Instant::now();
                // Decode to an RgbaImage whose bytes are BGRA (framebuffer
                // format). Prefer the faster pure-Rust zune-jpeg decoder; fall
                // back to the image crate on any failure (or when disabled).
                let mut decode_backend = "zune";
                let mut rgba_opt: Option<image::RgbaImage> = if allow_fastjpeg {
                    fast_jpeg_decode_bgra(&jpeg_data)
                } else {
                    None
                };
                if rgba_opt.is_none() {
                    decode_backend = "standard";
                    match image::load_from_memory(&jpeg_data) {
                        Ok(img) => {
                            let mut rgba = img.to_rgba8();
                            // RGBA -> BGRA in place (framebuffer format is BGRA32).
                            for pixel in rgba.pixels_mut() {
                                let r = pixel[0];
                                pixel[0] = pixel[2];
                                pixel[2] = r;
                            }
                            rgba_opt = Some(rgba);
                        }
                        Err(e) => set_error(&last_error, format!("JPEG decode failed: {}", e)),
                    }
                }

                if let Some(rgba) = rgba_opt {
                    source_w = rgba.width();
                    source_h = rgba.height();
                    sum_decode_ms += decode_start.elapsed().as_millis() as u32;
                    last_decode_backend = decode_backend;

                    let timings = resize_authoritative_mjpeg_write(
                        rgba,
                        width,
                        height,
                        &ipc,
                        frame_counter,
                        allow_simd,
                    );
                    sum_rotate_ms += timings.rotate_ms;
                    sum_resize_ms += timings.resize_ms;
                    sum_write_ms += timings.write_ms;
                    last_backend = timings.resize_backend;
                    last_rotation = timings.rotation;

                    frame_counter += 1;
                    output_fps_counter += 1;
                    decoded_fps_counter += 1;
                    last_write = Instant::now();
                }
                loop_total_ms = start_time.elapsed().as_millis() as u32;
            }
        }
        sum_total_ms += loop_total_ms;

        if last_print.elapsed().as_secs() >= 1 {
            let interval_secs = last_print.elapsed().as_secs_f64().max(0.001);
            let mut http_fps = ((output_fps_counter as f64) / interval_secs).round() as u32;
            let mut dropped_jpegs = 0;
            let mut queue_len = 0;
            let mut source_bytes = 0u64;

            if args.source == "mjpeg" {
                let mut lock = http_jpeg_counter.lock().unwrap();
                http_fps = ((*lock as f64) / interval_secs).round() as u32;
                *lock = 0;

                let mut drop_lock = dropped_jpeg_counter.lock().unwrap();
                dropped_jpegs = *drop_lock;
                *drop_lock = 0;

                let jpeg_lock = latest_jpeg.lock().unwrap();
                if jpeg_lock.is_some() {
                    queue_len = 1;
                }

                let mut bytes_lock = mjpeg_bytes_counter.lock().unwrap();
                source_bytes = *bytes_lock;
                *bytes_lock = 0;
            }

            let denom = if output_fps_counter > 0 {
                output_fps_counter
            } else {
                1
            };
            let avg_decode = sum_decode_ms / denom;
            let avg_rotate = sum_rotate_ms / denom;
            let avg_resize = sum_resize_ms / denom;
            let avg_write = sum_write_ms / denom;
            let avg_total = sum_total_ms / denom;

            let mbps = (source_bytes as f64 * 8.0) / 1_000_000.0;
            let decoded_fps_normalized =
                ((decoded_fps_counter as f64) / interval_secs).round() as u32;
            let written_fps_normalized =
                ((output_fps_counter as f64) / interval_secs).round() as u32;
            let (consumer_width, consumer_height, _) = ipc.consumer_format();
            let (vcam_unique_total, vcam_repeated_total) = ipc.virtual_camera_counters();
            let vcam_unique_fps = ((vcam_unique_total.saturating_sub(last_vcam_unique)) as f64
                / interval_secs)
                .round() as u32;
            let repeated_samples = vcam_repeated_total.saturating_sub(last_vcam_repeated);
            last_vcam_unique = vcam_unique_total;
            last_vcam_repeated = vcam_repeated_total;

            let err_snapshot = { last_error.lock().unwrap().clone() };
            let last_error_json = match &err_snapshot {
                Some(e) => format!("\"{}\"", json_escape(e)),
                None => "null".to_string(),
            };
            let ring = ipc.diagnostics();
            let ring_json = serde_json::to_string(&ring).unwrap_or_else(|_| "{}".into());
            let readiness = consumer_readiness.observe(&ring, Instant::now());
            let readiness_json = serde_json::to_string(&readiness).unwrap_or_else(|_| "{}".into());
            let virtual_camera_ready = readiness.ready;

            println!(
                r#"{{"type":"metrics","producer_state":"WRITING_RING","ring_frames_committed":{},"source":"{}","profile":"{}","source_width":{},"source_height":{},"output_width":{},"output_height":{},"fps_target":{},"http_jpeg_fps":{},"decoded_fps":{},"written_fps":{},"transport_fps":{},"decoded_unique_fps":{},"virtual_camera_unique_fps":{},"repeated_samples":{},"dropped_jpegs":{},"replaced_frames":{},"jpeg_queue_len":{},"decode_ms_avg":{},"rotate_ms_avg":{},"resize_ms_avg":{},"write_ms_avg":{},"total_pipeline_ms":{},"latency_ms":{},"bytes_per_sec":{},"estimated_mbps":"{:.2}","pixel_format":"NV12","decode_backend":"{}","decoder_name":"JPEG software compatibility","d3d11_output":false,"hardware_decoder":false,"encoder_name":"Android JPEG","hardware_encoder":false,"camera_id":"","fallback_reason":"{}","resize_backend":"{}","rotation":{},"last_error":{},"ring":{},"virtual_camera_readiness":{},"virtual_camera_ready":{}}}"#,
                frame_counter,
                args.source,
                json_escape(&args.profile),
                source_w,
                source_h,
                consumer_width,
                consumer_height,
                fps,
                http_fps,
                decoded_fps_normalized,
                written_fps_normalized,
                http_fps,
                decoded_fps_normalized,
                vcam_unique_fps,
                repeated_samples,
                dropped_jpegs,
                dropped_jpegs,
                queue_len,
                avg_decode,
                avg_rotate,
                avg_resize,
                avg_write,
                avg_total,
                avg_total,
                source_bytes,
                mbps,
                last_decode_backend,
                json_escape(&active_fallback_reason),
                last_backend,
                last_rotation,
                last_error_json,
                ring_json,
                readiness_json,
                virtual_camera_ready
            );

            last_print = Instant::now();
            output_fps_counter = 0;
            decoded_fps_counter = 0;
            sum_decode_ms = 0;
            sum_rotate_ms = 0;
            sum_resize_ms = 0;
            sum_write_ms = 0;
            sum_total_ms = 0;
        }
    }
}
