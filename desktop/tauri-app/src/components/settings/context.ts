import type { CameraController } from '../../state/useCameraController';
import type { ObsIntegration } from '../../state/useObs';
import type { ConnectionInfo } from '../../state/types';

export type SettingsPageId =
  | 'general'
  | 'profiles'
  | 'devices'
  | 'video'
  | 'connections'
  | 'integrations'
  | 'performance'
  | 'advanced'
  | 'updates'
  | 'about';

/** Everything a settings page may read or trigger. */
export interface SettingsContext {
  controller: CameraController;
  connection: ConnectionInfo;
  obs: ObsIntegration;
  onDisconnect: () => void;
  onOpenDiagnostics: () => void;
  onOpenLogs: () => void;
  navigate: (page: SettingsPageId) => void;
}
