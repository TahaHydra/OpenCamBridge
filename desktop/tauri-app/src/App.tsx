import { useState, useEffect } from 'react';
import { Camera, Unplug, Zap, Monitor, Usb, Wifi, ShieldCheck, FileText, AlertTriangle } from 'lucide-react';
import Preview from './components/Preview';
import ControlPanel from './components/ControlPanel';
import LogsView from './components/LogsView';
import { Lamp, Meter } from './components/ui';
import type { Health } from './components/ui';
import { apiFetch } from './services/api';
import { desktopInvoke as invoke, isTauriRuntime } from './services/desktopBridge';
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

  return (
    <div className="connection-screen">
      <div className="connection-card panel animate-fade">
        <div className="brand-hero">
          <div className="brand-hero__mark"><Camera size={24} /></div>
          <h1>Open<span>Cam</span>Bridge</h1>
          <p>Your phone, wired in as a Windows camera. No cloud, no account.</p>
        </div>

        <div className="form">
          <div className="seg">
            <button type="button" className={`seg__btn${mode === 'usb' ? ' is-active' : ''}`} onClick={() => { setMode('usb'); setError(''); setInfo(''); }}>
              <Usb size={14} /> USB
            </button>
            <button type="button" className={`seg__btn${mode === 'lan' ? ' is-active' : ''}`} onClick={() => { setMode('lan'); setError(''); setInfo(''); }}>
              <Wifi size={14} /> Wi-Fi
            </button>
          </div>

          {mode === 'usb' ? (
            <form onSubmit={handleUsbConnect} className="stack stack--14">
              {error && <div className="error-banner"><AlertTriangle size={15} /><span>{error}</span></div>}
              {info && !error && <p className="hint" style={{ marginTop: 0 }}>{info}</p>}

              <p className="form__lede">
                Recommended. Video stays inside the cable and never touches your network. Connect the phone,
                enable USB debugging, and leave the phone in <strong>USB Only</strong> access mode.
              </p>

              <div className="field">
                <span className="field__label">Port</span>
                <input type="text" className="input-control" value={port} onChange={(e) => setPort(e.target.value)} placeholder="8080" required />
              </div>

              {adbDevices.length > 0 && (
                <div className="field">
                  <span className="field__label">
                    Android device{adbDevices.length > 1 ? ' — required' : ''}
                    <button type="button" className="btn btn--sm" onClick={refreshAdbDevices}>Refresh</button>
                  </span>
                  <select className="input-control" value={selectedSerial} onChange={e => setSelectedSerial(e.target.value)} required={adbDevices.length > 1}>
                    {adbDevices.length > 1 && <option value="">Select a phone…</option>}
                    {adbDevices.map(device => <option key={device.serial} value={device.serial}>{device.model || 'Android device'} — {device.serial}</option>)}
                  </select>
                </div>
              )}

              <button type="submit" className="btn btn-primary btn--lg btn--block" disabled={isConnecting}>
                {isConnecting ? <Zap size={16} /> : <Usb size={16} />}
                {isConnecting ? 'Connecting…' : 'Set up USB & connect'}
              </button>
            </form>
          ) : (
            <form onSubmit={handleLanConnect} className="stack stack--14">
              {error && <div className="error-banner"><AlertTriangle size={15} /><span>{error}</span></div>}

              <p className="form__lede">
                On the phone open OpenCamBridge &gt; Security, switch to <strong>LAN Token</strong> mode, then copy
                the URL and access token it shows.
              </p>

              <div className="field">
                <span className="field__label">Phone URL</span>
                <input type="text" className="input-control" value={lanUrl} onChange={(e) => setLanUrl(e.target.value)} placeholder="http://192.168.1.10:8080" required />
              </div>

              <div className="field">
                <span className="field__label"><span className="row"><ShieldCheck size={12} /> Access token</span></span>
                <input type="password" className="input-control" value={lanToken} onChange={(e) => setLanToken(e.target.value)} placeholder="From the phone's Security tab" required />
              </div>

              <button type="submit" className="btn btn-primary btn--lg btn--block" disabled={isConnecting}>
                {isConnecting ? <Zap size={16} /> : <Unplug size={16} />}
                {isConnecting ? 'Connecting…' : 'Connect over Wi-Fi'}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}

function DesktopApp() {
  const [baseUrl, setBaseUrl] = useState('');
  const [token, setToken] = useState('');
  const [isConnected, setIsConnected] = useState(false);
  const [connectionInfo, setConnectionInfo] = useState<ConnectionInfo | null>(null);
  const [serverStatus, setServerStatus] = useState<any>(null);
  // Default to aspect-fit (letterbox) so the preview never crops or stretches
  // the camera image; the user can switch to 'fill' (crop-to-fill) explicitly.
  const [fitMode, setFitMode] = useState('fit');
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

  const lifecycle: string = serverStatus?.lifecycleState || 'UNKNOWN';
  const isError = lifecycle === 'FAILED' || lifecycle === 'OFFLINE';
  const transportLabel = token ? 'LAN' : 'USB';
  // The header lamp reports the phone's camera lifecycle only. Whether frames
  // reach Windows is the rail's tally lamp; conflating the two is how "a
  // process is alive" gets mistaken for "the webcam works".
  const lifecycleLamp: Health =
    lifecycle === 'STREAMING' ? 'ok'
      : isError ? 'down'
      : lifecycle === 'STARTING' || lifecycle === 'RECONFIGURING' || lifecycle === 'RECOVERING' ? 'busy'
      : 'idle';
  const sourceLabel = serverStatus?.encodedWidth
    ? `${serverStatus.encodedWidth}×${serverStatus.encodedHeight}`
    : '—';
  const rateValue = serverStatus?.snapshot?.actual?.encodedFps
    || serverStatus?.snapshot?.selected?.fps
    || serverStatus?.fps
    || '—';
  const codecLabel = (serverStatus?.activeStreamMode || serverStatus?.streamMode || '—').toString().toUpperCase();

  if (obsMode) {
    return (
      <div style={{ width: '100vw', height: '100vh', margin: 0, padding: 0, overflow: 'hidden', background: '#000', position: 'relative' }}>
        <style>{`.preview-stage { border-radius: 0 !important; background: transparent !important; }`}</style>
        <Preview baseUrl={baseUrl} token={token} fitMode={fitMode} serverStatus={serverStatus} />
        <button
          className="btn btn--sm"
          onClick={() => setObsMode(false)}
          style={{ position: 'absolute', top: 16, right: 16, zIndex: 9999 }}
        >
          <Unplug size={13} /> Exit clean feed (Esc)
        </button>
      </div>
    );
  }

  return (
    <div className="app-container animate-fade">
      <header className="header">
        <div className="header__brand">
          <div className="header__mark"><Camera size={16} /></div>
          <div className="header__word"><b>Open</b><span>Cam</span><b>Bridge</b></div>
        </div>

        <div className="chip" title={token ? 'Connected over Wi-Fi with token authentication' : 'Connected over USB with an adb forward'}>
          {token ? <Wifi size={12} /> : <Usb size={12} />}
          {transportLabel}
        </div>

        <div className="chip">
          <Lamp state={lifecycleLamp} />
          {lifecycle}
        </div>

        <div className="header__spacer" />

        <div className="header__meters">
          <Meter label="Source" value={sourceLabel} tone="plain" title="Resolution the phone is encoding right now" />
          <Meter label="Rate" value={rateValue} unit="fps" tone="plain" title="Frame rate requested from the phone camera" />
          <Meter label="Codec" value={codecLabel} tone={codecLabel === 'H264' ? 'ok' : 'warn'} title="Active transport codec" />
        </div>

        <div className="header__actions">
          <button className="btn btn--sm" onClick={() => setShowLogs(true)} title="Session logs">
            <FileText size={13} /> Logs
          </button>
          <button className="btn btn--sm" onClick={() => { setIsConnected(false); setToken(''); setConnectionInfo(null); }}>
            <Unplug size={13} /> Disconnect
          </button>
        </div>
      </header>

      <main className="main-content">
        <div className="stack stack--14" style={{ height: '100%', minHeight: 0 }}>
          {isError && serverStatus?.lastError && (
            <div className="error-banner animate-fade">
              <AlertTriangle size={15} />
              <span><strong>Camera error:</strong> {serverStatus.lastError}</span>
            </div>
          )}
          {!previewOff ? (
            <Preview baseUrl={baseUrl} token={token} fitMode={fitMode} serverStatus={serverStatus} />
          ) : (
            <div className="preview-wrapper">
              <div className="preview-stage layout-landscape">
                <div className="preview-overlay">
                  <Monitor size={40} />
                  <div>Desktop preview is off</div>
                  <p className="hint" style={{ maxWidth: 320, textAlign: 'center' }}>
                    Capture and delivery to the virtual camera continue as normal — only this window stopped
                    decoding, to keep it out of the frame budget.
                  </p>
                </div>
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

export default function App() {
  if (!isTauriRuntime()) {
    return (
      <div className="connection-screen">
        <div className="connection-card panel animate-fade">
          <div className="brand-hero">
            <div className="brand-hero__mark"><Monitor size={24} /></div>
            <h1>Open<span>Cam</span>Bridge</h1>
            <p>Desktop features are unavailable in a web browser. Open OpenCamBridge through the desktop application.</p>
          </div>
          <div className="error-banner">
            <AlertTriangle size={15} />
            <span>Tauri bridge unavailable — this page is not running inside the desktop app.</span>
          </div>
        </div>
      </div>
    );
  }
  return <DesktopApp />;
}
