import { useSyncExternalStore } from 'react';
import { getVersion } from '@tauri-apps/api/app';
import { desktopInvoke, isTauriRuntime } from '../services/desktopBridge';
import { newerRelease, stableVersion } from '../services/releasePolicy.js';

type UpdateState = { checking: boolean; available: boolean; latest: string; url: string; message: string };
let state: UpdateState = { checking: false, available: false, latest: '', url: '', message: 'Updates are checked only when requested or when you enable startup checks.' };
const listeners = new Set<() => void>();
let inFlight: Promise<void> | null = null;
function publish(next: Partial<UpdateState>) { state = { ...state, ...next }; listeners.forEach(fn => fn()); }
export function useUpdateState() { return useSyncExternalStore(fn => { listeners.add(fn); return () => listeners.delete(fn); }, () => state); }
export function checkForUpdates(): Promise<void> {
  if (inFlight) return inFlight;
  if (!isTauriRuntime()) return Promise.resolve();
  publish({ checking: true });
  inFlight = (async () => {
    try {
      const current = await getVersion();
      const release = await desktopInvoke<{ version: string; release_url: string } | null>('check_desktop_update');
      if (!release) { publish({ available: false, latest: '', message: 'No stable release has been published on GitHub yet.' }); return; }
      if (!stableVersion(release.version)) throw new Error('The latest release has an unsupported version tag.');
      const available = newerRelease(current, release.version);
      publish({ available, latest: release.version, url: release.release_url, message: available ? `Version ${release.version} is available.` : 'You are using the latest published version.' });
    } catch (error) { publish({ message: String(error) }); }
    finally { publish({ checking: false }); inFlight = null; }
  })();
  return inFlight;
}
