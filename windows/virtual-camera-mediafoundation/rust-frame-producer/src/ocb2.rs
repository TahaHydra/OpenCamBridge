use serde::Deserialize;

pub const MAGIC: [u8; 4] = *b"OCB2";
pub const VERSION: u16 = 2;
pub const HEADER_SIZE: usize = 48;
pub const MAX_PAYLOAD: usize = 16 * 1024 * 1024;

pub const TYPE_STREAM_INFO: u16 = 1;
pub const TYPE_CODEC_CONFIG: u16 = 2;
pub const TYPE_VIDEO_ACCESS_UNIT: u16 = 3;
pub const TYPE_HEARTBEAT: u16 = 4;
pub const TYPE_END_OF_STREAM: u16 = 5;
pub const TYPE_ERROR: u16 = 6;

pub const FLAG_CODEC_CONFIG: u32 = 1 << 0;
pub const FLAG_KEYFRAME: u32 = 1 << 1;
pub const FLAG_DISCONTINUITY: u32 = 1 << 2;
pub const FLAG_END_OF_STREAM: u32 = 1 << 3;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Record {
    pub record_type: u16,
    pub flags: u32,
    pub sequence: u64,
    pub capture_timestamp_ns: u64,
    pub encoder_timestamp_us: i64,
    pub payload: Vec<u8>,
}

impl Record {
    pub fn is_keyframe(&self) -> bool {
        self.flags & FLAG_KEYFRAME != 0
    }
    pub fn is_discontinuity(&self) -> bool {
        self.flags & FLAG_DISCONTINUITY != 0
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StreamInfo {
    pub codec: String,
    pub framing: String,
    pub width: u32,
    pub height: u32,
    pub fps_numerator: u32,
    pub fps_denominator: u32,
    pub bitrate: u32,
    pub camera_id: String,
    pub encoder_name: String,
    pub hardware_encoder: bool,
    pub pixel_format: String,
    #[serde(default)]
    pub effective_rotation: u32,
    #[serde(default)]
    pub mirror: bool,
    #[serde(default)]
    pub sensor_orientation: u32,
    #[serde(default)]
    pub device_rotation: u32,
}

impl StreamInfo {
    pub fn has_valid_transform(&self) -> bool {
        [
            self.effective_rotation,
            self.sensor_orientation,
            self.device_rotation,
        ]
        .iter()
        .all(|value| matches!(value, 0 | 90 | 180 | 270))
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum ParseError {
    BadMagic,
    UnsupportedVersion(u16),
    InvalidHeaderSize(u16),
    InvalidRecordType(u16),
    PayloadTooLarge(usize),
}

/// Incremental parser with a reusable receive buffer. A reconnect calls
/// `reset`, discarding any partial old-connection record.
pub struct Parser {
    buffer: Vec<u8>,
    consumed: usize,
    spare_payload: Vec<u8>,
}

impl Parser {
    pub fn new() -> Self {
        Self {
            buffer: Vec::with_capacity(128 * 1024),
            consumed: 0,
            spare_payload: Vec::with_capacity(256 * 1024),
        }
    }

    pub fn reset(&mut self) {
        self.buffer.clear();
        self.consumed = 0;
    }

    pub fn push(&mut self, bytes: &[u8]) {
        self.buffer.extend_from_slice(bytes);
    }

    /// Return a processed payload allocation to the parser. The streaming loop
    /// calls this after synchronous decode so steady-state AUs reuse storage.
    pub fn recycle_payload(&mut self, mut payload: Vec<u8>) {
        payload.clear();
        if payload.capacity() > self.spare_payload.capacity() {
            self.spare_payload = payload;
        }
    }

    pub fn next(&mut self) -> Result<Option<Record>, ParseError> {
        let available = &self.buffer[self.consumed..];
        if available.len() < HEADER_SIZE {
            return Ok(None);
        }
        if available[..4] != MAGIC {
            return Err(ParseError::BadMagic);
        }
        let version = u16::from_le_bytes([available[4], available[5]]);
        if version != VERSION {
            return Err(ParseError::UnsupportedVersion(version));
        }
        let header_size = u16::from_le_bytes([available[6], available[7]]);
        if header_size as usize != HEADER_SIZE {
            return Err(ParseError::InvalidHeaderSize(header_size));
        }
        let record_type = u16::from_le_bytes([available[8], available[9]]);
        if !(TYPE_STREAM_INFO..=TYPE_ERROR).contains(&record_type) {
            return Err(ParseError::InvalidRecordType(record_type));
        }
        let flags = u32::from_le_bytes(available[12..16].try_into().unwrap());
        let sequence = u64::from_le_bytes(available[16..24].try_into().unwrap());
        let capture_timestamp_ns = u64::from_le_bytes(available[24..32].try_into().unwrap());
        let encoder_timestamp_us = i64::from_le_bytes(available[32..40].try_into().unwrap());
        let payload_len = u32::from_le_bytes(available[40..44].try_into().unwrap()) as usize;
        if payload_len > MAX_PAYLOAD {
            return Err(ParseError::PayloadTooLarge(payload_len));
        }
        let total = HEADER_SIZE + payload_len;
        if available.len() < total {
            return Ok(None);
        }
        let mut payload = std::mem::take(&mut self.spare_payload);
        payload.clear();
        payload.extend_from_slice(&available[HEADER_SIZE..total]);
        self.consumed += total;
        if self.consumed == self.buffer.len() {
            self.reset();
        } else if self.consumed >= 256 * 1024 {
            self.buffer.drain(..self.consumed);
            self.consumed = 0;
        }
        Ok(Some(Record {
            record_type,
            flags,
            sequence,
            capture_timestamp_ns,
            encoder_timestamp_us,
            payload,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encoded(record_type: u16, flags: u32, sequence: u64, payload: &[u8]) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER_SIZE + payload.len());
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&VERSION.to_le_bytes());
        out.extend_from_slice(&(HEADER_SIZE as u16).to_le_bytes());
        out.extend_from_slice(&record_type.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&flags.to_le_bytes());
        out.extend_from_slice(&sequence.to_le_bytes());
        out.extend_from_slice(&123u64.to_le_bytes());
        out.extend_from_slice(&456i64.to_le_bytes());
        out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(payload);
        out
    }

    #[test]
    fn stream_info_transform_is_validated() {
        let mut info: StreamInfo = serde_json::from_str(
            r#"{"codec":"H264","framing":"annex-b-access-units","width":1280,"height":720,"fpsNumerator":60,"fpsDenominator":1,"bitrate":4000000,"cameraId":"0","encoderName":"test","hardwareEncoder":true,"pixelFormat":"NV12","effectiveRotation":270,"mirror":true,"sensorOrientation":90,"deviceRotation":180}"#
        ).unwrap();
        assert!(info.has_valid_transform());
        info.effective_rotation = 45;
        assert!(!info.has_valid_transform());
    }

    #[test]
    fn fragmented_reads() {
        let bytes = encoded(TYPE_VIDEO_ACCESS_UNIT, FLAG_KEYFRAME, 7, b"frame");
        let mut p = Parser::new();
        for b in &bytes[..bytes.len() - 1] {
            p.push(&[*b]);
            assert_eq!(p.next().unwrap(), None);
        }
        p.push(&bytes[bytes.len() - 1..]);
        let record = p.next().unwrap().unwrap();
        assert_eq!(record.sequence, 7);
        assert_eq!(record.payload, b"frame");
        assert!(record.is_keyframe());
    }

    #[test]
    fn multiple_records_in_one_read() {
        let mut bytes = encoded(TYPE_CODEC_CONFIG, FLAG_CODEC_CONFIG, 1, b"cfg");
        bytes.extend(encoded(TYPE_VIDEO_ACCESS_UNIT, 0, 2, b"au"));
        let mut p = Parser::new();
        p.push(&bytes);
        assert_eq!(p.next().unwrap().unwrap().record_type, TYPE_CODEC_CONFIG);
        assert_eq!(
            p.next().unwrap().unwrap().record_type,
            TYPE_VIDEO_ACCESS_UNIT
        );
        assert!(p.next().unwrap().is_none());
    }

    #[test]
    fn malformed_length_is_rejected_before_allocation() {
        let mut bytes = encoded(TYPE_VIDEO_ACCESS_UNIT, 0, 1, b"");
        bytes[40..44].copy_from_slice(&((MAX_PAYLOAD as u32) + 1).to_le_bytes());
        let mut p = Parser::new();
        p.push(&bytes);
        assert_eq!(p.next(), Err(ParseError::PayloadTooLarge(MAX_PAYLOAD + 1)));
    }

    #[test]
    fn reconnect_discards_middle_of_record() {
        let first = encoded(TYPE_VIDEO_ACCESS_UNIT, 0, 1, b"partial");
        let second = encoded(TYPE_STREAM_INFO, FLAG_DISCONTINUITY, 9, b"new");
        let mut p = Parser::new();
        p.push(&first[..20]);
        p.reset();
        p.push(&second);
        assert_eq!(p.next().unwrap().unwrap().sequence, 9);
    }

    #[test]
    fn codec_configuration_and_keyframe_restart_flags_survive() {
        let mut bytes = encoded(
            TYPE_CODEC_CONFIG,
            FLAG_CODEC_CONFIG | FLAG_DISCONTINUITY,
            1,
            b"spspps",
        );
        bytes.extend(encoded(TYPE_VIDEO_ACCESS_UNIT, FLAG_KEYFRAME, 2, b"idr"));
        let mut p = Parser::new();
        p.push(&bytes);
        let config = p.next().unwrap().unwrap();
        let idr = p.next().unwrap().unwrap();
        assert_eq!(config.flags & FLAG_CODEC_CONFIG, FLAG_CODEC_CONFIG);
        assert!(config.is_discontinuity());
        assert!(idr.is_keyframe());
    }
}
