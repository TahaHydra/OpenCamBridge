import { useEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type RefObject } from 'react';
import { centeredCrop, containRect, moveCrop, resizeCrop, type CropRect } from '../../services/outputFraming.js';
import '../../styles/framing.css';

type Drag = { pointerId: number; x: number; y: number; crop: CropRect; corner: string };

export default function CropOverlay({ viewport, source, crop, outputAspect, showAppTrim, mirrored, onChange }: {
  viewport: RefObject<HTMLDivElement | null>;
  source: { width: number; height: number };
  crop: CropRect;
  outputAspect: number;
  showAppTrim: boolean;
  mirrored: boolean;
  onChange: (crop: CropRect) => void;
}) {
  const [box, setBox] = useState({ width: 0, height: 0 });
  const drag = useRef<Drag | null>(null);
  // A rotation changes the coordinate surface. Finish the old gesture instead
  // of applying its accumulated pointer movement to a differently shaped image.
  useEffect(() => { drag.current = null; }, [source.width, source.height, mirrored]);
  useEffect(() => {
    const element = viewport.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setBox({ width: entry.contentRect.width, height: entry.contentRect.height }));
    observer.observe(element);
    return () => observer.disconnect();
  }, [viewport]);
  const image = containRect(box.width, box.height, source.width, source.height);
  const effective = centeredCrop(source.width * crop.width, source.height * crop.height, outputAspect);
  const trimmed = showAppTrim && (effective.width < 0.999 || effective.height < 0.999);
  const pointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || !image.width) return;
    const target = event.target as HTMLElement;
    const corner = target.dataset.corner || '';
    event.preventDefault();
    event.currentTarget.focus();
    event.currentTarget.setPointerCapture(event.pointerId);
    drag.current = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, crop, corner };
  };
  const pointerMove = (event: PointerEvent<HTMLDivElement>) => {
    const active = drag.current;
    if (!active || active.pointerId !== event.pointerId) return;
    const dx = (event.clientX - active.x) / image.width * (mirrored ? -1 : 1);
    const dy = (event.clientY - active.y) / image.height;
    onChange(active.corner ? resizeCrop(active.crop, active.corner, dx, dy) : moveCrop(active.crop, dx, dy));
  };
  const keyboard = (event: KeyboardEvent<HTMLDivElement>) => {
    const steps: Record<string, [number, number]> = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    const direction = steps[event.key];
    if (!direction) return;
    event.preventDefault();
    const amount = event.shiftKey ? 0.05 : 0.01;
    const dx = direction[0] * amount * (mirrored ? -1 : 1), dy = direction[1] * amount;
    const corner = (event.target as HTMLElement).dataset.corner;
    onChange(corner ? resizeCrop(crop, corner, dx, dy) : moveCrop(crop, dx, dy));
  };
  if (!image.width) return null;
  return (
    <div className="crop-overlay" style={{ left: image.x, top: image.y, width: image.width, height: image.height }}>
      <div className="crop-selection" role="group" tabIndex={0}
        aria-label="Output crop. Drag or use arrow keys to move; focus a corner and use arrow keys to resize."
        style={{ left: `${(mirrored ? 1 - crop.x - crop.width : crop.x) * 100}%`, top: `${crop.y * 100}%`, width: `${crop.width * 100}%`, height: `${crop.height * 100}%` }}
        onPointerDown={pointerDown} onPointerMove={pointerMove} onPointerUp={() => { drag.current = null; }}
        onPointerCancel={() => { drag.current = null; }} onLostPointerCapture={() => { drag.current = null; }} onKeyDown={keyboard}>
        <span className="crop-selection__label">Output crop</span>
        <span className="crop-selection__grid" />
        {trimmed && <span className="crop-selection__effective" title="Area delivered at the connected app’s aspect ratio"
          style={{ left: `${effective.x * 100}%`, top: `${effective.y * 100}%`, width: `${effective.width * 100}%`, height: `${effective.height * 100}%` }} />}
        {(['nw', 'ne', 'sw', 'se'] as const).map(corner => {
          const visualCorner = mirrored ? corner.replace(/[we]/g, direction => direction === 'w' ? 'e' : 'w') : corner;
          const name = `${visualCorner.startsWith('n') ? 'top' : 'bottom'} ${visualCorner.endsWith('w') ? 'left' : 'right'}`;
          return <button key={corner} type="button" className={`crop-handle crop-handle--${visualCorner}`} data-corner={corner} aria-label={`Resize ${name} crop corner`} />;
        })}
      </div>
    </div>
  );
}
