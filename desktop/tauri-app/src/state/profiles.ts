import { useMemo } from 'react';
import { BUILT_IN_PROFILES, type CaptureProfile } from '../services/profilePolicy.js';
import { setPreferences, usePreference, type CustomProfile } from './preferences';
import type { CameraSettings } from './types';

/** Built-in profiles followed by the user's own, in creation order. */
export function useProfiles(): CaptureProfile[] {
  const [custom] = usePreference('customProfiles');
  return useMemo(
    () => [...BUILT_IN_PROFILES, ...custom.map(profile => ({ ...profile, builtIn: false }))],
    [custom],
  );
}

/** Captures the phone's current capture settings as a named profile. */
export function saveCurrentAsProfile(name: string, settings: CameraSettings): CustomProfile {
  const streamMode = settings.streamMode === 'mjpeg' ? 'mjpeg' : 'h264';
  const profile: CustomProfile = {
    id: `custom-${Date.now().toString(36)}`,
    name: name.trim() || 'My profile',
    width: settings.width,
    height: settings.height,
    fps: settings.fps,
    streamMode,
    ...(streamMode === 'h264'
      ? { h264BitrateMbps: Math.max(1, Math.round(settings.h264Bitrate / 1_000_000)) }
      : { jpegQuality: settings.jpegQuality }),
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
