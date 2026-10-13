import { ExternalLink } from 'lucide-react';
import { Button, MetricRow, Switch } from '../../primitives';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { setPreferences, usePreferences } from '../../../state/preferences';
import { checkForUpdates, useUpdateState } from '../../../state/updates';
import { RELEASES_URL, openExternal, useAppInfo } from '../../../state/appInfo';
import type { SettingsContext } from '../context';

export default function UpdatesPage({ controller }: SettingsContext) {
  const app = useAppInfo();
  const preferences = usePreferences();
  const update = useUpdateState();
  return (
    <SettingsPage title="Updates" description="Check official GitHub releases. Downloads and installation remain your choice.">
      <SettingsSection title="Installed versions">
        <div className="settings-metrics">
          <MetricRow label="OpenCamBridge for Windows" value={app.version ? `v${app.version}` : '—'} />
          <MetricRow label="OpenCamBridge on the phone" value={controller.phoneInfo?.version ? `v${controller.phoneInfo.version}` : 'Connect a phone'} />
        </div>
      </SettingsSection>
      <SettingsSection title="Check for updates" description="Only public release metadata is requested over HTTPS. Camera video, phone details and logs are never sent.">
        <SettingsRow label="Check when the app starts" description="Optional. Off by default; local camera use does not need Internet.">
          <Switch label="Check for updates at startup" checked={preferences.checkForUpdatesOnStartup} onChange={checkForUpdatesOnStartup => setPreferences({ checkForUpdatesOnStartup })} />
        </SettingsRow>
        <p className="settings-note" role="status">{update.message}</p>
        <div className="settings-actions"><Button disabled={update.checking} onClick={() => void checkForUpdates()}>{update.checking ? 'Checking…' : 'Check for updates'}</Button></div>
      </SettingsSection>
      <SettingsSection title="Getting new versions" description="New releases are published on GitHub. Update the desktop app and the phone app together.">
        <div className="settings-actions">
          <Button variant="primary" icon={<ExternalLink size={15} />} onClick={() => void openExternal(RELEASES_URL)}>
            Open releases page
          </Button>
        </div>
      </SettingsSection>
    </SettingsPage>
  );
}
