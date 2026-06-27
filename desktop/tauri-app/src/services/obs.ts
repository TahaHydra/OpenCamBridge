import OBSWebSocket from 'obs-websocket-js';

const obs = new OBSWebSocket();

export interface ObsStatus {
  connected: boolean;
  message: string;
  error?: string;
}

export async function connectAndSetupObs(password: string, browserUrl: string, obsMode: 'browser' | 'window', onStatus: (status: ObsStatus) => void): Promise<boolean> {
  try {
    onStatus({ connected: false, message: 'Connecting to OBS...' });
    
    try {
      await obs.connect('ws://127.0.0.1:4455', password || undefined);
    } catch (err: any) {
      if (err.message && err.message.toLowerCase().includes('authentication')) {
        onStatus({ connected: false, message: 'Authentication failed', error: 'OBS WebSocket requires a password. Enter it in OpenCamBridge settings.' });
        return false;
      }
      onStatus({ connected: false, message: 'Connection failed', error: 'OBS is not running. Open OBS Studio and enable WebSocket Server on port 4455.' });
      return false;
    }

    onStatus({ connected: true, message: 'OBS connected. Setting up source...' });

    // 1. Scene Management
    const sceneName = 'OpenCamBridge';
    const { scenes } = await obs.call('GetSceneList');
    const sceneExists = scenes.some((s: any) => s.sceneName === sceneName);
    
    if (!sceneExists) {
      await obs.call('CreateScene', { sceneName });
    }
    
    await obs.call('SetCurrentProgramScene', { sceneName });

    // 2. Source Management
    let windowCaptureKind = 'window_capture';
    try {
      const res = await obs.call('GetInputKindList');
      const inputKinds = res.inputKinds as string[];
      console.log('Available OBS Input Kinds:', inputKinds);
      
      if (inputKinds.includes('window_capture_wgc')) {
        windowCaptureKind = 'window_capture_wgc';
      } else if (inputKinds.includes('window_capture')) {
        windowCaptureKind = 'window_capture';
      } else {
        const possible = inputKinds.find(k => k.includes('window_capture'));
        if (possible) windowCaptureKind = possible;
      }
      console.log('Selected Window Capture Kind:', windowCaptureKind);
    } catch (e) {
      console.warn('Failed to query input kinds', e);
    }

    let sourceName = '';
    let inputKind = '';
    let inputSettings: any = {};

    if (obsMode === 'browser') {
      sourceName = 'OpenCamBridge Browser Feed';
      inputKind = 'browser_source';
      inputSettings = {
        url: browserUrl,
        width: 1920,
        height: 1080,
        fps: 30,
        shutdown: false,
        reroute_audio: false,
        restart_when_active: true
      };
    } else {
      sourceName = 'OpenCamBridge Window Capture';
      inputKind = windowCaptureKind;
      inputSettings = {
        window: 'OpenCamBridge:*:tauri-app.exe',
        window_match_priority: 2,
        client_area: false,
        method: 2 // WGC (Windows Graphics Capture) usually handles WebView2 best
      };
    }
    
    // Check if source already exists
    let sourceExists = false;
    try {
      const { sceneItems } = await obs.call('GetSceneItemList', { sceneName });
      sourceExists = sceneItems.some((i: any) => i.sourceName === sourceName);
    } catch (e) {
      // Ignore
    }

    if (!sourceExists) {
      try {
        await obs.call('CreateInput', {
          sceneName,
          inputName: sourceName,
          inputKind: inputKind,
          inputSettings: inputSettings
        });
      } catch (err: any) {
        console.warn('Could not create source (it might exist globally). Trying to reuse it.');
        await obs.call('SetInputSettings', {
          inputName: sourceName,
          inputSettings: inputSettings
        });
      }
    } else {
      // Source exists, update settings just in case
      await obs.call('SetInputSettings', {
        inputName: sourceName,
        inputSettings: inputSettings
      });
    }

    onStatus({ connected: true, message: 'Source created/updated. Starting virtual camera...' });

    // 3. Virtual Camera Management
    const { outputActive } = await obs.call('GetVirtualCamStatus');
    
    if (outputActive) {
      onStatus({ connected: true, message: 'OBS Virtual Camera already running' });
    } else {
      await obs.call('StartVirtualCam');
      onStatus({ connected: true, message: 'OBS Virtual Camera started successfully!' });
    }

    return true;

  } catch (err: any) {
    console.error('OBS Automation Error:', err);
    onStatus({ connected: true, message: 'Error during OBS setup', error: err.message || 'Unknown error' });
    return false;
  }
}

export async function disconnectObs() {
  try {
    await obs.disconnect();
  } catch (e) {
    // Ignore
  }
}
