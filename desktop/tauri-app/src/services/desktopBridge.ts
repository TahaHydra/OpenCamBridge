import { invoke as tauriInvoke } from '@tauri-apps/api/core';

type InvokeArguments = Record<string, unknown>;

type TauriRuntimeWindow = Window & {
  __TAURI_INTERNALS__?: {
    invoke?: unknown;
  };
};

export class BrowserUnsupportedError extends Error {
  constructor() {
    super('Tauri bridge unavailable — this page is not running inside the desktop app.');
    this.name = 'BrowserUnsupportedError';
  }
}

export function isTauriRuntime(): boolean {
  if (typeof window === 'undefined') return false;
  const internals = (window as TauriRuntimeWindow).__TAURI_INTERNALS__;
  return typeof internals?.invoke === 'function';
}

/**
 * The only frontend entry point to native Tauri commands.
 *
 * The package-level invoke helper assumes `window.__TAURI_INTERNALS__` exists.
 * A normal Vite page does not have that injected object, so guard it here and
 * surface a useful typed error instead of dereferencing `undefined`.
 */
export async function desktopInvoke<T>(
  command: string,
  args?: InvokeArguments,
): Promise<T> {
  if (!isTauriRuntime()) throw new BrowserUnsupportedError();
  return tauriInvoke<T>(command, args);
}
