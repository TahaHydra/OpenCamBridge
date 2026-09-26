import { useCallback, useRef, useState } from 'react';
import { Camera, CameraOff, Check, ChevronDown, LogOut, RefreshCw, Smartphone, Usb, Wifi } from 'lucide-react';
import { desktopInvoke as invoke } from '../../services/desktopBridge';
import { MenuItem, MenuSeparator, Popover, StatusDot, Spinner } from '../primitives';
import { PHONE_STATE_LABEL, PHONE_STATE_TONE } from '../../state/status';
import type { CameraController } from '../../state/useCameraController';
import type { AdbDevice, ConnectionInfo } from '../../state/types';

export function phoneDisplayName(controller: CameraController, connection: ConnectionInfo): string {
  const info = controller.phoneInfo;
  const reported = info ? `${info.manufacturer || ''} ${info.model || ''}`.trim() : '';
  return reported || connection.model || (connection.mode === 'usb' ? 'Android phone' : 'Wi-Fi phone');
}

/**
 * The phone this window is driving, its connection health, and a way to pick
 * another USB phone or disconnect.
 */
export default function DevicePicker({
  controller,
  connection,
  onSwitchDevice,
  onDisconnect,
}: {
  controller: CameraController;
  connection: ConnectionInfo;
  onSwitchDevice: (device: AdbDevice) => void;
  onDisconnect: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [devices, setDevices] = useState<AdbDevice[] | null>(null);
  const anchorRef = useRef<HTMLDivElement>(null);
  const name = phoneDisplayName(controller, connection);
  const tone = PHONE_STATE_TONE[controller.phoneState];

  const refresh = useCallback(async () => {
    setDevices(null);
    try {
      setDevices(await invoke<AdbDevice[]>('list_devices'));
    } catch {
      setDevices([]);
    }
  }, []);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next && connection.mode === 'usb') void refresh();
  };

  const others = (devices ?? []).filter(device => device.serial !== connection.serial);

  return (
    <div className="device-picker" ref={anchorRef}>
      <button type="button" className={`device-picker__trigger${open ? ' is-open' : ''}`} onClick={toggle} aria-expanded={open}>
        <span className="device-picker__icon"><Smartphone size={18} /></span>
        <span className="device-picker__text">
          <span className="device-picker__name truncate" title={name}>{name}</span>
          <span className="device-picker__status">
            <span className="device-picker__state"><StatusDot tone={tone} />{PHONE_STATE_LABEL[controller.phoneState]}</span>
            <span className="device-picker__transport">
              {connection.mode === 'usb' ? <><Usb size={12} /> USB</> : <><Wifi size={12} /> Wi-Fi</>}
            </span>
          </span>
        </span>
        <ChevronDown size={15} className="device-picker__chevron" />
      </button>

      <Popover open={open} onClose={() => setOpen(false)} anchorRef={anchorRef} label="Phones">
        <MenuItem
          selected
          icon={<Check size={15} />}
          description={connection.mode === 'usb' ? `USB · ${connection.serial || 'adb forward'}` : `Wi-Fi · ${connection.url || ''}`}
        >
          {name}
        </MenuItem>
        {connection.mode === 'usb' && (
          <>
            {devices === null && (
              <div className="device-picker__loading"><Spinner size={14} /> Looking for USB phones…</div>
            )}
            {others.map(device => (
              <MenuItem
                key={device.serial}
                icon={<Usb size={15} />}
                description={controller.isLive ? 'Switching stops the virtual camera' : device.serial}
                onSelect={() => { setOpen(false); onSwitchDevice(device); }}
              >
                {device.model ? device.model.replace(/_/g, ' ') : 'Android phone'}
              </MenuItem>
            ))}
            <MenuItem icon={<RefreshCw size={15} />} onSelect={() => void refresh()}>Refresh USB phones</MenuItem>
          </>
        )}
        <MenuSeparator />
        {(controller.phoneState === 'streaming' || controller.phoneState === 'busy') && (
          <MenuItem
            icon={<CameraOff size={15} />}
            description="Also stops the virtual camera. You can start the phone camera again here."
            onSelect={() => { setOpen(false); void controller.stopEverything(); }}
          >
            Stop phone camera
          </MenuItem>
        )}
        {controller.phoneState === 'stopped' && (
          <MenuItem icon={<Camera size={15} />} onSelect={() => { setOpen(false); void controller.startPhoneCamera(); }}>
            Start phone camera
          </MenuItem>
        )}
        <MenuItem icon={<LogOut size={15} />} onSelect={() => { setOpen(false); onDisconnect(); }}>
          Disconnect
        </MenuItem>
      </Popover>
    </div>
  );
}
