import { useState } from 'react';
import { LogOut, ShieldCheck, Usb, Wifi } from 'lucide-react';
import { Badge, Button, Callout, MetricRow, TextInput } from '../../primitives';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { setPreferences, usePreferences } from '../../../state/preferences';
import type { SettingsContext } from '../context';

export default function ConnectionsPage({ controller, connection, onDisconnect }: SettingsContext) {
  const prefs = usePreferences();
  const [port, setPort] = useState(String(prefs.usbPort));
  const [lanUrl, setLanUrl] = useState(prefs.lanUrl);
  const usb = connection.mode === 'usb';

  const savePort = () => {
    const value = parseInt(port, 10);
    if (value >= 1024 && value <= 65535) setPreferences({ usbPort: value });
    else setPort(String(prefs.usbPort));
  };

  return (
    <SettingsPage title="Connections" description="How this PC reaches the phone. USB keeps video inside the cable; Wi-Fi needs the phone's access token.">
      <SettingsSection
        title="Current connection"
        aside={<Button size="sm" variant="ghost" icon={<LogOut size={14} />} onClick={onDisconnect}>Disconnect</Button>}
      >
        <div className="settings-metrics">
          <MetricRow label="Method" value={usb ? 'USB (adb port forward)' : 'Wi-Fi (token)'} />
          <MetricRow label="Address" value={controller.baseUrl} />
          {usb && <MetricRow label="Phone" value={connection.serial || 'single device'} />}
          <MetricRow label="Control server" value={controller.androidRunning ? 'Reachable' : 'Not reachable'} tone={controller.androidRunning ? 'ok' : 'danger'} />
        </div>
        {usb && (
          <p className="settings-note">
            While connected, the USB port forward is checked every few seconds and restored after a cable is unplugged and replugged.
          </p>
        )}
      </SettingsSection>

      <SettingsSection title="USB" description="Recommended. Needs USB debugging enabled on the phone." aside={<Badge tone="accent" dot={false}><Usb size={12} /> Recommended</Badge>}>
        <SettingsRow label="Port" description="Must match the port set on the phone (default 8080). Used for the next connection.">
          <div className="settings-row__input">
            <TextInput label="USB port" value={port} onChange={value => setPort(value.replace(/[^0-9]/g, '').slice(0, 5))} onEnter={savePort} mono />
            <Button size="sm" onClick={savePort} disabled={port === String(prefs.usbPort)}>Save</Button>
          </div>
        </SettingsRow>
      </SettingsSection>

      <SettingsSection title="Wi-Fi" aside={<Wifi size={16} className="muted" />}>
        <SettingsRow label="Phone address" description="Shown on the phone under Settings › Connection when Wi-Fi mode is on.">
          <div className="settings-row__input">
            <TextInput label="Phone address" value={lanUrl} onChange={setLanUrl} placeholder="http://192.168.1.10:8080" mono onEnter={() => setPreferences({ lanUrl })} />
            <Button size="sm" onClick={() => setPreferences({ lanUrl })} disabled={lanUrl === prefs.lanUrl}>Save</Button>
          </div>
        </SettingsRow>
        <Callout tone="accent" icon={<ShieldCheck size={15} />} title="How Wi-Fi stays private">
          Wi-Fi mode must be switched on from the phone. Every request then needs the phone's access token, which OpenCamBridge
          keeps in memory only and never saves. Security settings can only be changed on the phone itself.
        </Callout>
      </SettingsSection>
    </SettingsPage>
  );
}
