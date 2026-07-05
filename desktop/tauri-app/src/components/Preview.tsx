import { useState, useEffect, useRef } from 'react';
import { CameraOff, RefreshCw } from 'lucide-react';
import { buildUrl } from '../services/api';

interface PreviewProps {
  baseUrl: string;
  token?: string;
  fitMode: string;
  serverStatus: any;
}

export default function Preview({ baseUrl, token, fitMode, serverStatus }: PreviewProps) {
  const [timestamp, setTimestamp] = useState(Date.now());
  const [isError, setIsError] = useState(false);
  const imgRef = useRef<HTMLImageElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const [boxSize, setBoxSize] = useState({ w: 0, h: 0 });
  // Whether a frame has decoded since the last (re)load. Used by the watchdog.
  const loadedRef = useRef(false);

  const mjpegUrl = buildUrl(baseUrl, '/stream.mjpeg', token, { ts: String(timestamp) });

  useEffect(() => {
    if (!boxRef.current) return;
    const observer = new ResizeObserver((entries) => {
      setBoxSize({ w: entries[0].contentRect.width, h: entries[0].contentRect.height });
    });
    observer.observe(boxRef.current);
    return () => observer.disconnect();
  }, []);

  const handleError = () => {
    setIsError(true);
  };

  const handleLoad = () => {
    loadedRef.current = true;
    setIsError(false);
  };

  const reloadPreview = () => {
    loadedRef.current = false;
    setIsError(false);
    setTimestamp(Date.now());
  };

  // Auto-retry every 3 seconds if error
  useEffect(() => {
    if (isError) {
      const timer = setTimeout(() => {
        reloadPreview();
      }, 3000);
      return () => clearTimeout(timer);
    }
  }, [isError]);

  // Watchdog: /stream.mjpeg is multipart. When a (re)connect lands during a
  // camera rebind, the stream is open but sends no frame yet, so the <img>
  // fires NEITHER onload NOR onerror and the preview would sit blank forever.
  // If no frame decodes within the timeout, force an error so the auto-retry
  // above reconnects. Re-armed on every reload (timestamp change).
  useEffect(() => {
    loadedRef.current = false;
    const t = setTimeout(() => {
      if (!loadedRef.current) setIsError(true);
    }, 6000);
    return () => clearTimeout(t);
  }, [timestamp]);

  useEffect(() => {
    const handleReload = () => reloadPreview();
    window.addEventListener('reload-preview', handleReload);
    return () => window.removeEventListener('reload-preview', handleReload);
  }, []);

  const layout = serverStatus?.aspectRatio || 'auto';
  const mirror = serverStatus?.mirror || false;

  // The phone streams already-rotated, always-upright frames. The orientation
  // mode only shapes this VIEW:
  //  - 'auto': the preview box follows the frame the phone is sending right
  //    now (hold the phone vertical -> 9:16 box, horizontal -> 16:9 box).
  //  - '16:9' / '9:16': the box is pinned; mismatching frames letterbox.
  const framePortrait =
    (serverStatus?.encodedHeight ?? 0) > (serverStatus?.encodedWidth ?? 0);
  const boxPortrait =
    layout === '9:16' ? true :
    layout === '16:9' ? false :
    framePortrait; // auto
  const boxClass = boxPortrait ? 'layout-portrait' : 'layout-landscape';

  // No content rotation here (frames arrive rotated) — mirror only.
  const rotatorStyle: any = {
    transform: `translate(-50%, -50%) scaleX(${mirror ? -1 : 1})`,
    width: boxSize.w ? `${boxSize.w}px` : '100%',
    height: boxSize.h ? `${boxSize.h}px` : '100%',
  };

  // Fit rule per mode:
  // - Pinned Horizontal (16:9) with a portrait frame: letterbox, because 16:9
  //   IS the virtual camera canvas and apps see exactly this pillarboxed view.
  // - Pinned Vertical (9:16) with a landscape frame: honor "fill" (center-crop)
  //   so the vertical canvas is actually filled edge-to-edge — a letterboxed
  //   16:9 band jammed inside a 9:16 box is useless as a vertical view.
  // - Auto: box always matches the frame, so the user's fit mode applies as-is.
  const effectiveFit =
    layout === '16:9' && framePortrait ? 'fit' : fitMode;

  return (
    <div className="preview-wrapper animate-fade">
      <div className={`preview-stage ${boxClass}`} ref={boxRef}>
        <div className="stream-rotator" style={rotatorStyle}>
          <img
            ref={imgRef}
            src={mjpegUrl}
            className={`preview-img ${effectiveFit === 'fit' ? 'fit-contain' : 'fit-cover'}`}
            onError={handleError}
            onLoad={handleLoad}
            alt="Live Stream"
            style={{ opacity: isError ? 0 : 1 }}
          />
        </div>

        {isError && (
          <div className="preview-overlay">
            <CameraOff size={48} opacity={0.5} />
            <div>Stream Offline</div>
            <button className="btn btn-secondary" onClick={reloadPreview}>
              <RefreshCw size={16} /> Retry Now
            </button>
          </div>
        )}

        {!isError && (
          <button
            className="btn btn-secondary"
            style={{ position: 'absolute', top: 16, right: 16, padding: '6px 12px', fontSize: '0.8rem', background: 'rgba(0,0,0,0.5)' }}
            onClick={reloadPreview}
          >
            <RefreshCw size={14} /> Reload
          </button>
        )}

        {!isError && serverStatus?.streamMode === 'h264' && (
          <div style={{ position: 'absolute', bottom: 16, left: 16, padding: '4px 10px', fontSize: '0.7rem', color: '#ffb300', background: 'rgba(0,0,0,0.6)', border: '1px solid rgba(255,179,0,0.4)', borderRadius: 4 }}>
            H.264 active — this preview is a ~5 fps snapshot; the virtual camera runs at full rate
          </div>
        )}
      </div>
    </div>
  );
}
