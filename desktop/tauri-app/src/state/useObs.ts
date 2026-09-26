import { useCallback, useSyncExternalStore } from 'react';
import {
  connectObs,
  disconnectObs,
  getObsState,
  setupObsSource,
  subscribeObs,
  type ObsSetupMode,
} from '../services/obs';
import { buildUrl } from '../services/api';
import { getPreferences, usePreference } from './preferences';

/**
 * OBS integration as seen by the UI: live WebSocket state plus the actions the
 * Outputs rail and Settings › Integrations need. Connection details are read
 * from preferences at call time, so a just-saved address is used immediately.
 */
export function useObs(options: { baseUrl: string; token: string; fitMode: 'fit' | 'fill'; onEnterCleanFeed: () => void }) {
  const { baseUrl, token, fitMode, onEnterCleanFeed } = options;
  const state = useSyncExternalStore(subscribeObs, getObsState, getObsState);
  const [mode] = usePreference('obsSetupMode');

  const connect = useCallback(() => {
    const prefs = getPreferences();
    return connectObs(prefs.obsUrl, prefs.obsPassword);
  }, []);

  const setup = useCallback(async (requested?: ObsSetupMode) => {
    const prefs = getPreferences();
    const method = requested ?? prefs.obsSetupMode;
    if (!(await connectObs(prefs.obsUrl, prefs.obsPassword))) return false;
    if (method === 'window') {
      // Window capture reads this app's window, so show the chrome-free feed
      // first and give it a moment to paint before OBS starts capturing.
      onEnterCleanFeed();
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    // The phone already rotates the /stream.mjpeg frames, so the /obs page must
    // not rotate again.
    const browserUrl = buildUrl(baseUrl, '/obs', token, { fit: fitMode === 'fill' ? 'cover' : 'contain' });
    return setupObsSource(method, { browserUrl, startObsVirtualCamera: method !== 'camera' });
  }, [baseUrl, token, fitMode, onEnterCleanFeed]);

  return { state, connect, setup, disconnect: disconnectObs, mode };
}

export type ObsIntegration = ReturnType<typeof useObs>;
