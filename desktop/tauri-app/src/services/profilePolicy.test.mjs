import test from 'node:test';
import assert from 'node:assert/strict';
import {
  BUILT_IN_PROFILES,
  buildProfileChange,
  describeSettingsChange,
  matchProfile,
  phoneProfileForResolution,
  profileAvailability,
  regularH264Modes,
  resolveProfile,
  resolveProfiles,
} from './profilePolicy.js';

const byId = id => BUILT_IN_PROFILES.find(profile => profile.id === id);

// A phone like the OnePlus 9 main lens: 30 fps only through the regular session.
const onePlusMain = {
  id: '0',
  label: 'Back main',
  h264Modes: [
    { width: 1920, height: 1080, fps: 30 },
    { width: 1280, height: 720, fps: 30 },
  ],
  mjpegModes: [
    { width: 1920, height: 1080, fps: 30 },
    { width: 1280, height: 720, fps: 30 },
  ],
};

// A phone that genuinely offers regular 60 fps.
const sixtyCapable = {
  id: '0',
  label: 'Back main',
  h264Modes: [
    { width: 1920, height: 1080, fps: 30 },
    { width: 1920, height: 1080, fps: 60 },
    { width: 1280, height: 720, fps: 30 },
    { width: 1280, height: 720, fps: 60 },
  ],
  mjpegModes: [{ width: 1280, height: 720, fps: 30 }],
};

const mode = (width, height, fps) => ({ width, height, fps });
const REPORTED_ORDER = [mode(1920, 1080, 60), mode(1280, 720, 60), mode(1920, 1080, 30), mode(1280, 720, 30)];
const hasMode = (list, m) => list.some(item => item.width === m.width && item.height === m.height && item.fps === m.fps);

/**
 * A lens as current phone builds report it (CameraRepository): per-path
 * evidence for every preferred mode, and `h264Modes` = modes with any
 * supported path.
 */
function reportedLens(id, label, { regular, highSpeed = [] }) {
  const h264PathCapabilities = REPORTED_ORDER.map(m => ({
    mode: m,
    paths: [
      { engine: 'REGULAR_SURFACE', supported: hasMode(regular, m), reason: hasMode(regular, m) ? 'Supported' : 'No AE range' },
      { engine: 'HIGH_SPEED_SURFACE', supported: hasMode(highSpeed, m), reason: hasMode(highSpeed, m) ? 'Supported' : 'Disabled' },
    ],
  }));
  return {
    id,
    label,
    h264Modes: h264PathCapabilities.filter(entry => entry.paths.some(path => path.supported)).map(entry => entry.mode),
    h264PathCapabilities,
    mjpegModes: [mode(1280, 720, 30)],
  };
}

// A phone like the Galaxy S24: regular 1080p60 and 720p60.
const fullHd60 = reportedLens('0', 'Back main', { regular: REPORTED_ORDER });
// 60 fps only at 720p.
const hd60Only = reportedLens('2', 'Back ultrawide', { regular: [mode(1280, 720, 60), mode(1920, 1080, 30), mode(1280, 720, 30)] });
// 30 fps only.
const thirtyOnly = reportedLens('1', 'Front camera', { regular: [mode(1920, 1080, 30), mode(1280, 720, 30)] });
// 60 fps exists, but only through a constrained high-speed session.
const highSpeedSixty = reportedLens('0', 'Back main', {
  regular: [mode(1920, 1080, 30), mode(1280, 720, 30)],
  highSpeed: [mode(1280, 720, 60)],
});

const baseSettings = {
  cameraId: '0',
  profile: 'adaptive',
  width: 1920,
  height: 1080,
  outputWidth: 1920,
  outputHeight: 1080,
  fps: 30,
  jpegQuality: 75,
  displayRotation: '0',
  aspectRatio: '16:9',
  mirror: false,
  torchEnabled: false,
  linearZoom: 0,
  streamMode: 'h264',
  targetBandwidthMbps: 0,
  h264Bitrate: 8_000_000,
  h264KeyframeInterval: 5,
};

test('built-in profile ids are unique and stable', () => {
  const ids = BUILT_IN_PROFILES.map(profile => profile.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const id of ['meeting-hd', 'low-latency', 'high-quality', 'smooth-motion', 'compatibility']) assert.ok(byId(id), id);
});

test('a profile the lens supports exactly is available', () => {
  assert.deepEqual(profileAvailability(byId('meeting-hd'), onePlusMain), { available: true });
});

test('an unsupported frame rate is explained, not silently replaced', () => {
  const result = profileAvailability({ ...byId('meeting-hd'), fps: 60 }, onePlusMain);
  assert.equal(result.available, false);
  assert.equal(result.reason, '60 FPS unavailable on Back main at 1080p');
});

test('Smooth Motion uses 1080p60 when the camera genuinely offers it', () => {
  const resolved = resolveProfile(byId('smooth-motion'), fullHd60);
  assert.equal(resolved.id, 'smooth-motion');
  assert.deepEqual(
    [resolved.width, resolved.height, resolved.fps, resolved.streamMode, resolved.h264BitrateMbps],
    [1920, 1080, 60, 'h264', 16],
  );
  assert.deepEqual(profileAvailability(resolved, fullHd60), { available: true });
});

test('Smooth Motion falls back to 720p60 when that is the best 60 FPS mode', () => {
  const resolved = resolveProfile(byId('smooth-motion'), hd60Only);
  assert.deepEqual([resolved.width, resolved.height, resolved.fps, resolved.h264BitrateMbps], [1280, 720, 60, 10]);
  assert.deepEqual(profileAvailability(byId('smooth-motion'), hd60Only), { available: true });
});

test('Smooth Motion is unavailable, with the reason, on a 30 FPS camera', () => {
  const resolved = resolveProfile(byId('smooth-motion'), thirtyOnly);
  assert.equal(resolved.id, 'smooth-motion');
  assert.equal(resolved.unavailableReason, 'Needs 60 FPS — Front camera supports up to 30 FPS');
  assert.deepEqual(profileAvailability(byId('smooth-motion'), thirtyOnly), {
    available: false,
    reason: 'Needs 60 FPS — Front camera supports up to 30 FPS',
  });
  // Phones without per-path evidence are judged by their H.264 list.
  assert.equal(profileAvailability(byId('smooth-motion'), onePlusMain).reason, 'Needs 60 FPS — Back main supports up to 30 FPS');
});

test('a 60 FPS mode that only high-speed capture reaches is not genuine', () => {
  assert.ok(hasMode(highSpeedSixty.h264Modes, mode(1280, 720, 60)), 'the mode is still selectable by hand');
  assert.equal(hasMode(regularH264Modes(highSpeedSixty), mode(1280, 720, 60)), false);
  assert.deepEqual(profileAvailability(byId('smooth-motion'), highSpeedSixty), {
    available: false,
    reason: 'Needs 60 FPS — Back main reaches it only in high-speed mode',
  });
});

test('phones that send no path evidence use their H.264 list as is', () => {
  const resolved = resolveProfile(byId('smooth-motion'), sixtyCapable);
  assert.deepEqual([resolved.width, resolved.height, resolved.fps], [1920, 1080, 60]);
});

test('a resolved profile is resolved again for another camera', () => {
  const onMain = resolveProfile(byId('smooth-motion'), fullHd60);
  const onUltrawide = resolveProfile(onMain, hd60Only);
  assert.deepEqual([onUltrawide.width, onUltrawide.height], [1280, 720]);
  assert.equal(profileAvailability(onMain, thirtyOnly).available, false);
  const onFront = resolveProfile(byId('smooth-motion'), thirtyOnly);
  assert.equal(resolveProfile(onFront, fullHd60).unavailableReason, undefined);
  assert.equal(profileAvailability(onFront, fullHd60).available, true);
});

test('Smooth Motion is only known once cameras are reported', () => {
  assert.equal(profileAvailability(byId('smooth-motion'), undefined).available, false);
});

test('fixed profiles resolve to themselves', () => {
  assert.equal(resolveProfile(byId('meeting-hd'), fullHd60), byId('meeting-hd'));
});

test('settings read as Smooth Motion only when they equal what it resolves to', () => {
  const sixty = { ...baseSettings, fps: 60, h264Bitrate: 16_000_000 };
  assert.equal(matchProfile(resolveProfiles(BUILT_IN_PROFILES, fullHd60), sixty)?.id, 'smooth-motion');
  const hd = { ...sixty, width: 1280, height: 720, outputWidth: 1280, outputHeight: 720, h264Bitrate: 10_000_000 };
  assert.equal(matchProfile(resolveProfiles(BUILT_IN_PROFILES, hd60Only), hd)?.id, 'smooth-motion');
  assert.equal(matchProfile(resolveProfiles(BUILT_IN_PROFILES, fullHd60), hd), null, '720p60 is Custom on a 1080p60 camera');
  assert.equal(matchProfile(resolveProfiles(BUILT_IN_PROFILES, thirtyOnly), sixty), null);
});

test('applying Smooth Motion sends the resolved mode', () => {
  const current = { ...baseSettings, profile: 'quality' };
  const { next, keys } = buildProfileChange(resolveProfile(byId('smooth-motion'), hd60Only), current);
  assert.deepEqual(keys, ['profile', 'width', 'height', 'outputWidth', 'outputHeight', 'fps', 'h264Bitrate']);
  assert.deepEqual([next.profile, next.width, next.height, next.fps, next.h264Bitrate], ['balanced', 1280, 720, 60, 10_000_000]);
});

test('a missing resolution names the resolution', () => {
  const result = profileAvailability({ ...byId('meeting-hd'), streamMode: 'mjpeg' }, sixtyCapable);
  assert.equal(result.available, false);
  assert.equal(result.reason, '1080p unavailable on Back main in MJPEG');
});

test('a codec with no modes on the lens is reported as unavailable', () => {
  const result = profileAvailability(byId('compatibility'), { ...onePlusMain, mjpegModes: [] });
  assert.equal(result.available, false);
  assert.match(result.reason, /MJPEG is unavailable on Back main/);
});

test('availability is unknown until capabilities arrive', () => {
  assert.equal(profileAvailability(byId('meeting-hd'), undefined).available, false);
});

test('the current settings match the profile that pins the same bitrate', () => {
  assert.equal(matchProfile(BUILT_IN_PROFILES, baseSettings)?.id, 'meeting-hd');
  assert.equal(matchProfile(BUILT_IN_PROFILES, { ...baseSettings, h264Bitrate: 16_000_000 })?.id, 'high-quality');
});

test('settings that no profile describes read as custom', () => {
  const profiles = resolveProfiles(BUILT_IN_PROFILES, fullHd60);
  assert.equal(matchProfile(profiles, { ...baseSettings, h264Bitrate: 12_000_000 }), null);
  assert.equal(matchProfile(profiles, { ...baseSettings, fps: 60 }), null);
  assert.equal(matchProfile(profiles, { ...baseSettings, streamMode: 'mjpeg' }), null);
});

test('MJPEG profiles match on quality', () => {
  const mjpeg = { ...baseSettings, width: 1280, height: 720, streamMode: 'mjpeg', jpegQuality: 80 };
  assert.equal(matchProfile(BUILT_IN_PROFILES, mjpeg)?.id, 'compatibility');
  assert.equal(matchProfile(BUILT_IN_PROFILES, { ...mjpeg, jpegQuality: 60 }), null);
});

test('phone capture policy follows resolution exactly as the resolution picker does', () => {
  assert.equal(phoneProfileForResolution(1920), 'quality');
  assert.equal(phoneProfileForResolution(1280), 'balanced');
  assert.equal(phoneProfileForResolution(960), 'low-latency');
});

test('a bitrate-only profile switch does not report capture keys', () => {
  const current = { ...baseSettings, profile: 'quality' };
  const { next, keys } = buildProfileChange(byId('high-quality'), current);
  assert.deepEqual(keys, ['h264Bitrate']);
  assert.equal(next.h264Bitrate, 16_000_000);
});

test('a resolution change reports size, output and policy keys', () => {
  const { next, keys } = buildProfileChange(byId('low-latency'), { ...baseSettings, profile: 'quality' });
  assert.deepEqual(keys, ['profile', 'width', 'height', 'outputWidth', 'outputHeight', 'h264Bitrate']);
  assert.equal(next.profile, 'balanced');
  assert.equal(next.width, 1280);
});

test('an adaptive phone is moved to an explicit policy when a profile is applied', () => {
  const { next, keys } = buildProfileChange(byId('meeting-hd'), baseSettings);
  assert.deepEqual(keys, ['profile']);
  assert.equal(next.profile, 'quality');
});

test('the switching summary names the most significant change', () => {
  const next = { ...baseSettings, width: 1280, height: 720, fps: 60 };
  assert.equal(describeSettingsChange(['width', 'height', 'fps'], next), 'Switching to 720p60…');
  assert.equal(describeSettingsChange(['streamMode'], { ...next, streamMode: 'mjpeg' }), 'Switching to MJPEG…');
  assert.equal(describeSettingsChange(['cameraId', 'width'], next, 'Front camera 1'), 'Switching to Front camera 1…');
  assert.equal(describeSettingsChange(['h264Bitrate'], next), 'Updating quality…');
});
