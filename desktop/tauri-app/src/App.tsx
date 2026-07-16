import { useState, useEffect } from 'react';
import { Camera, Unplug, Zap, Monitor, Usb, Wifi, ShieldCheck, FileText } from 'lucide-react';
import { invoke } from '@tauri-apps/api/core';
import Preview from './components/Preview';
import ControlPanel from './components/ControlPanel';
import LogsView from './components/LogsView';
import { apiFetch } from './services/api';
import { startSession, logEvent } from './services/logging';
import './App.css';

interface ConnectionScreenProps {
  onConnect: (url: string, token: string, connection: ConnectionInfo) => void;
}

type ConnectMode = 'usb' | 'lan';
type AdbDevice = { serial: string; state: string; model?: string };
type ConnectionInfo = {
  mode: ConnectMode;
  port?: number;
  serial?: string;
};

function ConnectionScreen({ onConnect }: ConnectionScreenProps) {
  const [mode, setMode] = useState<ConnectMode>('usb');
  const [port, setPort] = useState('8080');
  const [lanUrl, setLanUrl] = useState('http://192.168.1.10:8080');
  const [lanToken, setLanToken] = useState('');
  const [isConnecting, setIsConnecting] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [adbDevices, setAdbDevices] = useState<AdbDevice[]>([]);
  const [selectedSerial, setSelectedSerial] = useState('');

  const refreshAdbDevices = async () => {
    try {
      const devices = await invoke<AdbDevice[]>('list_devices');
      setAdbDevices(devices);
      setSelectedSerial(current => devices.some(d => d.serial === current)
        ? current
        : devices.length === 1 ? devices[0].serial : '');
    } catch {
      setAdbDevices([]);
      setSelectedSerial('');
    }
  };

  useEffect(() => { if (mode === 'usb') void refreshAdbDevices(); }, [mode]);

  const verifyAndConnect = async (url: string, token: string, connection: ConnectionInfo) => {
    const formattedUrl = url.endsWith('/') ? url.slice(0, -1) : url;

    // 1. Reachability (no token needed for /health by design)
    const healthRes = await fetch(`${formattedUrl}/health`);
    if (!healthRes.ok) throw new Error('Health check failed');
    const text = await healthRes.text();
    if (!(text === 'OK' || text.toLowerCase().includes('ok'))) {
      throw new Error('Invalid health response');
    }

    // 2. Authorization (LAN mode requires the token for everything else)
    const statusRes = await apiFetch(formattedUrl, '/api/camera/status', token);
    if (statusRes.status === 401) {
      throw new Error('UNAUTHORIZED');
    }
    if (!statusRes.ok) throw new Error('Status check failed');

    onConnect(formattedUrl, token, connection);
  };

  const handleUsbConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsConnecting(true);
    setError('');
    setInfo('');
    const p = parseInt(port, 10) || 8080;
    try {
      const liveDevices = await invoke<AdbDevice[]>('list_devices').catch(() => adbDevices);
      setAdbDevices(liveDevices);
      const selectedIsLive = liveDevices.some(device => device.serial === selectedSerial);
      if (liveDevices.length > 1 && !selectedIsLive) {
        setError('Several ADB devices are connected. Select the phone to use.');
        return;
      }
      const effectiveSerial = liveDevices.length === 1 ? liveDevices[0].serial : selectedSerial;
      // Best-effort adb forward. If adb is missing we still try to connect:
      // the user may have set the forward up manually.
      try {
        setInfo('Setting up adb port forwarding...');
        await invoke<string>('forward_port', { port: p, serial: effectiveSerial || undefined });
        setInfo('adb forward active. Connecting...');
      } catch (adbErr: any) {
        const adbMessage = String(adbErr);
        if (/Several ADB devices|Selected ADB device/.test(adbMessage)) {
          setError(adbMessage);
          return;
        }
        setInfo(`adb not available (${adbMessage.slice(0, 120)}). Trying direct connection...`);
      }
      await verifyAndConnect(`http://127.0.0.1:${p}`, '', {
        mode: 'usb',
        port: p,
        serial: effectiveSerial || undefined,
      });
    } catch (err: any) {
      if (String(err?.message) === 'UNAUTHORIZED') {
        setError('The phone rejected the request (401). Is the phone set to LAN mode? For USB, set Access Mode to "USB Only" on the phone.');
      } else {
        setError('Connection failed. Check: phone connected via USB, USB debugging enabled, OpenCamBridge app running on the phone.');
      }
    } finally {
      setIsConnecting(false);
    }
  };

  const handleLanConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsConnecting(true);
    setError('');
    setInfo('');
    try {
      await verifyAndConnect(lanUrl, lanToken.trim(), { mode: 'lan' });
    } catch (err: any) {
      if (String(err?.message) === 'UNAUTHORIZED') {
        setError('Invalid or missing token. Copy the access token from the phone: OpenCamBridge app > Security tab.');
      } else {
        setError('Connection failed. Ensure the phone is on the same network, LAN mode is enabled, and the URL matches the one shown on the phone.');
      }
    } finally {
      setIsConnecting(false);
    }
  };

  const tabStyle = (active: boolean): React.CSSProperties => ({
    flex: 1,
    padding: '10px 12px',
    borderRadius: 8,
    border: active ? '1px solid var(--accent, #4dabf7)' : '1px solid rgba(255,255,255,0.1)',
    background: active ? 'rgba(77, 171, 247, 0.15)' : 'transparent',
    color: active ? '#4dabf7' : 'var(--text-secondary, #aaa)',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    fontWeight: 600,
    fontSize: '0.9rem',
  });

  return (
    <div className="connection-screen">
      <div className="connection-card glass-panel animate-fade">
        <div className="brand-hero">
          <Camera size={64} />
          <h1>OpenCamBridge</h1>
          <p>Use your Android phone as a webcam</p>
        </div>

        <div style={{ display: 'flex', gap: 8, width: '100%', marginBottom: 16 }}>
          <button type="button" style={tabStyle(mode === 'usb')} onClick={() => { setMode('usb'); setError(''); setInfo(''); }}>
            <Usb size={16} /> USB (Recommended)
          </button>
          <button type="button" style={tabStyle(mode === 'lan')} onClick={() => { setMode('lan'); setError(''); setInfo(''); }}>
            <Wifi size={16} /> Wi-Fi (LAN)
          </button>
        </div>

        {mode === 'usb' ? (
          <form onSubmit={handleUsbConnect} style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 16 }}>
            {error && <div className="error-banner">{error}</div>}
            {info && !error && <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>{info}</div>}

            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textAlign: 'left', margin: 0, lineHeight: 1.5 }}>
              Private and stable: video never leaves the USB cable. Connect the phone via USB,
              enable USB debugging, and keep the phone in <strong>USB Only</strong> access mode (the default).
            </p>

            <div style={{ textAlign: 'left' }}>
              <label style={{ display: 'block', marginBottom: 8, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>Port</label>
              <input
                type="text"
                className="input-control"
                value={port}
                onChange={(e) => setPort(e.target.value)}
                placeholder="8080"
                required
              />
            </div>

            {adbDevices.length > 0 && (
              <div style={{ textAlign: 'left' }}>
                <label style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                  <span>Android device{adbDevices.length > 1 ? ' (required)' : ''}</span>
                  <button type="button" onClick={refreshAdbDevices} style={{ border: 0, background: 'transparent', color: '#4dabf7', cursor: 'pointer' }}>Refresh</button>
                </label>
                <select className="input-control" value={selectedSerial} onChange={e => setSelectedSerial(e.target.value)} required={adbDevices.length > 1}>
                  {adbDevices.length > 1 && <option value="">Select a phone…</option>}
                  {adbDevices.map(device => <option key={device.serial} value={device.serial}>{device.model || 'Android device'} — {device.serial}</option>)}
                </select>
              </div>
            )}

            <button type="submit" className="btn btn-primary" style={{ width: '100%', padding: '14px', marginTop: 8 }} disabled={isConnecting}>
              {isConnecting ? <Zap size={18} /> : <Usb size={18} />}
              {isConnecting ? 'Connecting...' : 'Set up USB & Connect'}
            </button>
          </form>
        ) : (
          <form onSubmit={handleLanConnect} style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 16 }}>
            {error && <div className="error-banner">{error}</div>}

            <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', textAlign: 'left', margin: 0, lineHeight: 1.5 }}>
              On the phone: open OpenCamBridge &gt; Security, switch to <strong>LAN Token</strong> mode,
              then copy the URL and access token shown there.
            </p>

            <div style={{ textAlign: 'left' }}>
              <label style={{ display: 'block', marginBottom: 8, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>Phone URL</label>
              <input
                type="text"
                className="input-control"
                value={lanUrl}
                onChange={(e) => setLanUrl(e.target.value)}
                placeholder="http://192.168.1.10:8080"
                required
              />
            </div>

            <div style={{ textAlign: 'left' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 8, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                <ShieldCheck size={14} /> Access Token (required for LAN)
              </label>
              <input
                type="password"
                className="input-control"
                value={lanToken}
                onChange={(e) => setLanToken(e.target.value)}
                placeholder="Token from the phone's Security tab"
                required
              />
            </div>

            <button type="submit" className="btn btn-primary" style={{ width: '100%', padding: '14px', marginTop: 8 }} disabled={isConnecting}>
              {isConnecting ? <Zap size={18} /> : <Unplug size={18} />}
              {isConnecting ? 'Connecting...' : 'Connect over Wi-Fi'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}

export default function App() {
  const [baseUrl, setBaseUrl] = useState('');
  const [token, setToken] = useState('');
  const [isConnected, setIsConnected] = useState(false);
  const [connectionInfo, setConnectionInfo] = useState<ConnectionInfo | null>(null);
  const [serverStatus, setServerStatus] = useState<any>(null);
  const [fitMode, setFitMode] = useState('fill');
  const [obsMode, setObsMode] = useState(false);
  const [previewOff, setPreviewOff] = useState(false);
  const [showLogs, setShowLogs] = useState(false);

  // Start a persistent session log on connect, and record device + capability
  // info once so a sent-in log file is self-describing.
  useEffect(() => {
    if (!isConnected || !baseUrl) return;
    (async () => {
      await startSession({ transport: token ? 'LAN (token)' : 'USB', baseUrl });
      try {
        const info = await (await apiFetch(baseUrl, '/api/device/info', token)).json();
        logEvent('device', JSON.stringify(info));
      } catch { /* device info is best-effort */ }
      try {
        const cams = await (await apiFetch(baseUrl, '/api/camera/list', token)).json();
        const list = Array.isArray(cams) ? cams : cams.cameras || [];
        for (const c of list) {
          logEvent('capability', `lens ${c.id} "${c.label}" facing=${c.facing} lensType=${c.lensType || '?'} mono=${!!c.isMonochrome} torch=${c.hasTorch} focal=${JSON.stringify(c.focalLengths || [])} maxFps=${JSON.stringify(c.fpsByResolution || [])} highSpeed=${c.supportsHighSpeed ? JSON.stringify(c.highSpeedFpsRanges || []) : 'no'}`);
        }
      } catch { /* capabilities best-effort */ }
    })();
  }, [isConnected, baseUrl, token]);

  // An ADB forward is removed when a USB device disappears. Re-apply the
  // exact selected-device forward while connected so the existing producer
  // HTTP retry loop can resume after a cable cycle without restarting either
  // desktop process.
  useEffect(() => {
    if (!isConnected || connectionInfo?.mode !== 'usb' || !connectionInfo.serial || !connectionInfo.port) return;

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
  }, [isConnected, connectionInfo]);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setObsMode(false);
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // Polling loop for status
  useEffect(() => {
    if (!isConnected || !baseUrl) return;

    let active = true;
    const poll = async () => {
      try {
        const res = await apiFetch(baseUrl, '/api/camera/status', token);
        if (!res.ok) throw new Error('Offline');
        const data = await res.json();
        const status = data.status || data;
        if (active) setServerStatus(status);
      } catch (err) {
        if (active) {
          setServerStatus((prev: any) => ({ ...prev, lifecycleState: 'OFFLINE', lastError: 'Server disconnected' }));
        }
      }
      if (active) setTimeout(poll, 2000);
    };
    poll();
    return () => { active = false; };
  }, [isConnected, baseUrl, token]);

  if (!isConnected) {
    return <ConnectionScreen onConnect={(url, tok, connection) => {
      setBaseUrl(url);
      setToken(tok);
      setConnectionInfo(connection);
      setIsConnected(true);
    }} />;
  }

  const stateClass = serverStatus?.lifecycleState?.toLowerCase() || 'stopped';
  const isError = serverStatus?.lifecycleState === 'ERROR' || serverStatus?.lifecycleState === 'OFFLINE';
  const transportLabel = token ? 'LAN' : 'USB';

  if (obsMode) {
    return (
      <div style={{ width: '100vw', height: '100vh', margin: 0, padding: 0, overflow: 'hidden', background: '#000', position: 'relative' }}>
        <style>{`.preview-stage { border-radius: 0 !important; background: transparent !important; }`}</style>
        <Preview baseUrl={baseUrl} token={token} fitMode={fitMode} serverStatus={serverStatus} />
        <button
          onClick={() => setObsMode(false)}
          style={{ position: 'absolute', top: 16, right: 16, zIndex: 9999, background: 'rgba(0,0,0,0.7)', color: 'white', border: '1px solid #444', padding: '8px 16px', borderRadius: 6, cursor: 'pointer', fontFamily: 'inherit', display: 'flex', alignItems: 'center', gap: 8 }}
        >
          <Unplug size={16} /> Exit OBS Mode (Esc)
        </button>
      </div>
    );
  }

  return (
    <div className="app-container animate-fade">
      <header className="header">
        <div className="header-brand">
          <Camera size={24} /> OpenCamBridge
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <div className="status-badge" title={token ? 'Connected over Wi-Fi with token auth' : 'Connected over USB (adb forward)'}>
            {token ? <Wifi size={14} /> : <Usb size={14} />}
            <span style={{ textTransform: 'uppercase', letterSpacing: 1 }}>{transportLabel}</span>
          </div>
          <div className="status-badge">
            <div className={`status-dot ${stateClass}`}></div>
            <span style={{ textTransform: 'uppercase', letterSpacing: 1 }}>{serverStatus?.lifecycleState || 'UNKNOWN'}</span>
          </div>
          <button className="btn btn-secondary" onClick={() => setShowLogs(true)} style={{ padding: '6px 12px', fontSize: '0.8rem' }} title="Session logs">
            <FileText size={14} /> Logs
          </button>
          <button className="btn btn-secondary" onClick={() => { setIsConnected(false); setToken(''); setConnectionInfo(null); }} style={{ padding: '6px 12px', fontSize: '0.8rem' }}>
            <Unplug size={14} /> Disconnect
          </button>
        </div>
      </header>

      <main className="main-content">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, height: '100%', overflow: 'hidden' }}>
          {isError && serverStatus?.lastError && (
            <div className="error-banner animate-fade" style={{ margin: 0 }}>
              <strong>Camera Error:</strong> {serverStatus.lastError}
            </div>
          )}
          {!previewOff ? (
            <Preview baseUrl={baseUrl} token={token} fitMode={fitMode} serverStatus={serverStatus} />
          ) : (
            <div className="preview-stage glass-panel" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%' }}>
              <div style={{ textAlign: 'center', color: '#888' }}>
                <Monitor size={48} style={{ opacity: 0.5, marginBottom: 16 }} />
                <p>Preview Disabled for Performance Diagnostics</p>
                <p style={{ fontSize: '0.8rem', marginTop: 8 }}>Frames are still being captured and sent to the Virtual Camera.</p>
              </div>
            </div>
          )}
        </div>

        <ControlPanel baseUrl={baseUrl} token={token} fitMode={fitMode} setFitMode={setFitMode} onEnterObsMode={() => setObsMode(true)} previewOff={previewOff} setPreviewOff={setPreviewOff} />
      </main>

      {showLogs && <LogsView onClose={() => setShowLogs(false)} />}
    </div>
  );
}
