import type { VirtualCamState } from './types';

/**
 * Plain-language states for the two things a user actually watches: the phone
 * and the Windows camera. Every screen derives its wording from these, so the
 * top bar, the rails and the preview can never disagree about what is going on.
 */

export type PhoneState = 'connecting' | 'streaming' | 'busy' | 'stopped' | 'failed' | 'offline';

export function phoneStateFrom(serverStatus: any): PhoneState {
  const lifecycle: string | undefined = serverStatus?.lifecycleState;
  if (!lifecycle) return 'connecting';
  if (lifecycle === 'OFFLINE') return 'offline';
  if (lifecycle === 'STREAMING') return 'streaming';
  if (lifecycle === 'STOPPED') return 'stopped';
  if (lifecycle === 'FAILED') return 'failed';
  if (['STARTING', 'RECONFIGURING', 'RECOVERING', 'STOPPING'].includes(lifecycle)) return 'busy';
  return 'connecting';
}

export const PHONE_STATE_LABEL: Record<PhoneState, string> = {
  connecting: 'Connecting',
  streaming: 'Connected',
  busy: 'Reconfiguring',
  stopped: 'Camera stopped',
  failed: 'Camera error',
  offline: 'Not reachable',
};

export type VirtualCameraPhase = 'checking' | 'not-installed' | 'off' | 'starting' | 'ready' | 'live';

export function virtualCameraPhase(state: VirtualCamState | null): VirtualCameraPhase {
  if (!state) return 'checking';
  if (!state.registered) return 'not-installed';
  if (state.virtual_camera_ready) return 'live';
  if (state.pipeline_ready) return 'ready';
  if (state.host_running) return 'starting';
  return 'off';
}

export const VIRTUAL_CAMERA_LABEL: Record<VirtualCameraPhase, string> = {
  checking: 'Checking…',
  'not-installed': 'Not installed',
  off: 'Off',
  starting: 'Starting…',
  ready: 'Ready',
  live: 'In use',
};

/** Tone vocabulary shared by status dots and badges. */
export type Tone = 'ok' | 'warn' | 'danger' | 'idle' | 'busy' | 'accent';

export const PHONE_STATE_TONE: Record<PhoneState, Tone> = {
  connecting: 'busy',
  streaming: 'ok',
  busy: 'busy',
  stopped: 'idle',
  failed: 'danger',
  offline: 'danger',
};

export const VIRTUAL_CAMERA_TONE: Record<VirtualCameraPhase, Tone> = {
  checking: 'idle',
  'not-installed': 'warn',
  off: 'idle',
  starting: 'busy',
  ready: 'ok',
  live: 'ok',
};
