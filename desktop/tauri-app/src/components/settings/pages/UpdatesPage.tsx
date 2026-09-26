import { ExternalLink } from 'lucide-react';
import { Button, MetricRow } from '../../primitives';
import { SettingsPage, SettingsSection } from '../SettingsPage';
import { RELEASES_URL, openExternal, useAppInfo } from '../../../state/appInfo';
import type { SettingsContext } from '../context';

export default function UpdatesPage({ controller }: SettingsContext) {
  const app = useAppInfo();
  return (
    <SettingsPage title="Updates" description="OpenCamBridge never updates itself or contacts a server in the background.">
      <SettingsSection title="Installed versions">
        <div className="settings-metrics">
          <MetricRow label="OpenCamBridge for Windows" value={app.version ? `v${app.version}` : '—'} />
          <MetricRow label="OpenCamBridge on the phone" value={controller.phoneInfo?.version ? `v${controller.phoneInfo.version}` : 'Connect a phone'} />
        </div>
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
