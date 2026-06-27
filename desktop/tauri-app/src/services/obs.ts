import OBSWebSocket from 'obs-websocket-js';

const obs = new OBSWebSocket();

export interface ObsStatus {
  connected: boolean;
  message: string;
  error?: string;
}

export async function connectAndSetupObs(password: string, onStatus: (status: ObsStatus) => void): Promise<boolean> {
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
    const sourceName = 'OpenCamBridge Clean Feed';
    
    // Check if source already exists
    let sourceExists = false;
    try {
      const { sceneItems } = await obs.call('GetSceneItemList', { sceneName });
      sourceExists = sceneItems.some((i: any) => i.sourceName === sourceName);
    } catch (e) {
      // Ignore
    }

    const windowSettings = {
      window: 'OpenCamBridge:*:tauri-app.exe',
      window_match_priority: 2, // 2 = Match title, otherwise find window of same executable
      client_area: true
    };

    if (!sourceExists) {
      try {
        await obs.call('CreateInput', {
          sceneName,
          inputName: sourceName,
          inputKind: 'window_capture',
          inputSettings: windowSettings
        });
      } catch (err: any) {
        console.warn('Could not create source (it might exist globally). Trying to reuse it.');
        // If it exists globally but not in scene, we would add a scene item.
        // For simplicity, we just try to update settings.
        await obs.call('SetInputSettings', {
          inputName: sourceName,
          inputSettings: windowSettings
        });
      }
    } else {
      // Source exists, update settings just in case
      await obs.call('SetInputSettings', {
        inputName: sourceName,
        inputSettings: windowSettings
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
