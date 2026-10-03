import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  Laptop,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Usb,
  Wifi,
} from 'lucide-react';
import { LogoMark, Wordmark } from '../brand/Logo';
import { Button, Callout, Field, Segmented, Spinner, TextInput } from '../primitives';
import { apiFetch } from '../../services/api';
import { desktopInvoke as invoke } from '../../services/desktopBridge';
import { getPreferences, setPreferences, usePreferences } from '../../state/preferences';
import type { AdbDevice, ConnectionInfo, ConnectMode } from '../../state/types';
import PairingPanel from './PairingPanel';

export class UnauthorizedError extends Error {
  constructor() {
    super('UNAUTHORIZED');
  }
}

/** Reachability (no token needed for /health by design), then authorisation. */
export async function verifyPhone(url: string, token: string): Promise<string> {
  const formattedUrl = url.endsWith('/') ? url.slice(0, -1) : url;
  const healthRes = await apiFetch(formattedUrl, '/health');
  if (!healthRes.ok) throw new Error('Health check failed');
  const text = await healthRes.text();
  if (!(text === 'OK' || text.toLowerCase().includes('ok'))) throw new Error('Invalid health response');
  // LAN mode requires the token for everything else.
  const statusRes = await apiFetch(formattedUrl, '/api/camera/status', token);
  if (statusRes.status === 401) throw new UnauthorizedError();
  if (!statusRes.ok) throw new Error('Status check failed');
  return formattedUrl;
}

/**
 * Sets up the targeted adb forward and verifies the phone. Shared by the
 * connect screen, auto-reconnect and switching phones from the main window.
 */
export async function connectUsb(
  port: number,
  serial: string | undefined,
  onProgress?: (message: string) => void,
): Promise<{ url: string; connection: ConnectionInfo }> {
  // Best-effort adb forward. If adb is missing we still try to connect: the
  // user may have set the forward up manually.
  try {
    onProgress?.('Setting up the USB connection…');
    await invoke<string>('forward_port', { port, serial: serial || undefined });
  } catch (adbErr: any) {
    const adbMessage = String(adbErr);
    if (/Several ADB devices|Selected ADB device/.test(adbMessage)) throw new Error(adbMessage);
    onProgress?.('adb is not available — trying a direct connection…');
  }
  onProgress?.('Reaching OpenCamBridge on the phone…');
  const url = await verifyPhone(`http://127.0.0.1:${port}`, '');
  return { url, connection: { mode: 'usb', port, serial } };
}

export default function ConnectScreen({
  onConnect,
}: {
  onConnect: (url: string, token: string, connection: ConnectionInfo) => void;
}) {
  const prefs = usePreferences();
  const [mode, setMode] = useState<ConnectMode>('usb');
  const [devices, setDevices] = useState<AdbDevice[] | null>(null);
  const [adbMissing, setAdbMissing] = useState(false);
  const [selectedSerial, setSelectedSerial] = useState('');
  const [lanUrl, setLanUrl] = useState(prefs.lanUrl);
  const [lanToken, setLanToken] = useState('');
  const [port, setPort] = useState(String(prefs.usbPort));
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState('');
  const [error, setError] = useState('');
  const [helpOpen, setHelpOpen] = useState(false);
  const [portOpen, setPortOpen] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const autoTried = useRef(false);

  const refreshDevices = useCallback(async (): Promise<AdbDevice[]> => {
    try {
      const list = await invoke<AdbDevice[]>('list_devices');
      setAdbMissing(false);
      setDevices(list);
      const { preferredDeviceId, lastDeviceId } = getPreferences();
      setSelectedSerial(current => {
        if (list.some(device => device.serial === current)) return current;
        const preferred = list.find(device => device.serial === preferredDeviceId)
          || list.find(device => device.serial === lastDeviceId);
        return preferred?.serial || (list.length === 1 ? list[0].serial : '');
      });
      return list;
    } catch {
      setAdbMissing(true);
      setDevices([]);
      setSelectedSerial('');
      return [];
    }
  }, []);

  const usbConnect = useCallback(async (serialOverride?: string) => {
    setBusy(true);
    setError('');
    setProgress('');
    const p = parseInt(port, 10) || 8080;
    try {
      const live = await refreshDevices();
      const serial = serialOverride || selectedSerial;
      if (live.length > 1 && !live.some(device => device.serial === serial)) {
        setError('Several phones are connected. Choose the one to use.');
        return;
      }
      const effectiveSerial = live.length === 1 ? live[0].serial : serial;
      const model = live.find(device => device.serial === effectiveSerial)?.model;
      const { url, connection } = await connectUsb(p, effectiveSerial || undefined, setProgress);
      if (p !== getPreferences().usbPort) setPreferences({ usbPort: p });
      onConnect(url, '', { ...connection, model: model?.replace(/_/g, ' ') });
    } catch (err: any) {
      if (err instanceof UnauthorizedError) {
        setError('The phone is in Wi-Fi mode, which needs a token. On the phone open Settings › Connection and choose USB only, or connect over Wi-Fi.');
      } else if (/Several ADB devices|Selected ADB device/.test(String(err?.message))) {
        setError(String(err.message));
      } else {
        setError('Could not reach the phone. Make sure OpenCamBridge is open on the phone and you tapped Start.');
        setHelpOpen(true);
      }
    } finally {
      setBusy(false);
      setProgress('');
    }
  }, [port, selectedSerial, refreshDevices, onConnect]);

  const lanConnect = async () => {
    setBusy(true);
    setError('');
    try {
      const url = await verifyPhone(lanUrl.trim(), lanToken.trim());
      setPreferences({ lanUrl: lanUrl.trim() });
      onConnect(url, lanToken.trim(), { mode: 'lan', url });
    } catch (err: any) {
      if (err instanceof UnauthorizedError) {
        setError('The access token was not accepted. Copy it again from the phone: Settings › Connection › Access token.');
      } else {
        setError('Could not reach the phone. Check that both devices are on the same network, Wi-Fi mode is on, and the address matches the phone.');
      }
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (mode !== 'usb') return;
    void refreshDevices().then(list => {
      // Reconnect to the last USB phone once, when the user asked for it.
      if (autoTried.current) return;
      autoTried.current = true;
      const { autoReconnect, lastDeviceId, knownDevices } = getPreferences();
      const last = knownDevices.find(device => device.id === lastDeviceId && device.mode === 'usb');
      if (autoReconnect && last && list.some(device => device.serial === last.serial)) {
        void usbConnect(last.serial);
      }
    });
    // usbConnect is intentionally not a dependency: auto-reconnect runs once per mount.
  }, [mode, refreshDevices]);

  // Keep looking for a phone while the USB tab is open and none is attached.
  useEffect(() => {
    if (mode !== 'usb' || busy || (devices && devices.length > 0)) return;
    const timer = window.setInterval(() => { void refreshDevices(); }, 3000);
    return () => window.clearInterval(timer);
  }, [mode, busy, devices, refreshDevices]);

  const deviceCount = devices?.length ?? 0;

  return (
    <div className="connect">
      <header className="connect__brand">
        <LogoMark size={26} />
        <Wordmark />
      </header>

      <main className="connect__main">
        <div className="connect__hero" aria-hidden="true">
          <span className="connect__device"><Smartphone size={30} /></span>
          <span className="connect__link">
            <span /><span /><span />
          </span>
          <span className="connect__device"><Laptop size={32} /></span>
        </div>
        <h1 className="connect__title">Connect your phone</h1>
        <p className="connect__subtitle">{mode === 'usb'
          ? <>Open OpenCamBridge on your Android phone and tap <b>Start</b>.</>
          : <>Pair your Android phone, then tap <b>Start</b> on the phone to connect.</>}</p>

        <div className="connect__card">
          <Segmented
            label="Connection method"
            value={mode}
            disabled={busy}
            options={[
              { value: 'usb', label: 'USB', icon: <Usb size={14} /> },
              { value: 'lan', label: 'Wi-Fi', icon: <Wifi size={14} /> },
            ]}
            onChange={value => { setMode(value); setError(''); }}
          />

          {error && <Callout tone="danger" icon={<AlertTriangle size={15} />}>{error}</Callout>}

          {mode === 'usb' ? (
            <>
              <div className="connect__section-head">
                <span>{deviceCount > 1 ? 'Choose a phone' : 'Phone'}</span>
                <button type="button" className="connect__refresh" onClick={() => void refreshDevices()} aria-label="Refresh phones">
                  <RefreshCw size={13} /> Refresh
                </button>
              </div>

              {devices === null ? (
                <div className="connect__empty"><Spinner size={15} /> Looking for phones…</div>
              ) : deviceCount === 0 ? (
                <div className="connect__empty">
                  <Spinner size={15} />
                  {adbMissing ? 'adb was not found. You can still connect if a port forward is already set up.' : 'Waiting for a phone on USB…'}
                </div>
              ) : (
                <div className="connect__devices" role="radiogroup" aria-label="Phones">
                  {devices.map(device => (
                    <button
                      key={device.serial}
                      type="button"
                      role="radio"
                      aria-checked={selectedSerial === device.serial || deviceCount === 1}
                      className={`connect__device-row${selectedSerial === device.serial || deviceCount === 1 ? ' is-selected' : ''}`}
                      onClick={() => setSelectedSerial(device.serial)}
                      onDoubleClick={() => void usbConnect(device.serial)}
                    >
                      <Smartphone size={18} />
                      <span className="connect__device-name">{device.model ? device.model.replace(/_/g, ' ') : 'Android phone'}</span>
                      <span className="connect__device-serial mono">{device.serial}</span>
                    </button>
                  ))}
                </div>
              )}

              <Button variant="primary" size="lg" block loading={busy} onClick={() => void usbConnect()}>
                {busy ? progress || 'Connecting…' : 'Connect'}
              </Button>

              <button type="button" className="connect__disclosure" onClick={() => setHelpOpen(value => !value)} aria-expanded={helpOpen}>
                {helpOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />} Phone not showing up?
              </button>
              {helpOpen && (
                <ol className="connect__steps">
                  <li>Connect the phone with a USB cable that carries data, not just power.</li>
                  <li>On the phone, enable <b>Developer options › USB debugging</b>.</li>
                  <li>Unlock the phone and accept the <b>Allow USB debugging</b> prompt.</li>
                  <li>Open OpenCamBridge on the phone and tap <b>Start</b>.</li>
                </ol>
              )}

              <button type="button" className="connect__disclosure" onClick={() => setPortOpen(value => !value)} aria-expanded={portOpen}>
                {portOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />} Port
              </button>
              {portOpen && (
                <Field label="Port" hint="Must match the port set on the phone. The default is 8080.">
                  <TextInput label="Port" value={port} onChange={value => setPort(value.replace(/[^0-9]/g, '').slice(0, 5))} mono />
                </Field>
              )}
            </>
          ) : (
            <>
              <PairingPanel onConnect={onConnect} busy={busy} onBusyChange={setBusy} />
              <button type="button" className="connect__disclosure" onClick={() => setAdvancedOpen(value => !value)} aria-expanded={advancedOpen} aria-controls="advanced-wifi">
                {advancedOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />} Advanced: manual address and token
              </button>
              {advancedOpen && <div className="pairing" id="advanced-wifi">
              <p className="connect__lede">
                On the phone open <b>Settings › Connection</b>, choose <b>Wi-Fi</b>, then copy the address and access token it shows.
              </p>
              <Field label="Phone address">
                <TextInput label="Phone address" value={lanUrl} onChange={setLanUrl} placeholder="http://192.168.1.10:8080" disabled={busy} mono />
              </Field>
              <Field label={<span className="connect__token-label"><ShieldCheck size={13} /> Access token</span>}>
                <TextInput label="Access token" type="password" value={lanToken} onChange={setLanToken} placeholder="From the phone" disabled={busy} onEnter={() => { if (!busy) void lanConnect(); }} />
              </Field>
              <Button variant="primary" size="lg" block loading={busy} disabled={!lanUrl.trim() || !lanToken.trim()} onClick={() => void lanConnect()}>
                Connect over Wi-Fi
              </Button>
              <p className="connect__note">USB is faster and keeps video off your network. Use Wi-Fi when a cable isn't practical.</p>
              </div>}
            </>
          )}
        </div>
      </main>

      <footer className="connect__footer">No cloud · No account · No telemetry</footer>
    </div>
  );
}
