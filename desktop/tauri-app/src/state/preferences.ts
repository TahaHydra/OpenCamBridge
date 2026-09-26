import { useCallback, useSyncExternalStore } from 'react';
import type { ConnectMode } from './types';

/**
 * Desktop-only preferences, persisted in the WebView's localStorage.
 *
 * Nothing here is a camera setting: resolution, codec, lens and so on are owned
 * by the phone and synchronised through its revisioned settings API. These are
 * the things that only affect this PC — how the window looks, how the preview
 * is framed locally, OBS details and remembered phones.
 */

export type ObsSetupMode = 'camera' | 'browser' | 'window';

export interface KnownDevice {
  /** ADB serial for USB phones, the base URL for Wi-Fi phones. */
  id: string;
  mode: ConnectMode;
  name: string;
  serial?: string;
  url?: string;
  port?: number;
  lastConnected: number;
}

export interface CustomProfile {
  id: string;
  name: string;
  width: number;
  height: number;
  fps: number;
  streamMode: 'h264' | 'mjpeg';
  /** Missing on profiles saved before bitrate modes: those pinned a bitrate. */
  h264BitrateMode?: 'auto' | 'manual';
  h264BitrateMbps?: number;
  jpegQuality?: number;
}

export interface AppPreferences {
  /** Reveals engineering telemetry and expert controls throughout the UI. */
  advancedMode: boolean;
  theme: 'dark' | 'black';
  /** Local preview framing only; never changes what apps receive. */
  fitMode: 'fit' | 'fill';
  /** Flips only this window's preview, like looking in a mirror. */
  mirrorPreview: boolean;
  /** Desktop preview decoding. Off keeps the window out of the frame budget. */
  previewEnabled: boolean;
  /** USB only: reconnect to the last phone when the app opens. */
  autoReconnect: boolean;
  /** Start OpenCamBridge Camera as soon as a phone connects. */
  autoStartVirtualCamera: boolean;
  /** Profile applied right after connecting; '' keeps the phone's own settings. */
  defaultProfileId: string;
  usbPort: number;
  lanUrl: string;
  obsUrl: string;
  obsPassword: string;
  obsSetupMode: ObsSetupMode;
  knownDevices: KnownDevice[];
  preferredDeviceId: string;
  customProfiles: CustomProfile[];
  lastDeviceId: string;
}

const STORAGE_KEY = 'ocb.preferences.v1';

export const DEFAULT_PREFERENCES: AppPreferences = {
  advancedMode: false,
  theme: 'dark',
  fitMode: 'fit',
  mirrorPreview: false,
  previewEnabled: true,
  autoReconnect: false,
  autoStartVirtualCamera: false,
  defaultProfileId: '',
  usbPort: 8080,
  lanUrl: 'http://192.168.1.10:8080',
  obsUrl: 'ws://127.0.0.1:4455',
  obsPassword: '',
  obsSetupMode: 'camera',
  knownDevices: [],
  preferredDeviceId: '',
  customProfiles: [],
  lastDeviceId: '',
};

function readStorage(): AppPreferences {
  let stored: Partial<AppPreferences> = {};
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) stored = JSON.parse(raw) ?? {};
  } catch {
    stored = {};
  }
  const merged: AppPreferences = { ...DEFAULT_PREFERENCES, ...stored };
  // The previous UI kept its developer toggle under its own key. Carry an
  // enabled toggle over once so nobody loses the telemetry they had turned on.
  try {
    if (!('advancedMode' in stored) && localStorage.getItem('ocb.devMode') === '1') merged.advancedMode = true;
  } catch {
    /* storage unavailable */
  }
  if (!Array.isArray(merged.knownDevices)) merged.knownDevices = [];
  if (!Array.isArray(merged.customProfiles)) merged.customProfiles = [];
  return merged;
}

let current: AppPreferences = typeof window === 'undefined' ? DEFAULT_PREFERENCES : readStorage();
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getPreferences(): AppPreferences {
  return current;
}

export function setPreferences(
  patch: Partial<AppPreferences> | ((previous: AppPreferences) => Partial<AppPreferences>),
): void {
  const delta = typeof patch === 'function' ? patch(current) : patch;
  current = { ...current, ...delta };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(current));
  } catch {
    /* a full or unavailable store must never break the UI */
  }
  listeners.forEach(listener => listener());
}

export function resetPreferences(): void {
  const keep = { knownDevices: current.knownDevices, customProfiles: current.customProfiles };
  current = { ...DEFAULT_PREFERENCES, ...keep };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(current));
    localStorage.removeItem('ocb.devMode');
  } catch {
    /* ignore */
  }
  listeners.forEach(listener => listener());
}

export function usePreferences(): AppPreferences {
  return useSyncExternalStore(subscribe, getPreferences, getPreferences);
}

export function usePreference<K extends keyof AppPreferences>(
  key: K,
): [AppPreferences[K], (value: AppPreferences[K]) => void] {
  const value = useSyncExternalStore(subscribe, () => current[key], () => current[key]);
  const update = useCallback((next: AppPreferences[K]) => setPreferences({ [key]: next } as Partial<AppPreferences>), [key]);
  return [value, update];
}

/** Records a successful connection so the phone can be picked again later. */
export function rememberDevice(device: Omit<KnownDevice, 'lastConnected'>): void {
  setPreferences(previous => {
    const existing = previous.knownDevices.find(known => known.id === device.id);
    const entry: KnownDevice = {
      ...existing,
      ...device,
      // A later connection without a model string must not erase a good name.
      name: device.name || existing?.name || 'Android phone',
      lastConnected: Date.now(),
    };
    return {
      knownDevices: [entry, ...previous.knownDevices.filter(known => known.id !== device.id)].slice(0, 12),
      lastDeviceId: device.id,
    };
  });
}

export function forgetDevice(id: string): void {
  setPreferences(previous => ({
    knownDevices: previous.knownDevices.filter(device => device.id !== id),
    preferredDeviceId: previous.preferredDeviceId === id ? '' : previous.preferredDeviceId,
    lastDeviceId: previous.lastDeviceId === id ? '' : previous.lastDeviceId,
  }));
}
