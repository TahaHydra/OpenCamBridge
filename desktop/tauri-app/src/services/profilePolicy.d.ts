import type { CameraInfo, CameraSettings } from '../state/types';

export interface CaptureProfile {
  id: string;
  name: string;
  description?: string;
  builtIn?: boolean;
  width: number;
  height: number;
  fps: number;
  streamMode: 'h264' | 'mjpeg';
  h264BitrateMbps?: number;
  jpegQuality?: number;
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
export function profileAvailability(profile: CaptureProfile, camera: CameraInfo | undefined | null): ProfileAvailability;
export function matchProfile(profiles: readonly CaptureProfile[], settings: CameraSettings | null | undefined): CaptureProfile | null;
export function phoneProfileForResolution(width: number): 'quality' | 'balanced' | 'low-latency';
export function buildProfileChange(
  profile: CaptureProfile,
  settings: CameraSettings,
): { next: CameraSettings; keys: (keyof CameraSettings)[] };
export function describeSettingsChange(keys: string[], next: CameraSettings, cameraLabel?: string): string;
