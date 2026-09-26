import { useEffect, useState } from 'react';
import { getTauriVersion, getVersion } from '@tauri-apps/api/app';
import { openUrl } from '@tauri-apps/plugin-opener';
import { isTauriRuntime } from '../services/desktopBridge';

export const REPOSITORY_URL = 'https://github.com/TahaHydra/OpenCamBridge';
export const RELEASES_URL = `${REPOSITORY_URL}/releases`;
export const ISSUES_URL = `${REPOSITORY_URL}/issues`;
export const LICENSE_URL = `${REPOSITORY_URL}/blob/main/LICENSE`;
export const THIRD_PARTY_URL = `${REPOSITORY_URL}/blob/main/LICENSES.md`;

export interface AppInfo {
  version: string;
  tauriVersion: string;
}

/** Version strings for Updates / About. Falls back gracefully outside Tauri. */
export function useAppInfo(): AppInfo {
  const [info, setInfo] = useState<AppInfo>({ version: '', tauriVersion: '' });
  useEffect(() => {
    if (!isTauriRuntime()) return;
    let active = true;
    Promise.all([getVersion().catch(() => ''), getTauriVersion().catch(() => '')])
      .then(([version, tauriVersion]) => { if (active) setInfo({ version, tauriVersion }); });
    return () => { active = false; };
  }, []);
  return info;
}

/** Opens a link in the default browser (never inside the app window). */
export async function openExternal(url: string): Promise<void> {
  try {
    if (isTauriRuntime()) {
      await openUrl(url);
      return;
    }
  } catch {
    /* fall through */
  }
  window.open(url, '_blank', 'noopener');
}
