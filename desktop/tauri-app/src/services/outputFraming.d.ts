export interface CropRect { x: number; y: number; width: number; height: number }
export type FramingPreset = 'fit' | 'fill' | '16:9' | '9:16' | '4:3' | '1:1' | 'custom';
export interface OutputFraming { preset: FramingPreset; mode: 'fit' | 'fill' | 'custom'; crop: CropRect }
export const FULL_CROP: CropRect;
export const DEFAULT_FRAMING: OutputFraming;
export const FRAMING_PRESETS: readonly { value: FramingPreset; label: string }[];
export function uprightSourceDimensions(status: { encodedWidth?: number; encodedHeight?: number; activeStreamMode?: string; streamMode?: string; rotationDegrees?: number } | null, settings: { width: number; height: number; streamMode: string }): { width: number; height: number };
export function normalizeCrop(crop: unknown): CropRect;
export function normalizeFraming(value: unknown): OutputFraming;
export function centeredCrop(sourceWidth: number, sourceHeight: number, aspect: number): CropRect;
export function resolveFraming(value: OutputFraming, sourceWidth: number, sourceHeight: number): OutputFraming;
export function containRect(boxWidth: number, boxHeight: number, sourceWidth: number, sourceHeight: number): CropRect;
export function moveCrop(crop: CropRect, dx: number, dy: number): CropRect;
export function resizeCrop(crop: CropRect, corner: string, dx: number, dy: number): CropRect;
