import { Activity, Settings } from 'lucide-react';
import { LogoMark, Wordmark } from '../brand/Logo';
import { IconButton, Switch } from '../primitives';
import ProfileMenu from './ProfileMenu';
import type { CameraController } from '../../state/useCameraController';

/**
 * Brand on the left, the capture profile in the middle, and on the right the
 * Advanced switch and Settings. With Advanced on, live throughput and the
 * diagnostics entry point join the right-hand cluster.
 */
export default function TopBar({
  controller,
  advanced,
  onAdvancedChange,
  onOpenSettings,
  onOpenDiagnostics,
  onManageProfiles,
}: {
  controller: CameraController;
  advanced: boolean;
  onAdvancedChange: (value: boolean) => void;
  onOpenSettings: () => void;
  onOpenDiagnostics: () => void;
  onManageProfiles: () => void;
}) {
  const metrics = controller.vcamState?.metrics;
  const capture = controller.androidMetrics?.actualFps;
  const bitrate = Number(controller.androidMetrics?.encodedBitrate || 0) / 1_000_000;
  const link = metrics?.transport_bandwidth_mbps ?? metrics?.estimated_mbps;

  return (
    <header className="topbar">
      <div className="topbar__brand">
        <LogoMark size={24} />
        <Wordmark />
      </div>

      <div className="topbar__center">
        <ProfileMenu controller={controller} onManage={onManageProfiles} />
      </div>

      <div className="topbar__actions">
        {advanced && (
          <div className="topbar__stats" title="Phone capture rate and link bandwidth">
            <span><b className="tabular">{capture ?? '—'}</b> fps</span>
            <span><b className="tabular">{bitrate > 0 ? bitrate.toFixed(1) : link || '—'}</b> Mb/s</span>
            <IconButton
              size="sm"
              label="Diagnostics"
              icon={<Activity size={15} />}
              onClick={onOpenDiagnostics}
              className={controller.diagAlert ? `has-alert has-alert--${controller.diagAlert}` : undefined}
            />
          </div>
        )}
        <label className="topbar__advanced">
          <span>Advanced</span>
          <Switch checked={advanced} onChange={onAdvancedChange} label="Advanced mode" />
        </label>
        <IconButton label="Settings" icon={<Settings size={17} />} onClick={onOpenSettings} />
      </div>
    </header>
  );
}
