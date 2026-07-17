use std::mem::ManuallyDrop;
use std::ptr::null_mut;
use windows::core::{ComInterface, Interface};
use windows::Win32::Foundation::HMODULE;
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL_11_0};
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
    D3D11_CREATE_DEVICE_VIDEO_SUPPORT, D3D11_SDK_VERSION,
};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
};

use crate::ocb2::MAX_PAYLOAD;

fn validate_access_unit_size(size: usize) -> Result<(), String> {
    if size > MAX_PAYLOAD {
        return Err(format!(
            "H.264 access unit exceeds OCB2 MAX_PAYLOAD: {size} > {MAX_PAYLOAD}"
        ));
    }
    Ok(())
}

pub struct DecodedNv12<'a> {
    pub bytes: &'a [u8],
    pub width: u32,
    pub height: u32,
    pub y_stride: u32,
    pub uv_stride: u32,
    pub _timestamp_100ns: i64,
}

pub struct MfH264Decoder {
    transform: IMFTransform,
    width: u32,
    height: u32,
    fps: u32,
    input_sample: IMFSample,
    input_buffer: IMFMediaBuffer,
    input_capacity: usize,
    output_sample: Option<IMFSample>,
    output_provides_samples: bool,
    scratch: Vec<u8>,
    _device: ID3D11Device,
    _context: ID3D11DeviceContext,
    _device_manager: IMFDXGIDeviceManager,
    pub name: String,
    /** The MFT accepted the DXGI device manager and is producing through the
     * D3D11-aware path. This does not prove that DXVA acceleration is active. */
    pub d3d11_output_active: bool,
    // Keep this last: Rust drops fields in declaration order, so Media
    // Foundation and COM remain alive until every object above is released.
    _runtime: MfRuntimeGuard,
}

struct MfRuntimeGuard {
    com_initialized: bool,
    mf_started: bool,
}

impl Drop for MfRuntimeGuard {
    fn drop(&mut self) {
        unsafe {
            if self.mf_started {
                let _ = MFShutdown();
            }
            if self.com_initialized {
                CoUninitialize();
            }
        }
    }
}

impl MfH264Decoder {
    pub fn new(width: u32, height: u32, fps: u32, codec_config: &[u8]) -> Result<Self, String> {
        unsafe {
            let com_initialized = CoInitializeEx(None, COINIT_MULTITHREADED).is_ok();
            let mut runtime = MfRuntimeGuard {
                com_initialized,
                mf_started: false,
            };
            MFStartup(MF_VERSION, MFSTARTUP_FULL).map_err(err("MFStartup"))?;
            runtime.mf_started = true;

            let mut device = None;
            let mut context = None;
            D3D11CreateDevice(
                None,
                D3D_DRIVER_TYPE_HARDWARE,
                HMODULE(0),
                D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                Some(&[D3D_FEATURE_LEVEL_11_0]),
                D3D11_SDK_VERSION,
                Some(&mut device),
                None,
                Some(&mut context),
            )
            .map_err(err("D3D11CreateDevice"))?;
            let device = device.ok_or("D3D11 device was not returned")?;
            let context = context.ok_or("D3D11 context was not returned")?;

            let mut reset_token = 0u32;
            let mut device_manager = None;
            MFCreateDXGIDeviceManager(&mut reset_token, &mut device_manager)
                .map_err(err("MFCreateDXGIDeviceManager"))?;
            let device_manager = device_manager.ok_or("DXGI device manager was not returned")?;
            device_manager
                .ResetDevice(&device, reset_token)
                .map_err(err("IMFDXGIDeviceManager::ResetDevice"))?;

            // The inbox Microsoft decoder uses DXVA/D3D11 when supplied a
            // device manager, and remains the software fallback on systems
            // where the current GPU cannot decode the selected H.264 profile.
            let transform: IMFTransform =
                CoCreateInstance(&CMSH264DecoderMFT, None, CLSCTX_INPROC_SERVER)
                    .map_err(err("Microsoft H.264 decoder activation"))?;
            let attrs = transform
                .GetAttributes()
                .map_err(err("decoder attributes"))?;
            let _ = attrs.SetUINT32(&MF_LOW_LATENCY, 1);
            let d3d_aware = attrs.GetUINT32(&MF_SA_D3D11_AWARE).unwrap_or(0) != 0;
            let d3d_set = if d3d_aware {
                transform
                    .ProcessMessage(
                        MFT_MESSAGE_SET_D3D_MANAGER,
                        Interface::as_raw(&device_manager) as usize,
                    )
                    .is_ok()
            } else {
                false
            };

            let input_type = MFCreateMediaType().map_err(err("input media type"))?;
            input_type
                .SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)
                .map_err(err("input major type"))?;
            input_type
                .SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)
                .map_err(err("input subtype"))?;
            input_type
                .SetUINT64(&MF_MT_FRAME_SIZE, packed_pair(width, height))
                .map_err(err("input frame size"))?;
            input_type
                .SetUINT64(&MF_MT_FRAME_RATE, packed_pair(fps.max(1), 1))
                .map_err(err("input frame rate"))?;
            input_type
                .SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)
                .map_err(err("input interlace"))?;
            input_type.SetUINT32(&MF_NALU_LENGTH_SET, 0).ok(); // Annex B
            if !codec_config.is_empty() {
                input_type
                    .SetBlob(&MF_MT_MPEG_SEQUENCE_HEADER, codec_config)
                    .map_err(err("H.264 codec configuration"))?;
            }
            transform
                .SetInputType(0, &input_type, 0)
                .map_err(err("decoder SetInputType"))?;

            set_nv12_output_type(&transform, width, height, fps)?;
            let output_info = transform
                .GetOutputStreamInfo(0)
                .map_err(err("decoder output stream info"))?;
            // With a DXGI device manager the Microsoft decoder commonly sets
            // CAN_PROVIDE_SAMPLES (rather than PROVIDES_SAMPLES) and returns a
            // DXGI-backed sample when pSample is null. Supplying a normal
            // MFCreateMemoryBuffer in that mode lets the MFT replace the COM
            // pointer in-place, which makes ownership ambiguous and caused a
            // repeatable IUnknown::Release access violation. Let the decoder
            // allocate for either advertised allocation mode.
            let output_provides_samples = output_info.dwFlags
                & ((MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0)
                    as u32)
                != 0;
            let output_sample = if output_provides_samples {
                None
            } else {
                Some(make_output_sample(
                    output_info.cbSize.max(width * height * 3 / 2),
                )?)
            };

            transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)
                .map_err(err("decoder begin streaming"))?;
            transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)
                .map_err(err("decoder start stream"))?;
            let input_capacity = (width as usize * height as usize).max(512 * 1024);
            let (input_sample, input_buffer) = make_input_sample(input_capacity as u32)?;

            Ok(Self {
                transform,
                width,
                height,
                fps: fps.max(1),
                input_sample,
                input_buffer,
                input_capacity,
                output_sample,
                output_provides_samples,
                scratch: vec![0; width as usize * height as usize * 3 / 2],
                _device: device,
                _context: context,
                _device_manager: device_manager,
                name: "Microsoft H.264 Video Decoder MFT".to_string(),
                d3d11_output_active: d3d_set,
                _runtime: runtime,
            })
        }
    }

    pub fn flush(&mut self) -> Result<(), String> {
        unsafe {
            self.transform
                .ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0)
                .map_err(err("decoder flush"))?;
            self.transform
                .ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)
                .map_err(err("decoder restart"))?;
        }
        Ok(())
    }

    pub fn decode<F>(
        &mut self,
        access_unit: &[u8],
        pts_us: i64,
        keyframe: bool,
        mut on_frame: F,
    ) -> Result<u32, String>
    where
        F: FnMut(DecodedNv12<'_>),
    {
        // The decoder is a trust boundary of its own. Do not rely on every
        // caller having passed the access unit through the OCB2 parser first.
        validate_access_unit_size(access_unit.len())?;
        unsafe {
            if access_unit.len() > self.input_capacity {
                let capacity = access_unit.len().next_power_of_two().min(MAX_PAYLOAD);
                let (sample, buffer) = make_input_sample(capacity as u32)?;
                self.input_sample = sample;
                self.input_buffer = buffer;
                self.input_capacity = capacity;
            }
            let mut ptr = null_mut();
            self.input_buffer
                .Lock(&mut ptr, None, None)
                .map_err(err("input buffer lock"))?;
            std::ptr::copy_nonoverlapping(access_unit.as_ptr(), ptr, access_unit.len());
            self.input_buffer
                .Unlock()
                .map_err(err("input buffer unlock"))?;
            self.input_buffer
                .SetCurrentLength(access_unit.len() as u32)
                .map_err(err("input buffer length"))?;
            self.input_sample
                .SetSampleTime(pts_us.saturating_mul(10))
                .map_err(err("input timestamp"))?;
            self.input_sample
                .SetSampleDuration(10_000_000i64 / self.fps as i64)
                .ok();
            self.input_sample
                .SetUINT32(&MFSampleExtension_CleanPoint, if keyframe { 1 } else { 0 })
                .ok();
            self.transform
                .ProcessInput(0, &self.input_sample, 0)
                .map_err(err("decoder ProcessInput"))?;

            let mut produced = 0;
            loop {
                let supplied = if self.output_provides_samples {
                    None
                } else {
                    self.output_sample.clone()
                };
                let mut output = MFT_OUTPUT_DATA_BUFFER {
                    dwStreamID: 0,
                    pSample: ManuallyDrop::new(supplied),
                    dwStatus: 0,
                    pEvents: ManuallyDrop::new(None),
                };
                let mut status = 0;
                // Call the ABI directly and keep the HRESULT as a value. The
                // windows crate's Result conversion constructs an Error on the
                // normal NEED_MORE_INPUT path; with this MFT that wrapper could
                // release an invalid COM error-info pointer and crash in
                // IUnknown::Release before we could handle the status.
                let hr = (Interface::vtable(&self.transform).ProcessOutput)(
                    Interface::as_raw(&self.transform),
                    0,
                    1,
                    &mut output,
                    &mut status,
                );
                let returned_sample = ManuallyDrop::take(&mut output.pSample);
                let _events = ManuallyDrop::take(&mut output.pEvents);
                if hr.is_ok() {
                    let out_sample = returned_sample.ok_or("decoder returned no output sample")?;
                    let timestamp = out_sample
                        .GetSampleTime()
                        .unwrap_or(pts_us.saturating_mul(10));
                    self.copy_nv12_sample(&out_sample)?;
                    on_frame(DecodedNv12 {
                        bytes: &self.scratch,
                        width: self.width,
                        height: self.height,
                        y_stride: self.width,
                        uv_stride: self.width,
                        _timestamp_100ns: timestamp,
                    });
                    produced += 1;
                    if !self.output_provides_samples {
                        self.output_sample = Some(out_sample);
                    }
                } else if hr == MF_E_TRANSFORM_NEED_MORE_INPUT {
                    break;
                } else if hr == MF_E_TRANSFORM_STREAM_CHANGE {
                    set_nv12_output_type(&self.transform, self.width, self.height, self.fps)?;
                    continue;
                } else {
                    return Err(format!(
                        "decoder ProcessOutput HRESULT 0x{:08X}",
                        hr.0 as u32
                    ));
                }
            }
            Ok(produced)
        }
    }

    unsafe fn copy_nv12_sample(&mut self, sample: &IMFSample) -> Result<(), String> {
        let buffer = sample
            .ConvertToContiguousBuffer()
            .map_err(err("output contiguous buffer"))?;
        if let Ok(two_d) = buffer.cast::<IMF2DBuffer2>() {
            let mut scanline = null_mut();
            let mut pitch = 0i32;
            let mut start = null_mut();
            let mut length = 0u32;
            two_d
                .Lock2DSize(
                    MF2DBuffer_LockFlags_Read,
                    &mut scanline,
                    &mut pitch,
                    &mut start,
                    &mut length,
                )
                .map_err(err("NV12 surface lock"))?;
            let copy_result = (|| {
                if pitch < self.width as i32 {
                    return Err(format!("invalid NV12 surface pitch {pitch}"));
                }
                let pitch = pitch as usize;
                let width = self.width as usize;
                let height = self.height as usize;
                let required = pitch
                    .checked_mul(height + height / 2)
                    .ok_or("NV12 surface size overflow")?;
                let scan_offset = scanline.offset_from(start);
                if scan_offset < 0 || scan_offset as usize + required > length as usize {
                    return Err("NV12 surface metadata exceeds locked buffer".to_string());
                }
                for row in 0..height {
                    std::ptr::copy_nonoverlapping(
                        scanline.add(row * pitch),
                        self.scratch.as_mut_ptr().add(row * width),
                        width,
                    );
                }
                let src_uv = scanline.add(pitch * height);
                let dst_uv = self.scratch.as_mut_ptr().add(width * height);
                for row in 0..height / 2 {
                    std::ptr::copy_nonoverlapping(
                        src_uv.add(row * pitch),
                        dst_uv.add(row * width),
                        width,
                    );
                }
                Ok(())
            })();
            let _ = two_d.Unlock2D();
            return copy_result;
        }

        let mut ptr = null_mut();
        let mut current = 0u32;
        buffer
            .Lock(&mut ptr, None, Some(&mut current))
            .map_err(err("NV12 buffer lock"))?;
        let exact = self.scratch.len();
        let result = if current as usize >= exact {
            std::ptr::copy_nonoverlapping(ptr, self.scratch.as_mut_ptr(), exact);
            Ok(())
        } else {
            Err(format!("short NV12 output: {current} < {exact}"))
        };
        let _ = buffer.Unlock();
        result
    }
}

#[cfg(test)]
mod tests {
    use super::validate_access_unit_size;
    use crate::ocb2::MAX_PAYLOAD;

    #[test]
    fn access_unit_at_canonical_limit_is_accepted() {
        assert!(validate_access_unit_size(MAX_PAYLOAD).is_ok());
    }

    #[test]
    fn access_unit_over_canonical_limit_is_rejected() {
        let error = validate_access_unit_size(MAX_PAYLOAD + 1).unwrap_err();
        assert!(error.contains("exceeds OCB2 MAX_PAYLOAD"));
        assert!(error.contains(&(MAX_PAYLOAD + 1).to_string()));
    }
}

unsafe fn set_nv12_output_type(
    transform: &IMFTransform,
    _width: u32,
    _height: u32,
    _fps: u32,
) -> Result<(), String> {
    for index in 0..128u32 {
        let media_type = match transform.GetOutputAvailableType(0, index) {
            Ok(t) => t,
            Err(e) if e.code() == MF_E_NO_MORE_TYPES => break,
            Err(e) => return Err(format!("enumerating decoder output types: {e}")),
        };
        if media_type.GetGUID(&MF_MT_SUBTYPE).ok() != Some(MFVideoFormat_NV12) {
            continue;
        }
        // Once the decoder parses SPS it supplies a fully negotiated native
        // type. Rewriting its frame-size/rate/stride attributes makes the
        // inbox H.264 MFT reject that type with MF_E_INVALIDMEDIATYPE. Select
        // the native NV12 type unchanged and validate every returned surface
        // against our requested dimensions in copy_nv12_sample().
        transform
            .SetOutputType(0, &media_type, 0)
            .map_err(err("decoder NV12 SetOutputType"))?;
        return Ok(());
    }
    Err("Media Foundation decoder exposes no NV12 output type".to_string())
}

unsafe fn make_output_sample(size: u32) -> Result<IMFSample, String> {
    let sample = MFCreateSample().map_err(err("output sample"))?;
    let buffer = MFCreateMemoryBuffer(size).map_err(err("output buffer"))?;
    sample.AddBuffer(&buffer).map_err(err("output AddBuffer"))?;
    Ok(sample)
}

unsafe fn make_input_sample(size: u32) -> Result<(IMFSample, IMFMediaBuffer), String> {
    let sample = MFCreateSample().map_err(err("input sample"))?;
    let buffer = MFCreateMemoryBuffer(size).map_err(err("input buffer"))?;
    sample.AddBuffer(&buffer).map_err(err("input AddBuffer"))?;
    Ok((sample, buffer))
}

fn err(label: &'static str) -> impl FnOnce(windows::core::Error) -> String {
    move |e| format!("{label}: {e}")
}

fn packed_pair(high: u32, low: u32) -> u64 {
    ((high as u64) << 32) | low as u64
}
