//! Output-only framing. The full upright ring and desktop preview are untouched.
//! ABI v1 matches VirtualCameraMediaSource/OutputFraming.h (40 aligned bytes).
use serde::Deserialize;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::FromRawHandle;
use std::path::Path;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::Mutex;
use windows::core::{w, PCWSTR};
use windows::Win32::Foundation::{CloseHandle, GENERIC_ALL, GENERIC_READ, GENERIC_WRITE, HANDLE};
use windows::Win32::Security::{
    AddAccessAllowedAce, CreateWellKnownSid, InitializeAcl, InitializeSecurityDescriptor,
    SetKernelObjectSecurity, SetSecurityDescriptorDacl, WinCreatorOwnerRightsSid,
    WinLocalServiceSid, WinLocalSystemSid, ACL, ACL_REVISION, DACL_SECURITY_INFORMATION,
    PROTECTED_DACL_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, PSID, SECURITY_ATTRIBUTES,
    SECURITY_DESCRIPTOR,
};
use windows::Win32::Storage::FileSystem::{
    CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_ALWAYS, WRITE_DAC,
};
use windows::Win32::System::Memory::{
    CreateFileMappingW, MapViewOfFile, UnmapViewOfFile, FILE_MAP_ALL_ACCESS,
    MEMORY_MAPPED_VIEW_ADDRESS, PAGE_READWRITE,
};

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum FramingMode {
    Fit = 0,
    Fill = 1,
    Custom = 2,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NormalizedCrop {
    x: f64,
    y: f64,
    width: f64,
    height: f64,
}

impl Default for NormalizedCrop {
    fn default() -> Self {
        Self {
            x: 0.0,
            y: 0.0,
            width: 1.0,
            height: 1.0,
        }
    }
}

impl NormalizedCrop {
    fn validate(self) -> Result<(), String> {
        if [self.x, self.y, self.width, self.height]
            .iter()
            .any(|v| !v.is_finite())
            || self.x < 0.0
            || self.y < 0.0
            || self.x > 1.0
            || self.y > 1.0
            || self.width <= 0.0
            || self.height <= 0.0
            || self.width > 1.0
            || self.height > 1.0
            || self.x + self.width > 1.0 + 1e-9
            || self.y + self.height > 1.0 + 1e-9
        {
            return Err(
                "Crop must be a finite, nonempty rectangle inside normalized source bounds".into(),
            );
        }
        Ok(())
    }
}

#[repr(C)]
struct FramingWire {
    magic: AtomicU32,
    version: AtomicU32,
    size: AtomicU32,
    sequence: AtomicU32,
    mode: AtomicU32,
    x: AtomicU32,
    y: AtomicU32,
    width: AtomicU32,
    height: AtomicU32,
    reserved: AtomicU32,
}
const WIRE_SIZE: usize = 40;
const _: () = assert!(std::mem::size_of::<FramingWire>() == WIRE_SIZE);
const _: () = assert!(std::mem::offset_of!(FramingWire, sequence) == 12);

struct FramingMapping {
    handle: HANDLE,
    view: MEMORY_MAPPED_VIEW_ADDRESS,
}
// State's mutex serializes writers, and the view remains alive until Drop.
unsafe impl Send for FramingMapping {}

impl FramingMapping {
    fn create(path: &Path, name: Option<PCWSTR>) -> Result<Self, String> {
        unsafe {
            // Owner/SYSTEM may write; FrameServer's LOCAL SERVICE may only read.
            // OWNER RIGHTS avoids a subprocess or another dependency to get a SID.
            let mut acl_storage = [0u32; 64];
            let acl = acl_storage.as_mut_ptr() as *mut ACL;
            InitializeAcl(
                acl,
                std::mem::size_of_val(&acl_storage) as u32,
                ACL_REVISION,
            )
            .map_err(|e| e.to_string())?;
            for (sid_type, access) in [
                (WinCreatorOwnerRightsSid, GENERIC_ALL.0),
                (WinLocalSystemSid, GENERIC_ALL.0),
                (WinLocalServiceSid, GENERIC_READ.0),
            ] {
                let mut sid_storage = [0u32; 17];
                let mut sid_size = std::mem::size_of_val(&sid_storage) as u32;
                let sid = PSID(sid_storage.as_mut_ptr().cast());
                CreateWellKnownSid(sid_type, None, Some(sid), &mut sid_size)
                    .map_err(|e| e.to_string())?;
                AddAccessAllowedAce(acl, ACL_REVISION, access, sid).map_err(|e| e.to_string())?;
            }
            let mut descriptor = SECURITY_DESCRIPTOR::default();
            let sd = PSECURITY_DESCRIPTOR((&mut descriptor as *mut SECURITY_DESCRIPTOR).cast());
            InitializeSecurityDescriptor(sd, 1).map_err(|e| e.to_string())?;
            SetSecurityDescriptorDacl(sd, true, Some(acl), false).map_err(|e| e.to_string())?;
            let attributes = SECURITY_ATTRIBUTES {
                nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
                lpSecurityDescriptor: sd.0,
                bInheritHandle: false.into(),
            };
            let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            let file_handle = CreateFileW(
                PCWSTR(wide.as_ptr()),
                GENERIC_READ.0 | GENERIC_WRITE.0 | WRITE_DAC.0,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                Some(&attributes),
                OPEN_ALWAYS,
                FILE_ATTRIBUTE_NORMAL,
                None,
            )
            .map_err(|e| format!("Cannot open output framing settings: {e}"))?;
            let file = std::fs::File::from_raw_handle(file_handle.0);
            SetKernelObjectSecurity(
                file_handle,
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                sd,
            )
            .map_err(|e| format!("Cannot secure output framing settings: {e}"))?;
            // Never truncate a mapping held by a native consumer across app restarts.
            if file.metadata().map_err(|e| e.to_string())?.len() < WIRE_SIZE as u64 {
                file.set_len(WIRE_SIZE as u64).map_err(|e| e.to_string())?;
            }
            let handle = CreateFileMappingW(
                file_handle,
                Some(&attributes),
                PAGE_READWRITE,
                0,
                WIRE_SIZE as u32,
                name.unwrap_or(PCWSTR::null()),
            )
            .map_err(|e| e.to_string())?;
            let view = MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, WIRE_SIZE);
            if view.Value.is_null() {
                let _ = CloseHandle(handle);
                return Err("Cannot map output framing settings".into());
            }
            let mapping = Self { handle, view };
            mapping.publish(FramingMode::Fit, NormalizedCrop::default());
            Ok(mapping)
        }
    }

    fn publish(&self, mode: FramingMode, crop: NormalizedCrop) {
        let wire = unsafe { &*(self.view.Value as *const FramingWire) };
        // Recover an odd generation left by an interrupted previous process.
        let start = wire.sequence.load(Ordering::SeqCst).wrapping_add(1) | 1;
        wire.sequence.store(start, Ordering::SeqCst);
        wire.magic.store(0x4642_434f, Ordering::Relaxed);
        wire.version.store(1, Ordering::Relaxed);
        wire.size.store(WIRE_SIZE as u32, Ordering::Relaxed);
        wire.mode.store(mode as u32, Ordering::Relaxed);
        wire.x.store((crop.x as f32).to_bits(), Ordering::Relaxed);
        wire.y.store((crop.y as f32).to_bits(), Ordering::Relaxed);
        wire.width
            .store((crop.width as f32).to_bits(), Ordering::Relaxed);
        wire.height
            .store((crop.height as f32).to_bits(), Ordering::Relaxed);
        wire.reserved.store(0, Ordering::Relaxed);
        wire.sequence.store(start.wrapping_add(1), Ordering::SeqCst);
    }
}

impl Drop for FramingMapping {
    fn drop(&mut self) {
        self.publish(FramingMode::Fit, NormalizedCrop::default());
        unsafe {
            let _ = UnmapViewOfFile(self.view);
            let _ = CloseHandle(self.handle);
        }
    }
}

#[derive(Default)]
pub struct OutputFramingState(Mutex<Option<FramingMapping>>);

#[tauri::command]
pub fn set_output_framing(
    state: tauri::State<'_, OutputFramingState>,
    mode: FramingMode,
    crop: NormalizedCrop,
) -> Result<(), String> {
    crop.validate()?;
    let mut mapping = state
        .0
        .lock()
        .map_err(|_| "Output framing settings lock unavailable".to_string())?;
    if mapping.is_none() {
        std::fs::create_dir_all("C:\\ProgramData\\OpenCamBridge").map_err(|e| e.to_string())?;
        *mapping = Some(FramingMapping::create(
            Path::new("C:\\ProgramData\\OpenCamBridge\\output-framing-v1.bin"),
            Some(w!("Local\\OpenCamBridgeOutputFramingV1")),
        )?);
    }
    mapping.as_ref().unwrap().publish(mode, crop);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows::Win32::System::Memory::{FILE_MAP_READ, PAGE_READONLY};

    #[test]
    fn crop_validation_rejects_nonfinite_and_out_of_bounds() {
        let good = NormalizedCrop {
            x: 0.25,
            y: 0.1,
            width: 0.5,
            height: 0.9,
        };
        assert!(good.validate().is_ok());
        for bad in [
            NormalizedCrop {
                x: f64::NAN,
                ..good
            },
            NormalizedCrop {
                width: f64::INFINITY,
                ..good
            },
            NormalizedCrop { width: 0.0, ..good },
            NormalizedCrop { x: -0.1, ..good },
            NormalizedCrop { width: 0.9, ..good },
            NormalizedCrop {
                height: 1.0,
                ..good
            },
        ] {
            assert!(bad.validate().is_err());
        }
    }

    #[test]
    fn mapping_publishes_live_crop_to_a_second_readonly_view() {
        let path =
            std::env::temp_dir().join(format!("ocb-framing-test-{}.bin", std::process::id()));
        let mapping = FramingMapping::create(&path, None).unwrap();
        let reader = std::fs::File::open(&path).unwrap();
        use std::os::windows::io::AsRawHandle;
        unsafe {
            let handle = CreateFileMappingW(
                HANDLE(reader.as_raw_handle()),
                None,
                PAGE_READONLY,
                0,
                0,
                None,
            )
            .unwrap();
            let view = MapViewOfFile(handle, FILE_MAP_READ, 0, 0, WIRE_SIZE);
            assert!(!view.Value.is_null());
            let wire = &*(view.Value as *const FramingWire);
            mapping.publish(
                FramingMode::Custom,
                NormalizedCrop {
                    x: 0.25,
                    y: 0.5,
                    width: 0.5,
                    height: 0.5,
                },
            );
            assert_eq!(wire.sequence.load(Ordering::SeqCst) % 2, 0);
            assert_eq!(wire.mode.load(Ordering::Relaxed), 2);
            assert_eq!(f32::from_bits(wire.x.load(Ordering::Relaxed)), 0.25);
            assert_eq!(f32::from_bits(wire.height.load(Ordering::Relaxed)), 0.5);
            mapping.publish(FramingMode::Fit, NormalizedCrop::default());
            assert_eq!(wire.mode.load(Ordering::Relaxed), 0);
            assert_eq!(wire.width.load(Ordering::Relaxed), 1.0f32.to_bits());
            UnmapViewOfFile(view).unwrap();
            CloseHandle(handle).unwrap();
        }
        drop(reader);
        drop(mapping);
        std::fs::remove_file(path).unwrap();
    }
}
