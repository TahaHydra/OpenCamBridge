import { useState } from 'react';
import { AlertTriangle, ShieldAlert } from 'lucide-react';
import { Badge, Button, Callout, Segmented, TextInput } from '../../primitives';
import { SettingsPage, SettingsRow, SettingsSection } from '../SettingsPage';
import { setPreferences, usePreferences, type ObsSetupMode } from '../../../state/preferences';
import { VIRTUAL_CAMERA_NAME } from '../../../services/obs';
import { VIRTUAL_CAMERA_LABEL, VIRTUAL_CAMERA_TONE } from '../../../state/status';
import type { SettingsContext } from '../context';

const METHOD_HELP: Record<ObsSetupMode, string> = {
  camera: `Adds ${VIRTUAL_CAMERA_NAME} as a Video Capture Device. Full quality, lowest latency.`,
  browser: 'Adds a Browser Source reading MJPEG straight from the phone, framed like this preview when added. For PCs where the virtual camera is blocked.',
  window: "Captures this app's clean feed window. A last resort when neither of the others works.",
};

export default function IntegrationsPage({ controller, obs }: SettingsContext) {
  const prefs = usePreferences();
  const [url, setUrl] = useState(prefs.obsUrl);
  const [password, setPassword] = useState(prefs.obsPassword);
  const [details, setDetails] = useState('');
  const { state } = obs;
  const connected = state.phase === 'connected' || state.phase === 'working';
  const tone = state.phase === 'connected' ? 'ok' : state.phase === 'error' ? 'danger' : state.phase === 'disconnected' ? 'idle' : 'busy';
  const dirty = url !== prefs.obsUrl || password !== prefs.obsPassword;

  const saveConnection = () => setPreferences({ obsUrl: url.trim() || 'ws://127.0.0.1:4455', obsPassword: password });

  return (
    <SettingsPage title="Integrations" description="OBS Studio and the virtual camera that Zoom, Teams, Discord and browsers use.">
      <SettingsSection
        title="OBS Studio"
        description="Uses OBS WebSocket (OBS 28 or newer: Tools › WebSocket Server Settings)."
        aside={<Badge tone={tone}>{state.phase === 'connected' ? `Connected${state.obsVersion ? ` · OBS ${state.obsVersion}` : ''}` : state.message}</Badge>}
      >
        <SettingsRow label="Server address">
          <div className="settings-row__input settings-row__input--wide">
            <TextInput label="OBS WebSocket address" value={url} onChange={setUrl} placeholder="ws://127.0.0.1:4455" mono />
          </div>
        </SettingsRow>
        <SettingsRow label="Password" description="Stored only on this PC.">
          <div className="settings-row__input settings-row__input--wide">
            <TextInput label="OBS WebSocket password" type="password" value={password} onChange={setPassword} placeholder="Optional" />
          </div>
        </SettingsRow>
        <SettingsRow label="Setup method" description={METHOD_HELP[prefs.obsSetupMode]}>
          <Segmented
            size="sm"
            label="OBS setup method"
            value={prefs.obsSetupMode}
            options={[
              { value: 'camera', label: 'Camera' },
              { value: 'browser', label: 'Browser' },
              { value: 'window', label: 'Window' },
            ]}
            onChange={obsSetupMode => setPreferences({ obsSetupMode })}
          />
        </SettingsRow>
        {state.phase === 'error' && state.error && (
          <Callout tone="danger" icon={<AlertTriangle size={14} />} title={state.message}>{state.error}</Callout>
        )}
        <div className="settings-actions">
          {dirty && <Button onClick={saveConnection}>Save</Button>}
          {connected ? (
            <Button onClick={() => void obs.disconnect()}>Disconnect</Button>
          ) : (
            <Button onClick={() => { saveConnection(); void obs.connect(); }} loading={state.phase === 'connecting'}>
              Test connection
            </Button>
          )}
          <Button variant="primary" loading={state.phase === 'working'} onClick={() => { saveConnection(); void obs.setup(); }}>
            Add to OBS now
          </Button>
        </div>
      </SettingsSection>

      <SettingsSection
        title="Virtual camera"
        description={`Apps see it as “${VIRTUAL_CAMERA_NAME}”. Installing it needs administrator approval once.`}
        aside={<Badge tone={VIRTUAL_CAMERA_TONE[controller.virtualCamera]}>{VIRTUAL_CAMERA_LABEL[controller.virtualCamera]}</Badge>}
      >
        {controller.virtualCamera === 'not-installed' ? (
          <Button variant="primary" icon={<ShieldAlert size={15} />} loading={controller.isVcamRegistering} onClick={() => void controller.registerVirtualCamera()}>
            Install virtual camera
          </Button>
        ) : (
          <div className="settings-actions">
            <Button onClick={async () => setDetails(await controller.getBackendDetails())}>Show driver details</Button>
            <Button
              variant="danger"
              disabled={!!(controller.vcamState?.process_running || controller.vcamState?.host_running) || controller.isVcamRegistering}
              title={controller.vcamState?.host_running ? 'Stop the virtual camera first' : undefined}
              onClick={() => void controller.unregisterVirtualCamera()}
            >
              Remove virtual camera
            </Button>
          </div>
        )}
        {details && <pre className="settings-pre">{details}</pre>}
      </SettingsSection>
    </SettingsPage>
  );
}
