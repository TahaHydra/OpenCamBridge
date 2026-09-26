import { Segmented, Select, Switch } from '../../primitives';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { setPreferences, usePreferences } from '../../../state/preferences';
import { useProfiles } from '../../../state/profiles';
import type { SettingsContext } from '../context';

export default function GeneralPage({ controller }: SettingsContext) {
  const prefs = usePreferences();
  const profiles = useProfiles(controller.activeCam);

  return (
    <SettingsPage title="General" description="How OpenCamBridge looks and what it does when it opens.">
      <SettingsSection title="Appearance">
        <SettingsRow label="Theme" description="Black uses true black surfaces for OLED screens and dark rooms.">
          <Segmented
            label="Theme"
            value={prefs.theme}
            options={[{ value: 'dark', label: 'Dark' }, { value: 'black', label: 'Black' }]}
            onChange={theme => setPreferences({ theme })}
          />
        </SettingsRow>
        <SettingsRow label="Advanced mode" description="Shows live performance figures, quality controls and diagnostics throughout the app.">
          <Switch label="Advanced mode" checked={prefs.advancedMode} onChange={advancedMode => setPreferences({ advancedMode })} />
        </SettingsRow>
      </SettingsSection>

      <SettingsSection title="Startup">
        <SettingsRow label="Reconnect automatically" description="When OpenCamBridge opens, connect to the last phone used over USB.">
          <Switch label="Reconnect automatically" checked={prefs.autoReconnect} onChange={autoReconnect => setPreferences({ autoReconnect })} />
        </SettingsRow>
        <SettingsRow label="Start the virtual camera after connecting" description="Turns on the virtual camera as soon as the phone is streaming.">
          <Switch
            label="Start the virtual camera after connecting"
            checked={prefs.autoStartVirtualCamera}
            onChange={autoStartVirtualCamera => setPreferences({ autoStartVirtualCamera })}
          />
        </SettingsRow>
        <SettingsRow label="Start with Windows" description="Not available in this build yet.">
          <Switch label="Start with Windows" checked={false} disabled onChange={() => undefined} />
        </SettingsRow>
      </SettingsSection>

      <SettingsSection
        title="Default profile"
        description="Applied right after connecting, when the selected camera supports it. Smooth Motion uses that camera's best 60 FPS mode."
      >
        <SettingsRow label="On connect">
          <div className="settings-row__select">
            <Select
              label="Default profile"
              value={prefs.defaultProfileId || '__keep'}
              options={[
                { value: '__keep', label: "Keep the phone's settings" },
                ...profiles.map(profile => ({ value: profile.id, label: profile.name })),
              ]}
              onChange={id => setPreferences({ defaultProfileId: id === '__keep' ? '' : id })}
            />
          </div>
        </SettingsRow>
      </SettingsSection>
    </SettingsPage>
  );
}
