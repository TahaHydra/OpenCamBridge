import OBSWebSocket from 'obs-websocket-js';

const obs = new OBSWebSocket();

export interface ObsStatus {
  connected: boolean;
  message: string;
  error?: string;
}

/** Name Windows (and therefore OBS) shows for the OpenCamBridge virtual camera. */
export const VIRTUAL_CAMERA_NAME = 'OpenCamBridge Camera';
export const DEFAULT_OBS_URL = 'ws://127.0.0.1:4455';
const SCENE_NAME = 'OpenCamBridge';

export type ObsSetupMode = 'camera' | 'browser' | 'window';

export interface ObsState {
  phase: 'disconnected' | 'connecting' | 'connected' | 'working' | 'error';
  message: string;
  error?: string;
  obsVersion?: string;
  /** Set once a source has been created or updated in this session. */
  sourceReady?: boolean;
}

let state: ObsState = { phase: 'disconnected', message: 'Not connected' };
const listeners = new Set<(next: ObsState) => void>();

function publish(patch: Partial<ObsState>, replace = false): void {
  state = replace ? { phase: 'disconnected', message: '', ...patch } : { ...state, ...patch };
  listeners.forEach(listener => listener(state));
}

obs.on('ConnectionClosed', () => {
  if (state.phase !== 'error') publish({ phase: 'disconnected', message: 'Not connected', obsVersion: undefined }, true);
});

export function getObsState(): ObsState {
  return state;
}

export function subscribeObs(listener: (next: ObsState) => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function describeConnectError(err: any): { message: string; error: string } {
  const text = String(err?.message || err || '').toLowerCase();
  if (text.includes('authentication')) {
    return {
      message: 'Password required',
      error: 'OBS WebSocket asked for a password. Enter it in Settings › Integrations.',
    };
  }
  return {
    message: 'OBS not found',
    error: 'Open OBS Studio and enable Tools › WebSocket Server Settings › Enable WebSocket server.',
  };
}

/** Connects (or reuses a live connection). Resolves true when identified. */
export async function connectObs(url: string, password: string): Promise<boolean> {
  if (state.phase === 'connected' || state.phase === 'working') return true;
  publish({ phase: 'connecting', message: 'Connecting to OBS…', error: undefined });
  try {
    await obs.connect(url || DEFAULT_OBS_URL, password || undefined);
  } catch (err: any) {
    publish({ phase: 'error', ...describeConnectError(err) });
    return false;
  }
  let obsVersion: string | undefined;
  try {
    const version = await obs.call('GetVersion');
    obsVersion = version.obsVersion;
  } catch {
    /* version is informational */
  }
  publish({ phase: 'connected', message: 'Connected to OBS', error: undefined, obsVersion });
  return true;
}

async function ensureScene(): Promise<void> {
  const { scenes } = await obs.call('GetSceneList');
  if (!scenes.some((s: any) => s.sceneName === SCENE_NAME)) {
    await obs.call('CreateScene', { sceneName: SCENE_NAME });
  }
  await obs.call('SetCurrentProgramScene', { sceneName: SCENE_NAME });
}

async function sceneHasSource(sourceName: string): Promise<boolean> {
  try {
    const { sceneItems } = await obs.call('GetSceneItemList', { sceneName: SCENE_NAME });
    return sceneItems.some((item: any) => item.sourceName === sourceName);
  } catch {
    return false;
  }
}

async function createOrUpdateInput(sourceName: string, inputKind: string, inputSettings: any): Promise<void> {
  if (await sceneHasSource(sourceName)) {
    await obs.call('SetInputSettings', { inputName: sourceName, inputSettings });
    return;
  }
  try {
    await obs.call('CreateInput', { sceneName: SCENE_NAME, inputName: sourceName, inputKind, inputSettings });
  } catch {
    // The input may exist globally without being in this scene: reuse it.
    await obs.call('SetInputSettings', { inputName: sourceName, inputSettings });
    try {
      await obs.call('CreateSceneItem', { sceneName: SCENE_NAME, sourceName });
    } catch {
      /* already present */
    }
  }
}

/**
 * Adds OpenCamBridge Camera to OBS as a Video Capture Device — the native,
 * full-quality path (hardware H.264 decoded once, NV12 into OBS).
 */
async function setupCameraSource(): Promise<void> {
  const sourceName = VIRTUAL_CAMERA_NAME;
  await createOrUpdateInput(sourceName, 'dshow_input', {});
  const { propertyItems } = await obs.call('GetInputPropertiesListPropertyItems', {
    inputName: sourceName,
    propertyName: 'video_device_id',
  });
  const device = (propertyItems as any[]).find(item =>
    String(item.itemName || '').toLowerCase().includes('opencambridge'));
  if (!device) {
    throw new Error(`${VIRTUAL_CAMERA_NAME} is not visible to OBS. Start the virtual camera first, then try again.`);
  }
  await obs.call('SetInputSettings', {
    inputName: sourceName,
    inputSettings: { video_device_id: device.itemValue },
  });
}

async function windowCaptureKind(): Promise<string> {
  try {
    const { inputKinds } = await obs.call('GetInputKindList');
    const kinds = inputKinds as string[];
    if (kinds.includes('window_capture_wgc')) return 'window_capture_wgc';
    if (kinds.includes('window_capture')) return 'window_capture';
    return kinds.find(kind => kind.includes('window_capture')) || 'window_capture';
  } catch {
    return 'window_capture';
  }
}

export interface ObsSetupOptions {
  /** The phone's /obs page, used only by the browser-source fallback. */
  browserUrl: string;
  /** Fallback modes can also start OBS's own virtual camera, as before. */
  startObsVirtualCamera?: boolean;
}

/**
 * Creates the OpenCamBridge scene and source for the chosen method.
 *  - camera:  Video Capture Device reading OpenCamBridge Camera (recommended)
 *  - browser: Browser Source on the phone's /obs page (fallback, MJPEG)
 *  - window:  Window Capture of the clean feed window (fallback)
 */
export async function setupObsSource(mode: ObsSetupMode, options: ObsSetupOptions): Promise<boolean> {
  if (state.phase !== 'connected' && state.phase !== 'working') {
    publish({ phase: 'error', message: 'Not connected', error: 'Connect to OBS first.' });
    return false;
  }
  publish({ phase: 'working', message: 'Setting up the OpenCamBridge scene…', error: undefined });
  try {
    await ensureScene();
    if (mode === 'camera') {
      await setupCameraSource();
    } else if (mode === 'browser') {
      await createOrUpdateInput('OpenCamBridge Browser Feed', 'browser_source', {
        url: options.browserUrl,
        width: 1920,
        height: 1080,
        fps: 30,
        shutdown: false,
        reroute_audio: false,
        restart_when_active: true,
      });
    } else {
      await createOrUpdateInput('OpenCamBridge Window Capture', await windowCaptureKind(), {
        window: 'OpenCamBridge:*:tauri-app.exe',
        window_match_priority: 2,
        client_area: false,
        method: 2, // WGC (Windows Graphics Capture) usually handles WebView2 best
      });
    }
    if (mode !== 'camera' && options.startObsVirtualCamera) {
      const { outputActive } = await obs.call('GetVirtualCamStatus');
      if (!outputActive) await obs.call('StartVirtualCam');
    }
    publish({
      phase: 'connected',
      message: mode === 'camera' ? `${VIRTUAL_CAMERA_NAME} added to OBS` : 'OBS scene ready',
      sourceReady: true,
      error: undefined,
    });
    return true;
  } catch (err: any) {
    console.error('OBS Automation Error:', err);
    publish({ phase: 'error', message: 'OBS setup failed', error: err?.message || 'Unknown error' });
    return false;
  }
}

export async function disconnectObs() {
  try {
    await obs.disconnect();
  } catch (e) {
    // Ignore
  }
  publish({ phase: 'disconnected', message: 'Not connected' }, true);
}

/**
 * The original one-shot automation (connect, browser/window source, start
 * OBS's own virtual camera). Kept for compatibility with existing callers.
 */
export async function connectAndSetupObs(password: string, browserUrl: string, obsMode: 'browser' | 'window', onStatus: (status: ObsStatus) => void): Promise<boolean> {
  const forward = (next: ObsState) => onStatus({
    connected: next.phase === 'connected' || next.phase === 'working',
    message: next.message,
    error: next.error,
  });
  const unsubscribe = subscribeObs(forward);
  try {
    if (!(await connectObs(DEFAULT_OBS_URL, password))) return false;
    return await setupObsSource(obsMode, { browserUrl, startObsVirtualCamera: true });
  } finally {
    unsubscribe();
  }
}
