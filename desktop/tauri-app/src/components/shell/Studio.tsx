import { useCallback, useEffect, useRef, useState } from 'react';
import TopBar from './TopBar';
import CameraRail from '../camera/CameraRail';
import OutputsRail from '../outputs/OutputsRail';
import PreviewStage from '../stage/PreviewStage';
import CleanFeed from '../stage/CleanFeed';
import SettingsModal from '../settings/SettingsModal';
import DiagnosticsModal from '../diagnostics/DiagnosticsModal';
import LogsView from '../LogsView';
import { useCameraController } from '../../state/useCameraController';
import { useObs } from '../../state/useObs';
import { setPreferences, usePreferences } from '../../state/preferences';
import { useProfiles } from '../../state/profiles';
import { matchProfile, profileAvailability } from '../../services/profilePolicy.js';
import type { SettingsPageId } from '../settings/context';
import type { AdbDevice, ConnectionInfo } from '../../state/types';

/**
 * The main window: camera controls on the left, the preview in the middle
 * (always the largest thing on screen), outputs on the right. Owns the camera
 * controller for as long as a phone is connected, so its state survives the
 * clean feed, settings and diagnostics.
 */
export default function Studio({
  baseUrl,
  token,
  connection,
  onDisconnect,
  onSwitchDevice,
}: {
  baseUrl: string;
  token: string;
  connection: ConnectionInfo;
  onDisconnect: () => void;
  onSwitchDevice: (device: AdbDevice) => void;
}) {
  const prefs = usePreferences();
  const profiles = useProfiles();
  const [cleanFeed, setCleanFeed] = useState(false);
  const [settingsPage, setSettingsPage] = useState<SettingsPageId | null>(null);
  const [diagnosticsOpen, setDiagnosticsOpen] = useState(false);
  const [logsOpen, setLogsOpen] = useState(false);

  // The clean feed always decodes, even when the windowed preview is paused.
  const controller = useCameraController({
    baseUrl,
    token,
    previewEnabled: prefs.previewEnabled || cleanFeed,
  });
  const enterCleanFeed = useCallback(() => setCleanFeed(true), []);
  const obs = useObs({ baseUrl, token, fitMode: prefs.fitMode, onEnterCleanFeed: enterCleanFeed });

  // "Start the camera after connecting": once per connection, when the phone
  // first streams and the Windows camera is installed but off.
  const autoStartedRef = useRef(false);
  useEffect(() => {
    if (autoStartedRef.current || !prefs.autoStartVirtualCamera) return;
    if (controller.phoneState !== 'streaming' || controller.virtualCamera !== 'off') return;
    autoStartedRef.current = true;
    controller.addDiag('startup', 'Starting OpenCamBridge Camera automatically');
    void controller.startVirtualCamera();
  }, [prefs.autoStartVirtualCamera, controller.phoneState, controller.virtualCamera, controller]);

  // Default profile: applied once per connection, only when the camera can do it.
  const defaultAppliedRef = useRef(false);
  useEffect(() => {
    if (defaultAppliedRef.current || !prefs.defaultProfileId) return;
    if (controller.phoneState !== 'streaming' || !controller.activeCam || controller.isSyncing) return;
    defaultAppliedRef.current = true;
    const profile = profiles.find(candidate => candidate.id === prefs.defaultProfileId);
    if (!profile || matchProfile([profile], controller.settings)) return;
    if (!profileAvailability(profile, controller.activeCam).available) {
      controller.addDiag('profile', `Default profile "${profile.name}" is not available on ${controller.activeCam.label}`);
      return;
    }
    void controller.applyProfile(profile);
  }, [prefs.defaultProfileId, controller.phoneState, controller.activeCam, controller.isSyncing, controller.settings, profiles, controller]);

  useEffect(() => {
    document.title = controller.isLive ? 'OpenCamBridge — live' : 'OpenCamBridge';
  }, [controller.isLive]);

  const openSettings = (page: SettingsPageId = 'general') => setSettingsPage(page);

  if (cleanFeed) {
    return (
      <CleanFeed
        controller={controller}
        fitMode={prefs.fitMode}
        mirrorPreview={prefs.mirrorPreview}
        onExit={() => setCleanFeed(false)}
      />
    );
  }

  return (
    <div className="studio">
      <TopBar
        controller={controller}
        advanced={prefs.advancedMode}
        onAdvancedChange={advancedMode => setPreferences({ advancedMode })}
        onOpenSettings={() => openSettings('general')}
        onOpenDiagnostics={() => setDiagnosticsOpen(true)}
        onManageProfiles={() => openSettings('profiles')}
      />

      <div className="studio__body">
        <CameraRail
          controller={controller}
          connection={connection}
          advanced={prefs.advancedMode}
          fitMode={prefs.fitMode}
          onFitModeChange={fitMode => setPreferences({ fitMode })}
          mirrorPreview={prefs.mirrorPreview}
          onMirrorPreviewChange={mirrorPreview => setPreferences({ mirrorPreview })}
          onSwitchDevice={async device => {
            // Leave nothing of the old phone's pipeline running on this PC.
            if (controller.vcamState?.process_running || controller.vcamState?.host_running) {
              await controller.stopVirtualCamera();
            }
            onSwitchDevice(device);
          }}
          onDisconnect={onDisconnect}
        />

        <main className="studio__stage">
          <PreviewStage
            controller={controller}
            fitMode={prefs.fitMode}
            mirrorPreview={prefs.mirrorPreview}
            previewEnabled={prefs.previewEnabled}
            onEnablePreview={() => setPreferences({ previewEnabled: true })}
          />
        </main>

        <OutputsRail
          controller={controller}
          obs={obs}
          advanced={prefs.advancedMode}
          onEnterCleanFeed={enterCleanFeed}
          onOpenIntegrations={() => openSettings('integrations')}
          onOpenDiagnostics={() => setDiagnosticsOpen(true)}
        />
      </div>

      <SettingsModal
        open={settingsPage !== null}
        page={settingsPage ?? 'general'}
        onClose={() => setSettingsPage(null)}
        context={{
          controller,
          connection,
          obs,
          onDisconnect,
          onOpenDiagnostics: () => { setSettingsPage(null); setDiagnosticsOpen(true); },
          onOpenLogs: () => setLogsOpen(true),
          navigate: page => setSettingsPage(page),
        }}
      />

      <DiagnosticsModal
        open={diagnosticsOpen}
        onClose={() => setDiagnosticsOpen(false)}
        controller={controller}
        onOpenLogs={() => setLogsOpen(true)}
      />

      {logsOpen && <LogsView onClose={() => setLogsOpen(false)} />}
    </div>
  );
}
