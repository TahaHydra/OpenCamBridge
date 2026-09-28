import { Crop, RotateCcw } from 'lucide-react';
import { Button } from '../primitives';
import { DEFAULT_FRAMING, FRAMING_PRESETS, resolveFraming, type FramingPreset } from '../../services/outputFraming.js';
import { setPreferences, usePreferences } from '../../state/preferences';
import type { CameraController } from '../../state/useCameraController';
import '../../styles/framing.css';

export default function OutputFramingControl({ controller }: { controller: CameraController }) {
  const prefs = usePreferences();
  const source = controller.sourceDimensions;
  const framing = resolveFraming(prefs.outputFraming, source.width, source.height);
  const ring = controller.vcamState?.metrics?.ring;
  const select = (preset: FramingPreset) => {
    const next = resolveFraming({ ...framing, preset }, source.width, source.height);
    setPreferences({ outputFraming: next, editOutputFraming: preset !== 'fit', previewEnabled: true });
  };
  return (
    <section className="output-card">
      <header className="output-card__head">
        <span className="output-card__icon"><Crop size={17} /></span>
        <h3 className="output-card__title">Output framing</h3>
      </header>
      <p className="output-card__description">Choose the area sent to the virtual camera. The phone keeps capturing the full picture.</p>
      <div className="framing-presets" role="group" aria-label="Output framing preset">
        {FRAMING_PRESETS.map(preset => <button type="button" key={preset.value} aria-pressed={framing.preset === preset.value}
          className={`framing-preset${framing.preset === preset.value ? ' is-selected' : ''}`}
          onClick={() => select(preset.value)}>{preset.label}</button>)}
      </div>
      <p className="output-card__note framing-note">
        {framing.mode === 'fit' ? 'Full picture, with black bars when the app uses a different shape.'
          : framing.mode === 'fill' ? 'Fills the connected app’s frame by cropping the center.'
            : 'Drag the box or its corners in the preview. Apps with a different shape trim the selection further; the dashed line shows that area.'}
      </p>
      {ring && ring.negotiated_width > 0 && <div className="output-card__format tabular">App output: {ring.negotiated_width}×{ring.negotiated_height}</div>}
      <div className="framing-actions">
        <Button size="sm" onClick={() => setPreferences({ editOutputFraming: !prefs.editOutputFraming, previewEnabled: true })}>
          {prefs.editOutputFraming ? 'Hide crop editor' : 'Edit crop'}
        </Button>
        <Button size="sm" variant="ghost" icon={<RotateCcw size={13} />} onClick={() => setPreferences({ outputFraming: DEFAULT_FRAMING })}>Reset</Button>
      </div>
    </section>
  );
}
