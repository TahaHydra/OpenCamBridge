/**
 * Capture profiles: named capture preferences that are only ever applied when
 * the phone reports a complete path for them.
 *
 * A profile is a request, not a promise. `profileAvailability` checks it
 * against the exact capture + encoder tuples the selected lens advertises and
 * explains a mismatch in plain words ("60 FPS unavailable on Back main at
 * 1080p") instead of silently applying something else. Applying a profile sends
 * an explicit phone profile, so the phone never adapts it away behind the
 * user's back either.
 */

/** Built-in profiles. Ids are persisted (default profile), so keep them stable. */
export const BUILT_IN_PROFILES = Object.freeze([
  {
    id: 'meeting-hd',
    name: 'Meeting HD',
    description: 'Sharp 1080p for calls and meetings',
    builtIn: true,
    width: 1920,
    height: 1080,
    fps: 30,
    streamMode: 'h264',
    h264BitrateMbps: 8,
  },
  {
    id: 'low-latency',
    name: 'Low Latency',
    description: 'Lightest load and the quickest response',
    builtIn: true,
    width: 1280,
    height: 720,
    fps: 30,
    streamMode: 'h264',
    h264BitrateMbps: 4,
  },
  {
    id: 'high-quality',
    name: 'High Quality',
    description: 'Most detail, for recording and streaming',
    builtIn: true,
    width: 1920,
    height: 1080,
    fps: 30,
    streamMode: 'h264',
    h264BitrateMbps: 16,
  },
  {
    id: 'smooth-motion',
    name: 'Smooth Motion',
    description: '60 FPS where the camera supports it',
    builtIn: true,
    width: 1280,
    height: 720,
    fps: 60,
    streamMode: 'h264',
    h264BitrateMbps: 10,
  },
  {
    id: 'compatibility',
    name: 'Compatibility',
    description: 'MJPEG for PCs without hardware H.264',
    builtIn: true,
    width: 1280,
    height: 720,
    fps: 30,
    streamMode: 'mjpeg',
    jpegQuality: 80,
  },
]);

export function heightLabel(height) {
  const h = Number(height) || 0;
  return h > 0 ? `${h}p` : '—';
}

export function codecLabel(streamMode) {
  return streamMode === 'mjpeg' ? 'MJPEG' : 'H.264';
}

/** "1080p · 30 FPS · H.264" */
export function describeMode(_width, height, fps, streamMode) {
  return `${heightLabel(height)} · ${Number(fps) || '—'} FPS · ${codecLabel(streamMode)}`;
}

export function describeProfile(profile, { withQuality = false } = {}) {
  const base = describeMode(profile.width, profile.height, profile.fps, profile.streamMode);
  if (!withQuality) return base;
  if (profile.streamMode === 'h264' && profile.h264BitrateMbps) return `${base} · ${profile.h264BitrateMbps} Mb/s`;
  if (profile.streamMode === 'mjpeg' && profile.jpegQuality) return `${base} · Q${profile.jpegQuality}`;
  return base;
}

function modesFor(camera, streamMode) {
  if (!camera) return [];
  const modes = streamMode === 'mjpeg' ? camera.mjpegModes : camera.h264Modes;
  return Array.isArray(modes) ? modes : [];
}

/**
 * Whether the selected lens can deliver this profile exactly.
 * @returns {{ available: boolean, reason?: string }}
 */
export function profileAvailability(profile, camera) {
  if (!camera) return { available: false, reason: 'Waiting for the phone to report its cameras' };
  const lens = camera.label || `camera ${camera.id}`;
  const modes = modesFor(camera, profile.streamMode);
  if (modes.length === 0) {
    return { available: false, reason: `${codecLabel(profile.streamMode)} is unavailable on ${lens}` };
  }
  const exact = modes.some(m =>
    Number(m.width) === profile.width && Number(m.height) === profile.height && Number(m.fps) === profile.fps);
  if (exact) return { available: true };
  const sizeExists = modes.some(m => Number(m.width) === profile.width && Number(m.height) === profile.height);
  if (sizeExists) {
    return { available: false, reason: `${profile.fps} FPS unavailable on ${lens} at ${heightLabel(profile.height)}` };
  }
  return {
    available: false,
    reason: `${heightLabel(profile.height)} unavailable on ${lens}${profile.streamMode === 'mjpeg' ? ' in MJPEG' : ''}`,
  };
}

const BITRATE_TOLERANCE = 500_000;

/**
 * The profile the phone's current settings correspond to, or null ("Custom").
 * Bitrate / JPEG quality take part only when the profile pins them.
 */
export function matchProfile(profiles, settings) {
  if (!settings) return null;
  return profiles.find(profile => {
    if (profile.width !== Number(settings.width) || profile.height !== Number(settings.height)) return false;
    if (profile.fps !== Number(settings.fps) || profile.streamMode !== settings.streamMode) return false;
    if (profile.streamMode === 'h264' && profile.h264BitrateMbps) {
      return Math.abs(profile.h264BitrateMbps * 1_000_000 - Number(settings.h264Bitrate)) < BITRATE_TOLERANCE;
    }
    if (profile.streamMode === 'mjpeg' && profile.jpegQuality) {
      return profile.jpegQuality === Number(settings.jpegQuality);
    }
    return true;
  }) ?? null;
}

/**
 * The explicit phone capture policy for a resolution. Mirrors what the
 * resolution picker has always sent, so profiles and manual changes select the
 * same phone-side path and neither leaves the phone on 'adaptive'.
 */
export function phoneProfileForResolution(width) {
  const w = Number(width) || 0;
  return w >= 1920 ? 'quality' : w >= 1280 ? 'balanced' : 'low-latency';
}

/**
 * Settings that apply `profile`, plus the keys that actually change. Only
 * changed keys are reported so, e.g., a bitrate-only switch stays a live update
 * instead of rebinding the camera.
 */
export function buildProfileChange(profile, settings) {
  const next = {
    ...settings,
    profile: phoneProfileForResolution(profile.width),
    width: profile.width,
    height: profile.height,
    outputWidth: profile.width,
    outputHeight: profile.height,
    fps: profile.fps,
    streamMode: profile.streamMode,
  };
  if (profile.streamMode === 'h264' && profile.h264BitrateMbps) {
    next.h264Bitrate = Math.round(profile.h264BitrateMbps * 1_000_000);
  }
  if (profile.streamMode === 'mjpeg' && profile.jpegQuality) {
    next.jpegQuality = profile.jpegQuality;
  }
  const candidates = ['profile', 'width', 'height', 'outputWidth', 'outputHeight', 'fps', 'streamMode', 'h264Bitrate', 'jpegQuality'];
  const keys = candidates.filter(key => next[key] !== settings[key]);
  return { next, keys };
}

/** A short human summary of what is about to change, for the "Switching…" state. */
export function describeSettingsChange(keys, next, cameraLabel) {
  const touches = key => keys.includes(key);
  if (touches('cameraId')) return `Switching to ${cameraLabel || 'the selected camera'}…`;
  if (touches('streamMode')) return `Switching to ${codecLabel(next.streamMode)}…`;
  if (touches('width') || touches('height') || touches('fps')) {
    return `Switching to ${heightLabel(next.height)}${Number(next.fps) || ''}…`;
  }
  if (touches('mirror')) return next.mirror ? 'Mirroring the output…' : 'Removing the output mirror…';
  if (touches('displayRotation')) return 'Rotating…';
  if (touches('h264Bitrate') || touches('jpegQuality') || touches('targetBandwidthMbps')) return 'Updating quality…';
  return 'Applying settings…';
}
