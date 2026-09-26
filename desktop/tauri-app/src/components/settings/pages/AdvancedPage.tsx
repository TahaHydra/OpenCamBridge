import { useState } from 'react';
import { Activity, Copy, FileText, FolderOpen, RefreshCw, RotateCcw, Square } from 'lucide-react';
import { Button, MetricRow, Switch } from '../../primitives';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { openLogsFolder } from '../../../services/logging';
import { resetPreferences, setPreferences, usePreferences } from '../../../state/preferences';
import { useAppInfo } from '../../../state/appInfo';
import type { SettingsContext } from '../context';

export default function AdvancedPage({ controller, onOpenDiagnostics, onOpenLogs }: SettingsContext) {
  const prefs = usePreferences();
  const app = useAppInfo();
  const [copied, setCopied] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const identity = controller.vcamState?.binary_identity;
  const ring = controller.vcamState?.metrics?.ring;

  return (
    <SettingsPage title="Advanced" description="Diagnostics, logs and recovery tools. Nothing here is needed for everyday use.">
      <SettingsSection title="Advanced mode">
        <SettingsRow label="Show advanced controls" description="Adds live figures to the top bar, encoder controls to the camera panel and a Performance card to Outputs.">
          <Switch label="Show advanced controls" checked={prefs.advancedMode} onChange={advancedMode => setPreferences({ advancedMode })} />
        </SettingsRow>
      </SettingsSection>

      <SettingsSection title="Diagnostics & logs">
        <div className="settings-actions settings-actions--wrap">
          <Button icon={<Activity size={15} />} onClick={onOpenDiagnostics}>Open diagnostics</Button>
          <Button
            icon={<Copy size={15} />}
            onClick={async () => {
              setCopied(await controller.copyDiagnostics());
              window.setTimeout(() => setCopied(false), 2000);
            }}
          >
            {copied ? 'Copied' : 'Copy diagnostics report'}
          </Button>
          <Button icon={<FileText size={15} />} onClick={onOpenLogs}>Session log</Button>
          <Button icon={<FolderOpen size={15} />} onClick={() => void openLogsFolder()}>Open logs folder</Button>
        </div>
      </SettingsSection>

      <SettingsSection title="Recovery">
        <SettingsRow label="Reload the preview" description="Reconnects this window's preview without touching the camera.">
          <Button size="sm" icon={<RefreshCw size={14} />} onClick={() => window.dispatchEvent(new CustomEvent('reload-preview'))}>Reload</Button>
        </SettingsRow>
        <SettingsRow label="Stop desktop processes" description="Stops the decoder and OpenCamBridge Camera on this PC. The phone keeps running.">
          <Button
            size="sm"
            icon={<Square size={13} />}
            disabled={!controller.vcamState?.process_running && !controller.vcamState?.host_running}
            onClick={() => void controller.stopVirtualCamera()}
          >
            Stop
          </Button>
        </SettingsRow>
        <SettingsRow label="Reset app preferences" description="Restores display, startup and OBS settings. Profiles and remembered phones are kept.">
          {confirmReset ? (
            <div className="settings-actions">
              <Button size="sm" variant="ghost" onClick={() => setConfirmReset(false)}>Cancel</Button>
              <Button size="sm" variant="danger" onClick={() => { resetPreferences(); setConfirmReset(false); }}>Reset</Button>
            </div>
          ) : (
            <Button size="sm" variant="danger" icon={<RotateCcw size={13} />} onClick={() => setConfirmReset(true)}>Reset…</Button>
          )}
        </SettingsRow>
      </SettingsSection>

      <SettingsSection title="Developer information">
        <div className="settings-metrics">
          <MetricRow label="Desktop app" value={app.version ? `v${app.version}` : '—'} />
          <MetricRow label="Tauri" value={app.tauriVersion || '—'} />
          <MetricRow label="Phone app" value={controller.phoneInfo?.version ? `v${controller.phoneInfo.version}` : '—'} />
          <MetricRow label="Producer" value={controller.vcamState?.producer_path?.split('\\').pop() || '—'} title={controller.vcamState?.producer_path} />
          <MetricRow label="Binary identity" value={identity ? (identity.ready ? 'Consistent' : 'Mismatch') : '—'} tone={identity ? (identity.ready ? 'ok' : 'danger') : undefined} />
          <MetricRow label="Ring ABI" value={ring ? `0x${(ring.ring_abi_hash >>> 0).toString(16)}` : '—'} tone="muted" />
        </div>
      </SettingsSection>
    </SettingsPage>
  );
}
