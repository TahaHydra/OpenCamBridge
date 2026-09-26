import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Minimize2 } from 'lucide-react';

/**
 * Chrome-free, full-window picture for OBS window capture.
 *
 * It always wraps the preview stage — as `display: contents` while inactive —
 * so entering or leaving clean feed never remounts the preview: the WebCodecs
 * stream (or the native fallback) keeps running instead of reconnecting. The
 * exit control only appears while the mouse moves, so a capture never records
 * it; Escape always exits.
 */
export default function CleanFeed({
  active,
  onExit,
  children,
}: {
  active: boolean;
  onExit: () => void;
  children: ReactNode;
}) {
  const [controlsVisible, setControlsVisible] = useState(true);
  const hideTimer = useRef(0);
  // The parent re-renders every poll with a fresh callback; keep the listeners
  // (and the hide timer) stable across those renders.
  const onExitRef = useRef(onExit);
  onExitRef.current = onExit;

  useEffect(() => {
    if (!active) return;
    const reveal = () => {
      setControlsVisible(true);
      window.clearTimeout(hideTimer.current);
      hideTimer.current = window.setTimeout(() => setControlsVisible(false), 2500);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onExitRef.current();
    };
    reveal();
    window.addEventListener('mousemove', reveal);
    window.addEventListener('keydown', onKey);
    return () => {
      window.clearTimeout(hideTimer.current);
      window.removeEventListener('mousemove', reveal);
      window.removeEventListener('keydown', onKey);
    };
  }, [active]);

  return (
    <div className={`clean-feed${active ? ' is-active' : ''}${active && !controlsVisible ? ' is-idle' : ''}`}>
      {children}
      {active && (
        <button type="button" className="clean-feed__exit" onClick={() => onExitRef.current()}>
          <Minimize2 size={14} /> Exit clean feed <kbd>Esc</kbd>
        </button>
      )}
    </div>
  );
}
