import type { CameraInfo, CameraMode, CameraSettings } from '../state/types';

/** One size a capability-aware profile may resolve to, best first. */
export interface AdaptiveCandidate {
  width: number;
  height: number;
}

export interface CaptureProfile {
  id: string;
  name: string;
  description?: string;
  builtIn?: boolean;
  width: number;
  height: number;
  fps: number;
  streamMode: 'h264' | 'mjpeg';
  /** H.264 only. 'auto': the phone's recommended rate; 'manual': h264BitrateMbps. */
  h264BitrateMode?: 'auto' | 'manual';
  h264BitrateMbps?: number;
  jpegQuality?: number;
  /** Capability-aware profiles resolve per camera (see `resolveProfile`). */
  adaptive?: { candidates: readonly AdaptiveCandidate[] };
  /** Set by `resolveProfile` when the camera cannot deliver the profile. */
  unavailableReason?: string;
}

export interface ProfileAvailability {
  available: boolean;
  reason?: string;
}

export const BUILT_IN_PROFILES: readonly CaptureProfile[];
export function heightLabel(height: number): string;
export function codecLabel(streamMode: string): string;
export function describeMode(width: number, height: number, fps: number, streamMode: string): string;
export function describeProfile(profile: CaptureProfile, options?: { withQuality?: boolean }): string;
export function profileBitrateMode(profile: CaptureProfile): 'auto' | 'manual';
export function automaticBitrateMbps(width: number, height: number, fps: number): number;
export function regularH264Modes(camera: CameraInfo | undefined | null): CameraMode[];
export function resolveProfile<T extends CaptureProfile>(profile: T, camera: CameraInfo | undefined | null): T;
export function resolveProfiles<T extends CaptureProfile>(profiles: readonly T[], camera: CameraInfo | undefined | null): T[];
export function profileAvailability(profile: CaptureProfile, camera: CameraInfo | undefined | null): ProfileAvailability;
export function matchProfile<T extends CaptureProfile>(profiles: readonly T[], settings: CameraSettings | null | undefined): T | null;
export function phoneProfileForResolution(width: number): 'quality' | 'balanced' | 'low-latency';
export function buildProfileChange(
  profile: CaptureProfile,
  settings: CameraSettings,
): { next: CameraSettings; keys: (keyof CameraSettings)[] };
export function describeSettingsChange(keys: string[], next: CameraSettings, cameraLabel?: string): string;
