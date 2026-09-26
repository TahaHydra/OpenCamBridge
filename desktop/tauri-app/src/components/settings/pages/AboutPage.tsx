import { BookOpen, Bug, ExternalLink, Scale } from 'lucide-react';
import { LogoMark } from '../../brand/Logo';
import { Button } from '../../primitives';
import { SettingsSection } from '../SettingsPage';
import {
  ISSUES_URL,
  LICENSE_URL,
  REPOSITORY_URL,
  THIRD_PARTY_URL,
  openExternal,
  useAppInfo,
} from '../../../state/appInfo';

export default function AboutPage() {
  const app = useAppInfo();
  return (
    <div className="settings-page">
      <div className="about-hero">
        <LogoMark size={56} />
        <div>
          <h2 className="about-hero__name">OpenCamBridge</h2>
          <p className="about-hero__version">{app.version ? `Version ${app.version}` : 'Desktop app'}</p>
          <p className="about-hero__tagline">Your Android phone as a Windows webcam. Free and open source — no cloud, no account, no telemetry.</p>
        </div>
      </div>

      <div className="settings-page__body">
        <SettingsSection title="Project">
          <div className="settings-actions settings-actions--wrap">
            <Button icon={<ExternalLink size={15} />} onClick={() => void openExternal(REPOSITORY_URL)}>GitHub repository</Button>
            <Button icon={<Bug size={15} />} onClick={() => void openExternal(ISSUES_URL)}>Report a problem</Button>
          </div>
        </SettingsSection>

        <SettingsSection title="Licences">
          <p className="settings-note">
            OpenCamBridge is licensed under the GNU General Public License v3.0 or later. The Windows virtual camera is derived from
            Microsoft's Windows-Camera VirtualCamera sample (MIT). The frame producer can use the openh264 library (BSD-2-Clause),
            compiled from source.
          </p>
          <div className="settings-actions settings-actions--wrap">
            <Button icon={<Scale size={15} />} onClick={() => void openExternal(LICENSE_URL)}>GPL-3.0 licence</Button>
            <Button icon={<BookOpen size={15} />} onClick={() => void openExternal(THIRD_PARTY_URL)}>Third-party notices</Button>
          </div>
        </SettingsSection>

        <p className="about-copyright">Copyright © 2026 TahaHydra and contributors.</p>
      </div>
    </div>
  );
}
