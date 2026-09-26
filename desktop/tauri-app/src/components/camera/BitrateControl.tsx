import CommitSlider from './CommitSlider';
import { Segmented } from '../primitives';
import { automaticBitrateMbps, heightLabel } from '../../services/profilePolicy.js';
import type { CameraController } from '../../state/useCameraController';
import type { CameraSettings } from '../../state/types';

/**
 * H.264 bitrate, as the phone models it: Automatic (the phone's recommended
 * rate for the resolution and frame rate, kept within what its encoder
 * supports) or Manual (the user's own rate). Moving to Manual is always an
 * explicit choice here — the slider only exists in Manual.
 */

export const BITRATE_HINT = {
  auto: "Recommended. The phone picks it from the resolution, frame rate and what its encoder supports.",
  manual: 'Your fixed bitrate, applied live and kept after restarts. The encoder may cap it.',
} as const;

/** "10 Mb/s for 1080p · 30 FPS" */
export function automaticBitrateLabel(settings: CameraSettings): string {
  return `${automaticBitrateMbps(settings.width, settings.height, settings.fps)} Mb/s for ${heightLabel(settings.height)} · ${settings.fps} FPS`;
}

export function BitrateModeSwitch({ controller, disabled }: { controller: CameraController; disabled?: boolean }) {
  return (
    <Segmented
      size="sm"
      label="H.264 bitrate"
      value={controller.settings.h264BitrateMode}
      disabled={disabled}
      options={[
        { value: 'auto', label: 'Automatic', title: 'Let the phone pick the bitrate for this mode' },
        { value: 'manual', label: 'Manual', title: 'Set your own bitrate' },
      ]}
      onChange={mode => void controller.updateBitrateMode(mode)}
    />
  );
}

export function ManualBitrateSlider({ controller, disabled }: { controller: CameraController; disabled?: boolean }) {
  return (
    <CommitSlider
      label="Manual H.264 bitrate"
      value={Math.round(controller.settings.h264Bitrate / 1_000_000)}
      min={1}
      max={50}
      disabled={disabled}
      format={value => `${value} Mb/s`}
      onCommit={value => void controller.updateManualBitrate(value * 1_000_000)}
    />
  );
}
