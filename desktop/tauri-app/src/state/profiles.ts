import { useMemo } from 'react';
import { BUILT_IN_PROFILES, describeProfile, resolveProfiles, type CaptureProfile } from '../services/profilePolicy.js';
import { setPreferences, usePreference, type CustomProfile } from './preferences';
import type { CameraInfo, CameraSettings } from './types';

/**
 * Built-in profiles followed by the user's own, in creation order, resolved
 * for the selected camera: Smooth Motion becomes the concrete 60 FPS mode
 * that lens offers, or carries the reason it has none.
 */
export function useProfiles(camera: CameraInfo | null | undefined): CaptureProfile[] {
  const [custom] = usePreference('customProfiles');
  return useMemo(
    () => resolveProfiles([...BUILT_IN_PROFILES, ...custom.map(profile => ({ ...profile, builtIn: false }))], camera),
    [custom, camera],
  );
}

/** What a profile saved from these settings would contain. */
function captureFields(settings: CameraSettings): Omit<CustomProfile, 'id' | 'name'> {
  const streamMode = settings.streamMode === 'mjpeg' ? 'mjpeg' : 'h264';
  return {
    width: settings.width,
    height: settings.height,
    fps: settings.fps,
    streamMode,
    ...(streamMode === 'h264'
      ? { h264BitrateMbps: Math.max(1, Math.round(settings.h264Bitrate / 1_000_000)) }
      : { jpegQuality: settings.jpegQuality }),
  };
}

/** "1080p · 60 FPS · H.264 · 12 Mb/s": the settings in use, as a profile would describe them. */
export function describeCurrentSettings(settings: CameraSettings): string {
  return describeProfile({ id: 'current', name: 'Current', ...captureFields(settings) }, { withQuality: true });
}

/** Captures the phone's current capture settings as a named profile. */
export function saveCurrentAsProfile(name: string, settings: CameraSettings): CustomProfile {
  const profile: CustomProfile = {
    id: `custom-${Date.now().toString(36)}`,
    name: name.trim() || 'My profile',
    ...captureFields(settings),
  };
  setPreferences(previous => ({ customProfiles: [...previous.customProfiles, profile] }));
  return profile;
}

export function renameProfile(id: string, name: string): void {
  setPreferences(previous => ({
    customProfiles: previous.customProfiles.map(profile =>
      profile.id === id ? { ...profile, name: name.trim() || profile.name } : profile),
  }));
}

export function deleteProfile(id: string): void {
  setPreferences(previous => ({
    customProfiles: previous.customProfiles.filter(profile => profile.id !== id),
    defaultProfileId: previous.defaultProfileId === id ? '' : previous.defaultProfileId,
  }));
}
