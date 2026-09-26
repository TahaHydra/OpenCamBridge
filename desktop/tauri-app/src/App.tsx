import { useEffect, useState } from 'react';
import { AlertTriangle, Monitor } from 'lucide-react';
import ConnectScreen, { connectUsb } from './components/connect/ConnectScreen';
import Studio from './components/shell/Studio';
import { apiFetch } from './services/api';
import { desktopInvoke as invoke, isTauriRuntime } from './services/desktopBridge';
import { startSession, logEvent } from './services/logging';
import { getPreferences, rememberDevice, usePreference } from './state/preferences';
import type { AdbDevice, ConnectionInfo } from './state/types';
import './App.css';

interface Session {
  baseUrl: string;
  token: string;
  connection: ConnectionInfo;
}

function DesktopApp() {
  const [session, setSession] = useState<Session | null>(null);
  const [switchError, setSwitchError] = useState('');

  const connected = (baseUrl: string, token: string, connection: ConnectionInfo) => {
    rememberDevice({
      id: connection.mode === 'usb' ? connection.serial || 'usb' : baseUrl,
      mode: connection.mode,
      name: connection.model || '',
      serial: connection.serial,
      url: connection.mode === 'lan' ? baseUrl : undefined,
      port: connection.port,
    });
    setSwitchError('');
    setSession({ baseUrl, token, connection: connection.mode === 'lan' ? { ...connection, url: baseUrl } : connection });
  };

  // Start a persistent session log on connect, and record device + capability
  // info once so a sent-in log file is self-describing.
  useEffect(() => {
    if (!session) return;
    const { baseUrl, token } = session;
    (async () => {
      await startSession({ transport: token ? 'LAN (token)' : 'USB', baseUrl });
      try {
        const info = await (await apiFetch(baseUrl, '/api/device/info', token)).json();
        logEvent('device', JSON.stringify(info));
        // The phone reports its real make and model; prefer that name.
        const name = `${info?.manufacturer || ''} ${info?.model || ''}`.trim();
        if (name) {
          const connection = session.connection;
          rememberDevice({
            id: connection.mode === 'usb' ? connection.serial || 'usb' : baseUrl,
            mode: connection.mode,
            name,
            serial: connection.serial,
            url: connection.mode === 'lan' ? baseUrl : undefined,
            port: connection.port,
          });
        }
      } catch { /* device info is best-effort */ }
      try {
        const cams = await (await apiFetch(baseUrl, '/api/camera/list', token)).json();
        const list = Array.isArray(cams) ? cams : cams.cameras || [];
        for (const c of list) {
          logEvent('capability', `lens ${c.id} "${c.label}" facing=${c.facing} lensType=${c.lensType || '?'} mono=${!!c.isMonochrome} torch=${c.hasTorch} focal=${JSON.stringify(c.focalLengths || [])} maxFps=${JSON.stringify(c.fpsByResolution || [])} highSpeed=${c.supportsHighSpeed ? JSON.stringify(c.highSpeedFpsRanges || []) : 'no'}`);
        }
      } catch { /* capabilities best-effort */ }
    })();
  }, [session]);

  // An ADB forward is removed when a USB device disappears. Re-apply the
  // exact selected-device forward while connected so the existing producer
  // HTTP retry loop can resume after a cable cycle without restarting either
  // desktop process.
  useEffect(() => {
    const connectionInfo = session?.connection;
    if (!connectionInfo || connectionInfo.mode !== 'usb' || !connectionInfo.serial || !connectionInfo.port) return;

    let active = true;
    let inFlight = false;
    let forwardHealthy = true;
    const repairForward = async () => {
      if (!active || inFlight) return;
      inFlight = true;
      try {
        await invoke<string>('forward_port', {
          port: connectionInfo.port,
          serial: connectionInfo.serial,
        });
        if (!forwardHealthy) logEvent('usb', `ADB forward restored for ${connectionInfo.serial}`);
        forwardHealthy = true;
      } catch (error) {
        if (forwardHealthy) logEvent('usb', `ADB device unavailable; waiting to restore forward: ${String(error)}`);
        forwardHealthy = false;
      } finally {
        inFlight = false;
      }
    };

    const timer = window.setInterval(() => { void repairForward(); }, 3000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [session]);

  const switchDevice = async (device: AdbDevice) => {
    const port = session?.connection.port || getPreferences().usbPort;
    setSession(null);
    try {
      const { url, connection } = await connectUsb(port, device.serial);
      connected(url, '', { ...connection, model: device.model?.replace(/_/g, ' ') });
    } catch (error: any) {
      setSwitchError(`Could not connect to ${device.model?.replace(/_/g, ' ') || device.serial}: ${error?.message || error}`);
    }
  };

  if (!session) {
    return (
      <>
        {switchError && <div className="app-banner" role="alert"><AlertTriangle size={14} /> {switchError}</div>}
        <ConnectScreen onConnect={connected} />
      </>
    );
  }

  return (
    <Studio
      // A different phone gets a fresh controller, never the previous one's state.
      key={`${session.baseUrl}|${session.connection.serial || ''}|${session.token ? 'lan' : 'usb'}`}
      baseUrl={session.baseUrl}
      token={session.token}
      connection={session.connection}
      onDisconnect={() => setSession(null)}
      onSwitchDevice={device => void switchDevice(device)}
    />
  );
}

function ThemeRoot({ children }: { children: React.ReactNode }) {
  const [theme] = usePreference('theme');
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  return <>{children}</>;
}

export default function App() {
  if (!isTauriRuntime()) {
    return (
      <div className="browser-unsupported">
        <div className="browser-unsupported__card">
          <Monitor size={28} />
          <h1>OpenCamBridge</h1>
          <p>Desktop features are unavailable in a web browser. Open OpenCamBridge through the desktop application.</p>
          <p className="browser-unsupported__detail">
            <AlertTriangle size={14} /> Tauri bridge unavailable — this page is not running inside the desktop app.
          </p>
        </div>
      </div>
    );
  }
  return (
    <ThemeRoot>
      <DesktopApp />
    </ThemeRoot>
  );
}
