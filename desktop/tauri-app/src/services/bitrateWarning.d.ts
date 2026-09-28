import type { CameraSettings } from '../state/types';
export function manualBitrateWarning(settings: CameraSettings, active?: { encodedWidth?: number; encodedHeight?: number; selectedFps?: number } | null): null | { recommendation: number; width: number; height: number; fps: number };
