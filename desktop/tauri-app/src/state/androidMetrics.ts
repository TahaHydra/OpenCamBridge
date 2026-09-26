/**
 * Flattens the phone's structured /api/stream/metrics payload into the flat
 * field names the desktop has always used. Older phone builds already answer
 * with the flat shape, which passes through unchanged.
 */
export function normalizeAndroidMetrics(raw: any): any {
  if (!raw || !('capture' in raw)) return raw;
  const capture = raw.capture;
  const h264 = raw.h264;
  const mjpeg = raw.mjpeg;
  const selection = raw.selection || {};
  const fallback = raw.fallback;
  return {
    ...raw,
    ...selection,
    fps: capture?.actualFps ?? 0,
    requestedFps: capture?.requestedFps ?? 0,
    actualFps: capture?.actualFps ?? 0,
    captureFps: capture?.actualFps ?? 0,
    cameraCaptureFps: capture?.actualFps ?? 0,
    selectedFps: capture?.selectedFps ?? 0,
    cameraSessionFps: capture?.cameraSessionFps ?? 0,
    gpuBridgeFps: capture?.gpuBridgeFps,
    captureEngine: capture?.engine,
    encodedWidth: capture?.actualWidth ?? 0,
    encodedHeight: capture?.actualHeight ?? 0,
    encodedFps: h264?.encodedFps ?? mjpeg?.encodedFps ?? 0,
    phoneEncodedFps: h264?.encodedFps ?? mjpeg?.encodedFps ?? 0,
    encodedBitrate: h264?.bitrate ?? 0,
    encoderName: h264?.encoderName,
    hardwareEncoder: h264?.hardwareEncoder ?? false,
    androidEncodeMsAvg: mjpeg?.encodeMs,
    phoneEncodeMs: mjpeg?.encodeMs ?? 0,
    yuvMsAvg: mjpeg?.yuvMs,
    jpegMsAvg: mjpeg?.jpegMs,
    rotateMsAvg: mjpeg?.rotateMs,
    mjpegProcessingCapacityFps: mjpeg?.processingCapacityFps ?? 0,
    latestFrameRevision: mjpeg?.latestFrameRevision ?? 0,
    estimatedMbps: raw.transport?.estimatedMbps ?? '0.0',
    targetBandwidthMbps: raw.transport?.targetBandwidthMbps ?? 0,
    fallbackUsed: fallback?.active ?? false,
    fallbackReason: fallback?.reason ?? '',
  };
}
