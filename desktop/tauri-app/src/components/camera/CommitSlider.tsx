import { useEffect, useRef, useState } from 'react';
import { Slider } from '../primitives';

/**
 * A slider that moves freely while dragged and reports its value once, on
 * release. Quality settings are phone round-trips; sending one per pixel of
 * travel only produces revision conflicts. If the phone does not adopt the
 * committed value, the thumb returns to the authoritative one.
 */
export default function CommitSlider({
  value,
  min,
  max,
  step = 1,
  onCommit,
  disabled,
  label,
  format,
}: {
  value: number;
  min: number;
  max: number;
  step?: number;
  onCommit: (value: number) => void;
  disabled?: boolean;
  label: string;
  format?: (value: number) => string;
}) {
  const [draft, setDraft] = useState(value);
  const dragging = useRef(false);
  const latest = useRef(value);
  const resync = useRef(0);

  useEffect(() => {
    latest.current = value;
    if (!dragging.current) setDraft(value);
  }, [value]);

  useEffect(() => () => window.clearTimeout(resync.current), []);

  const commit = () => {
    dragging.current = false;
    if (draft === latest.current) return;
    onCommit(draft);
    window.clearTimeout(resync.current);
    resync.current = window.setTimeout(() => {
      if (!dragging.current) setDraft(latest.current);
    }, 4000);
  };

  return (
    <div
      className="commit-slider"
      onPointerDown={() => { dragging.current = true; }}
      onPointerUp={commit}
      onKeyUp={commit}
      onBlur={() => { if (dragging.current) commit(); }}
    >
      <Slider value={draft} min={min} max={max} step={step} onChange={setDraft} disabled={disabled} label={label} />
      {format && <span className="commit-slider__value tabular">{format(draft)}</span>}
    </div>
  );
}
