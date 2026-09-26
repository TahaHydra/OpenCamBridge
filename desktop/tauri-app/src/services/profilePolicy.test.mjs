import test from 'node:test';
import assert from 'node:assert/strict';
import {
  BUILT_IN_PROFILES,
  buildProfileChange,
  describeSettingsChange,
  matchProfile,
  phoneProfileForResolution,
  profileAvailability,
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
  for (const id of ['meeting-hd', 'low-latency', 'high-quality', 'compatibility']) assert.ok(byId(id), id);
});

test('a profile the lens supports exactly is available', () => {
  assert.deepEqual(profileAvailability(byId('meeting-hd'), onePlusMain), { available: true });
});

test('an unsupported frame rate is explained, not silently replaced', () => {
  const result = profileAvailability(byId('smooth-motion'), onePlusMain);
  assert.equal(result.available, false);
  assert.equal(result.reason, '60 FPS unavailable on Back main at 720p');
});

test('the same profile becomes available on a phone with a real 60 fps mode', () => {
  assert.equal(profileAvailability(byId('smooth-motion'), sixtyCapable).available, true);
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
  assert.equal(matchProfile(BUILT_IN_PROFILES, { ...baseSettings, h264Bitrate: 12_000_000 }), null);
  assert.equal(matchProfile(BUILT_IN_PROFILES, { ...baseSettings, fps: 60 }), null);
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
