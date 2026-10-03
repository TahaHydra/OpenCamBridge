import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, QrCode, RefreshCw, ShieldCheck, Smartphone } from 'lucide-react';
import { Button, Callout, Spinner } from '../primitives';
import {
  formatPairingCode, pairingApi, PairingSession, pairingSecondsRemaining,
  type PairingView, type SavedPhone,
} from '../../services/pairing';
import type { ConnectionInfo } from '../../state/types';

export default function PairingPanel({ onConnect, busy, onBusyChange }: {
  onConnect: (url: string, token: string, connection: ConnectionInfo) => void;
  busy: boolean;
  onBusyChange: (busy: boolean) => void;
}) {
  const [phones, setPhones] = useState<SavedPhone[] | null>(null);
  const [view, setView] = useState<PairingView>({ state: 'idle' });
  const [now, setNow] = useState(Date.now);
  const [error, setError] = useState('');
  const [activePhone, setActivePhone] = useState('');
  const session = useRef<PairingSession | null>(null);
  const generation = useRef(0);
  const listGeneration = useRef(0);
  const actionPending = useRef(false);

  const refreshPhones = useCallback(async () => {
    const current = generation.current;
    const request = ++listGeneration.current;
    try {
      const saved = await pairingApi.list();
      if (current === generation.current && request === listGeneration.current) setPhones(saved);
    } catch {
      if (current === generation.current && request === listGeneration.current) {
        setPhones(previous => previous ?? []);
        setError('Could not load saved phones. Refresh to try again.');
      }
    }
  }, []);

  useEffect(() => {
    ++generation.current;
    const currentSession = new PairingSession(pairingApi, setView);
    session.current = currentSession;
    void refreshPhones();
    return () => {
      ++generation.current;
      currentSession.close();
      session.current = null;
    };
  }, [refreshPhones]);

  useEffect(() => {
    if (view.state !== 'waiting') return;
    const tick = () => {
      setNow(Date.now());
      void session.current?.poll();
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [view.state]);

  useEffect(() => {
    if (view.state !== 'paired') return;
    if (view.phone) {
      const phone = view.phone;
      setPhones(previous => [phone, ...(previous ?? []).filter(item => item.phoneId !== phone.phoneId)]);
    }
    void refreshPhones();
  }, [view, refreshPhones]);

  const connect = async (phone: SavedPhone) => {
    if (actionPending.current || busy) return;
    actionPending.current = true;
    const current = generation.current;
    onBusyChange(true);
    setActivePhone(phone.phoneId);
    setError('');
    try {
      await session.current?.cancel();
      const verified = await pairingApi.connect(phone.phoneId);
      if (current !== generation.current) return;
      // Only this explicit action brings the credential into memory. Never save it in preferences.
      onConnect(verified.baseUrl, verified.token, {
        mode: 'lan', url: verified.baseUrl, port: verified.port, model: verified.name,
      });
    } catch {
      if (current === generation.current) {
        setError('Could not connect to this saved phone. On the phone, choose Wi-Fi in Settings › Connection and tap Start. Keep both devices on the same private LAN. If this PC was revoked on the phone, pair again.');
      }
    } finally {
      actionPending.current = false;
      if (current === generation.current) {
        setActivePhone('');
        onBusyChange(false);
      }
    }
  };

  const forget = async (phone: SavedPhone) => {
    if (actionPending.current || busy) return;
    actionPending.current = true;
    const current = generation.current;
    onBusyChange(true);
    setError('');
    try {
      await pairingApi.forget(phone.phoneId);
      if (current !== generation.current) return;
      ++listGeneration.current;
      setPhones(previous => (previous ?? []).filter(item => item.phoneId !== phone.phoneId));
    } catch {
      if (current === generation.current) setError('Could not forget this phone. Try again.');
    } finally {
      actionPending.current = false;
      if (current === generation.current) onBusyChange(false);
    }
  };

  const waiting = view.state === 'waiting';
  const starting = view.state === 'starting';

  return (
    <section className="pairing" aria-label="Wi-Fi pairing">
      <p className="connect__lede">Pair once with a QR code or an eight-digit code. Keep the phone and this PC on the same <b>private LAN</b>.</p>
      {error && <Callout tone="danger" icon={<AlertTriangle size={15} />}>{error}</Callout>}

      <div className="connect__section-head">
        <span>Saved phones</span>
        <button type="button" className="connect__refresh" disabled={busy} onClick={() => { setError(''); void refreshPhones(); }} aria-label="Refresh saved phones">
          <RefreshCw size={13} /> Refresh
        </button>
      </div>
      {phones === null ? (
        <div className="connect__empty"><Spinner size={15} /> Loading saved phones…</div>
      ) : phones.length === 0 ? (
        <p className="connect__empty">No paired phones yet. Pair a phone below.</p>
      ) : (
        <ul className="pairing__phones">
          {phones.map(phone => (
            <li className="pairing__phone" key={phone.phoneId}>
              <div className="pairing__phone-head">
                <Smartphone size={18} />
                <span className="connect__device-name">{phone.name}</span>
              </div>
              <span className="pairing__host mono">Last address: {phone.lastHost}:{phone.port}</span>
              <div className="pairing__actions">
                <Button size="sm" variant="primary" disabled={busy} loading={activePhone === phone.phoneId} onClick={() => void connect(phone)} aria-label={`Connect to ${phone.name}`}>Connect</Button>
                <Button size="sm" variant="ghost" disabled={busy} onClick={() => void forget(phone)} aria-label={`Forget ${phone.name} on this PC`}>Forget on this PC</Button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {phones && phones.length > 0 && (
        <p className="connect__lede pairing__hint">To connect, select Wi-Fi and tap Start on the phone. Forget removes this PC’s saved copy; to deny access, revoke this PC in the phone’s Settings › Connection.</p>
      )}

      {view.state === 'paired' && (
        <Callout tone="ok" icon={<ShieldCheck size={15} />} title="Phone paired">
          Select Wi-Fi and tap Start on the phone, then choose Connect above. Pairing does not start capture.
        </Callout>
      )}
      {view.state === 'error' && <Callout tone="danger">{view.error}</Callout>}
      {view.state === 'expired' && <Callout tone="warn">Invitation expired. Create a new invitation when you are ready.</Callout>}
      {view.state === 'cancelled' && <p className="connect__lede" role="status">Invitation cancelled.</p>}

      {waiting && (
        <div className="pairing__invitation">
          <div className="pairing__qr">
            <img src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(view.invitation.qrSvg)}`} width={256} height={256} alt="Scan this pairing QR code in OpenCamBridge on your phone" />
          </div>
          <p className="connect__lede">This PC: <b>{view.invitation.name}</b></p>
          <ol className="connect__steps">
            <li>On the phone, open <b>Settings › Connection</b> and choose pairing.</li>
            <li>To scan this QR, <b>Stop capture</b> first so the camera is available.</li>
            <li>Or choose manual code pairing on the phone and enter the code below. It does not need the camera.</li>
            <li>Check this PC’s name and approve pairing on the phone.</li>
          </ol>
          <div className="pairing__code" aria-label={`Pairing code ${view.invitation.code.split('').join(' ')}`}>
            <span className="pairing__hint">Pairing code</span>
            <strong className="mono">{formatPairingCode(view.invitation.code)}</strong>
          </div>
          <p className="connect__note">Expires in {pairingSecondsRemaining(view.invitation, now)} seconds. Keep this panel open.</p>
        </div>
      )}
      {waiting || starting ? (
        <>
          {starting && <p className="connect__lede" role="status"><Spinner size={14} /> Creating invitation…</p>}
          <Button block onClick={() => void session.current?.cancel()}>Cancel invitation</Button>
        </>
      ) : (
        <Button variant="primary" block icon={<QrCode size={16} />} disabled={busy} onClick={() => { setError(''); setNow(Date.now()); void session.current?.start(); }}>
          {view.state === 'expired' || view.state === 'cancelled' || view.state === 'error' ? 'Create new invitation' : 'Pair a phone'}
        </Button>
      )}
      <p className="connect__lede pairing__hint">Windows may ask you to allow OpenCamBridge through the firewall on your private network. Guest Wi-Fi or client isolation can prevent pairing. Video and controls use local HTTP; use a trusted network.</p>
    </section>
  );
}
