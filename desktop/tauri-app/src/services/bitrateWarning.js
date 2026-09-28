import { automaticBitrateMbps } from './profilePolicy.js';

/** Selected active format, not transient measured FPS dips or pending controls. */
export function manualBitrateWarning(settings, active = {}) {
  if (settings.streamMode !== 'h264' || settings.h264BitrateMode !== 'manual') return null;
  const width = Number(active?.encodedWidth) > 0 ? Number(active.encodedWidth) : settings.width;
  const height = Number(active?.encodedHeight) > 0 ? Number(active.encodedHeight) : settings.height;
  const fps = Number(active?.selectedFps) > 0 ? Number(active.selectedFps) : settings.fps;
  const recommendation = automaticBitrateMbps(width, height, fps);
  return settings.h264Bitrate < recommendation * 1_000_000 * 0.7
    ? { recommendation, width, height, fps } : null;
}
