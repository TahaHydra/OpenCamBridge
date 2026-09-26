/**
 * Capture profiles: presets — one-click shortcuts to a resolution, frame rate,
 * format and quality. The camera panel remains the manual control surface and
 * lists every mode the phone reports; a profile only ever selects one of those.
 *
 * A profile is a request, not a promise. `profileAvailability` checks it
 * against the exact capture + encoder tuples the selected lens advertises and
 * explains a mismatch in plain words instead of silently applying something
 * else. Applying a profile sends an explicit phone profile, so the phone never
 * adapts it away behind the user's back either.
 *
 * Most presets are fixed. Smooth Motion is capability-aware: it resolves, per
 * camera, to the best genuine 60 FPS mode (see `resolveProfile`).
 *
 * H.264 quality: a profile either leaves the bitrate to the phone
 * (`h264BitrateMode: 'auto'`, the phone's recommended rate for the mode) or
 * pins one (`'manual'` + `h264BitrateMbps`). Applying a profile always sends
 * the mode explicitly, so a pinned rate never flips the phone to Manual by
 * implication and an automatic profile never keeps a stale manual rate.
 */

/** Built-in profiles. Ids are persisted (default profile), so keep them stable. */
export const BUILT_IN_PROFILES = Object.freeze([
  {
    id: 'meeting-hd',
    name: 'Meeting HD',
    description: 'Sharp 1080p for calls and meetings, at the recommended bitrate',
    builtIn: true,
    width: 1920,
    height: 1080,
    fps: 30,
    streamMode: 'h264',
    h264BitrateMode: 'auto',
  },
  {
    id: 'low-latency',
    name: 'Low Latency',
    description: 'Lightest load: 720p at a fixed, low bitrate',
    builtIn: true,
    width: 1280,
    height: 720,
    fps: 30,
    streamMode: 'h264',
    h264BitrateMode: 'manual',
    h264BitrateMbps: 4,
  },
  {
    id: 'high-quality',
    name: 'High Quality',
    description: 'Most detail, for recording and streaming: a fixed high bitrate',
    builtIn: true,
    width: 1920,
    height: 1080,
    fps: 30,
    streamMode: 'h264',
    h264BitrateMode: 'manual',
    h264BitrateMbps: 16,
  },
  {
    id: 'smooth-motion',
    name: 'Smooth Motion',
    description: '60 FPS — 1080p where the camera supports it, otherwise 720p — at the recommended bitrate',
    builtIn: true,
    // Placeholder geometry: always resolved against the selected camera.
    width: 1920,
    height: 1080,
    fps: 60,
    streamMode: 'h264',
    // The phone picks the bitrate for whichever size is chosen.
    h264BitrateMode: 'auto',
    adaptive: {
      // Best first; the first one the camera reports as a regular H.264 mode wins.
      candidates: [
        { width: 1920, height: 1080 },
        { width: 1280, height: 720 },
      ],
    },
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
  if (profile.streamMode === 'h264') {
    return profileBitrateMode(profile) === 'manual' ? `${base} · ${profile.h264BitrateMbps} Mb/s` : `${base} · auto bitrate`;
  }
  if (profile.streamMode === 'mjpeg' && profile.jpegQuality) return `${base} · quality ${profile.jpegQuality}%`;
  return base;
}

/**
 * A profile's H.264 bitrate mode. Profiles saved before modes existed always
 * pinned a bitrate, so they read as Manual.
 * @returns {'auto' | 'manual'}
 */
export function profileBitrateMode(profile) {
  if (profile.h264BitrateMode === 'auto') return 'auto';
  if (profile.h264BitrateMode === 'manual' && profile.h264BitrateMbps) return 'manual';
  return profile.h264BitrateMode == null && profile.h264BitrateMbps ? 'manual' : 'auto';
}

/**
 * The bitrate the phone's Automatic mode targets for a mode, in Mb/s — a copy
 * of H264BitratePolicy.kt for display. The phone also keeps it inside what
 * its encoder supports, which the desktop cannot see.
 */
export function automaticBitrateMbps(width, height, fps) {
  const pixels = (Number(width) || 0) * (Number(height) || 0);
  const smooth = (Number(fps) || 0) >= 50;
  if (pixels >= 1920 * 1080) return smooth ? 16 : 10;
  if (pixels >= 1280 * 720) return smooth ? 9 : 6;
  return 3;
}

function modesFor(camera, streamMode) {
  if (!camera) return [];
  const modes = streamMode === 'mjpeg' ? camera.mjpegModes : camera.h264Modes;
  return Array.isArray(modes) ? modes : [];
}

const sameMode = (a, b) =>
  Number(a.width) === Number(b.width) && Number(a.height) === Number(b.height) && Number(a.fps) === Number(b.fps);

const maxFps = modes => Math.max(0, ...modes.map(mode => Number(mode.fps) || 0));

/**
 * H.264 modes the camera delivers through a regular Camera2 capture session —
 * the genuine ones.
 *
 * `h264Modes` is the phone's selectable list; `h264PathCapabilities` says, per
 * mode, which capture engine can provide it. A rate that only a constrained
 * high-speed session reaches does not count. Phone builds that send no path
 * evidence only ever listed regular-session modes, so their list is used as is.
 */
export function regularH264Modes(camera) {
  const listed = modesFor(camera, 'h264');
  const evidence = Array.isArray(camera?.h264PathCapabilities) ? camera.h264PathCapabilities : [];
  if (evidence.length === 0) return listed;
  return listed.filter(mode => {
    const entry = evidence.find(candidate => candidate?.mode && sameMode(candidate.mode, mode));
    if (!entry || !Array.isArray(entry.paths)) return false;
    return entry.paths.some(path => path?.engine === 'REGULAR_SURFACE' && path?.supported === true);
  });
}

/**
 * The concrete form of a profile for one camera. Fixed profiles come back
 * unchanged. A capability-aware profile (Smooth Motion) takes the first of its
 * candidates the camera genuinely offers at the profile's frame rate — or,
 * when there is none, keeps its id and carries `unavailableReason`. Resolving
 * an already-resolved profile again, for any camera, starts from scratch.
 */
export function resolveProfile(profile, camera) {
  if (!profile?.adaptive) return profile;
  const { unavailableReason: _stale, ...base } = profile;
  const unavailable = reason => ({ ...base, unavailableReason: reason });
  if (!camera) return unavailable('Waiting for the phone to report its cameras');

  const regular = regularH264Modes(camera);
  for (const candidate of base.adaptive.candidates) {
    const mode = { width: candidate.width, height: candidate.height, fps: base.fps };
    if (regular.some(item => sameMode(item, mode))) return { ...base, ...candidate };
  }

  const lens = camera.label || `camera ${camera.id}`;
  const listed = modesFor(camera, 'h264');
  if (listed.length === 0) return unavailable(`H.264 is unavailable on ${lens}`);
  const need = `Needs ${base.fps} FPS`;
  const regularBest = maxFps(regular);
  if (regularBest >= base.fps) {
    const sizes = base.adaptive.candidates.map(c => heightLabel(c.height)).reverse().join(' or ');
    return unavailable(`${need} at ${sizes} — ${lens} offers it only at other sizes`);
  }
  if (maxFps(listed) >= base.fps) return unavailable(`${need} — ${lens} reaches it only in high-speed mode`);
  return unavailable(`${need} — ${lens} supports up to ${maxFps(listed)} FPS`);
}

export function resolveProfiles(profiles, camera) {
  return profiles.map(profile => resolveProfile(profile, camera));
}

/**
 * Whether the selected lens can deliver this profile exactly. Capability-aware
 * profiles are resolved for `camera` first, so passing the template or a copy
 * resolved for another camera is safe.
 * @returns {{ available: boolean, reason?: string }}
 */
export function profileAvailability(requested, camera) {
  const profile = resolveProfile(requested, camera);
  if (profile?.unavailableReason) return { available: false, reason: profile.unavailableReason };
  if (!camera) return { available: false, reason: 'Waiting for the phone to report its cameras' };
  const lens = camera.label || `camera ${camera.id}`;
  const modes = modesFor(camera, profile.streamMode);
  if (modes.length === 0) {
    return { available: false, reason: `${codecLabel(profile.streamMode)} is unavailable on ${lens}` };
  }
  const exact = modes.some(m => sameMode(m, profile));
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
 * Pass resolved profiles so capability-aware presets match what they would
 * actually apply. For H.264 the bitrate mode must match, and a Manual profile
 * also its bitrate; JPEG quality takes part only when a profile pins it.
 */
export function matchProfile(profiles, settings) {
  if (!settings) return null;
  const settingsBitrateMode = settings.h264BitrateMode === 'manual' ? 'manual' : 'auto';
  return profiles.find(profile => {
    if (profile.unavailableReason) return false;
    if (profile.width !== Number(settings.width) || profile.height !== Number(settings.height)) return false;
    if (profile.fps !== Number(settings.fps) || profile.streamMode !== settings.streamMode) return false;
    if (profile.streamMode === 'h264') {
      const mode = profileBitrateMode(profile);
      if (mode !== settingsBitrateMode) return false;
      return mode === 'auto'
        || Math.abs(profile.h264BitrateMbps * 1_000_000 - Number(settings.h264Bitrate)) < BITRATE_TOLERANCE;
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
 * Settings that apply `profile` (already resolved), plus the keys that
 * actually change. Only changed keys are reported so, e.g., a bitrate-only
 * switch stays a live update instead of rebinding the camera. H.264 profiles
 * always set the bitrate mode; an automatic profile leaves the stored manual
 * bitrate alone. MJPEG profiles do not touch H.264 settings.
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
  if (profile.streamMode === 'h264') {
    next.h264BitrateMode = profileBitrateMode(profile);
    if (next.h264BitrateMode === 'manual') next.h264Bitrate = Math.round(profile.h264BitrateMbps * 1_000_000);
  }
  if (profile.streamMode === 'mjpeg' && profile.jpegQuality) {
    next.jpegQuality = profile.jpegQuality;
  }
  const candidates = [
    'profile', 'width', 'height', 'outputWidth', 'outputHeight', 'fps', 'streamMode',
    'h264Bitrate', 'h264BitrateMode', 'jpegQuality',
  ];
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
  if (touches('h264Bitrate') || touches('h264BitrateMode') || touches('jpegQuality') || touches('targetBandwidthMbps')) {
    return 'Updating quality…';
  }
  return 'Applying settings…';
}
