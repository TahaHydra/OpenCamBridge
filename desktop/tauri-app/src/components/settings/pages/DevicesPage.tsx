import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, Smartphone, Star, Trash2, Usb, Wifi } from 'lucide-react';
import { Badge, Button, Callout, IconButton, MetricRow, Spinner } from '../../primitives';
import { SettingsPage, SettingsSection } from '../SettingsPage';
import { desktopInvoke as invoke } from '../../../services/desktopBridge';
import { forgetDevice, setPreferences, usePreferences } from '../../../state/preferences';
import { PHONE_STATE_LABEL, PHONE_STATE_TONE } from '../../../state/status';
import { phoneDisplayName } from '../../camera/DevicePicker';
import type { AdbDevice } from '../../../state/types';
import type { SettingsContext } from '../context';

const connectionId = (mode: string, serial?: string, url?: string) => (mode === 'usb' ? serial || 'usb' : url || 'lan');

export default function DevicesPage({ controller, connection }: SettingsContext) {
  const prefs = usePreferences();
  const [usb, setUsb] = useState<AdbDevice[] | null>(null);
  const [usbError, setUsbError] = useState('');
  const info = controller.phoneInfo;
  const currentId = connectionId(connection.mode, connection.serial, connection.url);

  const refresh = useCallback(async () => {
    setUsb(null);
    setUsbError('');
    try {
      setUsb(await invoke<AdbDevice[]>('list_devices'));
    } catch (error) {
      setUsb([]);
      setUsbError(String(error));
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  return (
    <SettingsPage title="Devices" description="The phone in use, USB phones attached to this PC, and phones you've connected before.">
      <SettingsSection title="This phone">
        <div className="device-card">
          <span className="device-card__icon"><Smartphone size={22} /></span>
          <div className="device-card__main">
            <div className="device-card__name">{phoneDisplayName(controller, connection)}</div>
            <div className="device-card__meta">
              <Badge tone={PHONE_STATE_TONE[controller.phoneState]}>{PHONE_STATE_LABEL[controller.phoneState]}</Badge>
              <span>{connection.mode === 'usb' ? <><Usb size={12} /> USB</> : <><Wifi size={12} /> Wi-Fi</>}</span>
            </div>
          </div>
        </div>
        <div className="settings-metrics">
          <MetricRow label="Manufacturer / model" value={info ? `${info.manufacturer || '—'} / ${info.model || '—'}` : '—'} />
          <MetricRow label="OpenCamBridge app" value={info?.version ? `v${info.version}` : '—'} />
          <MetricRow label={connection.mode === 'usb' ? 'USB serial' : 'Address'} value={connection.mode === 'usb' ? connection.serial || 'single device' : connection.url || '—'} />
          <MetricRow label="Cameras reported" value={controller.cameras.length || '—'} />
          <MetricRow
            label="Battery optimisation"
            value={info?.batteryOptimizationExempt == null ? '—' : info.batteryOptimizationExempt ? 'Unrestricted' : 'Restricted'}
            tone={info?.batteryOptimizationExempt === false ? 'warn' : info?.batteryOptimizationExempt ? 'ok' : undefined}
          />
        </div>
        {info?.batteryOptimizationExempt === false && (
          <Callout tone="warn" title="Android may pause the camera when the phone locks">
            On the phone, open Settings › Apps › OpenCamBridge › Battery and choose Unrestricted.
          </Callout>
        )}
      </SettingsSection>

      <SettingsSection
        title="USB phones on this PC"
        description="Phones with USB debugging enabled and authorised."
        aside={<Button size="sm" icon={<RefreshCw size={13} />} onClick={() => void refresh()}>Refresh</Button>}
      >
        {usb === null ? (
          <div className="settings-empty"><Spinner size={14} /> Looking for phones…</div>
        ) : usb.length === 0 ? (
          <div className="settings-empty">
            {usbError ? `adb is not available: ${usbError.slice(0, 140)}` : 'No authorised USB phones found. Unlock the phone and accept the USB debugging prompt.'}
          </div>
        ) : (
          <div className="device-list">
            {usb.map(device => (
              <div key={device.serial} className="device-list__row">
                <Usb size={15} className="muted" />
                <span className="device-list__name">{device.model ? device.model.replace(/_/g, ' ') : 'Android phone'}</span>
                <span className="device-list__serial mono">{device.serial}</span>
                {connection.mode === 'usb' && connection.serial === device.serial && <Badge tone="ok">In use</Badge>}
              </div>
            ))}
          </div>
        )}
      </SettingsSection>

      <SettingsSection title="Remembered phones" description="Phones this PC has connected to. The preferred phone is selected first when several are plugged in.">
        {prefs.knownDevices.length === 0 ? (
          <div className="settings-empty">No phones remembered yet.</div>
        ) : (
          <div className="device-list">
            {prefs.knownDevices.map(device => {
              const preferred = prefs.preferredDeviceId === device.id;
              return (
                <div key={device.id} className="device-list__row">
                  {device.mode === 'usb' ? <Usb size={15} className="muted" /> : <Wifi size={15} className="muted" />}
                  <span className="device-list__name">{device.name}</span>
                  <span className="device-list__serial mono">{device.mode === 'usb' ? device.serial : device.url}</span>
                  <span className="device-list__when">{new Date(device.lastConnected).toLocaleDateString()}</span>
                  {device.id === currentId && <Badge tone="ok">In use</Badge>}
                  <IconButton
                    size="sm"
                    label={preferred ? 'Preferred phone' : 'Make preferred'}
                    icon={<Star size={14} fill={preferred ? 'currentColor' : 'none'} />}
                    active={preferred}
                    onClick={() => setPreferences({ preferredDeviceId: preferred ? '' : device.id })}
                  />
                  <IconButton size="sm" label="Forget phone" icon={<Trash2 size={14} />} onClick={() => forgetDevice(device.id)} />
                </div>
              );
            })}
          </div>
        )}
      </SettingsSection>
    </SettingsPage>
  );
}
