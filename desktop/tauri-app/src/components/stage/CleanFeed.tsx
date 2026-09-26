import { useEffect, useRef, useState } from 'react';
import { Minimize2 } from 'lucide-react';
import PreviewStage from './PreviewStage';
import type { CameraController } from '../../state/useCameraController';

/**
 * Chrome-free, full-window picture for OBS window capture. The exit control
 * only appears while the mouse moves, so a capture never records it; Escape
 * always exits.
 */
export default function CleanFeed({
  controller,
  fitMode,
  mirrorPreview,
  onExit,
}: {
  controller: CameraController;
  fitMode: 'fit' | 'fill';
  mirrorPreview: boolean;
  onExit: () => void;
}) {
  const [controlsVisible, setControlsVisible] = useState(true);
  const hideTimer = useRef(0);
  // The parent re-renders every poll with a fresh callback; keep the listeners
  // (and the hide timer) stable across those renders.
  const onExitRef = useRef(onExit);
  onExitRef.current = onExit;

  useEffect(() => {
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
  }, []);

  return (
    <div className={`clean-feed${controlsVisible ? '' : ' is-idle'}`}>
      <PreviewStage
        controller={controller}
        fitMode={fitMode}
        mirrorPreview={mirrorPreview}
        previewEnabled
        onEnablePreview={() => undefined}
        compact
      />
      <button type="button" className="clean-feed__exit" onClick={() => onExitRef.current()}>
        <Minimize2 size={14} /> Exit clean feed <kbd>Esc</kbd>
      </button>
    </div>
  );
}
