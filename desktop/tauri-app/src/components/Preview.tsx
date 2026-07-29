import { useState, useEffect, useRef } from 'react';
import { CameraOff, RefreshCw } from 'lucide-react';
import { buildUrl } from '../services/api';
import Nv12RingPreview from './Nv12RingPreview';

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

  const lifecycle: string = serverStatus?.lifecycleState || 'UNKNOWN';
  const frameRev: number = Number(serverStatus?.latestFrameRevision || 0);
  const lastRevRef = useRef(0);
  const staleSinceRef = useRef(0);
  const lastReloadRef = useRef(0);

  // Metrics-based recovery. /stream.mjpeg is multipart, so onLoad/onError are
  // unreliable during a rebind (the socket opens but no complete frame arrives).
  // Drive recovery from the phone's frame counter + lifecycle instead:
  //  - frame counter advanced while we were blank -> reconnect once,
  //  - lifecycle STREAMING but no fresh frame for a while -> reconnect (backoff),
  //  - lifecycle stopped/error/offline -> show a message, do NOT spam reconnect.
  useEffect(() => {
    const now = Date.now();
    if (frameRev > lastRevRef.current) {
      lastRevRef.current = frameRev;
      staleSinceRef.current = 0;
      if (isError) reloadPreview(); // frames resumed after a blank/rebind
      return;
    }
    if (lifecycle === 'STREAMING') {
      if (staleSinceRef.current === 0) staleSinceRef.current = now;
      // No new frame for >5s while "streaming" -> reconnect, at most once per
      // 5s so we never spam.
      if (now - staleSinceRef.current > 5000 && now - lastReloadRef.current > 5000) {
        lastReloadRef.current = now;
        reloadPreview();
      }
    } else {
      staleSinceRef.current = 0; // not streaming; nothing to wait for
    }
  }, [frameRev, lifecycle, isError]);

  useEffect(() => {
    const handleReload = () => reloadPreview();
    window.addEventListener('reload-preview', handleReload);
    return () => window.removeEventListener('reload-preview', handleReload);
  }, []);

  // Human-readable state for the overlay.
  const rebinding = lifecycle === 'STARTING' || lifecycle === 'RECONFIGURING' || lifecycle === 'RECOVERING';
  const stopped = lifecycle === 'STOPPED' || lifecycle === 'STOPPING';
  const offline = lifecycle === 'OFFLINE' || lifecycle === 'UNKNOWN';
  const cameraError = lifecycle === 'FAILED';
  const statusMsg = offline ? 'Android server unreachable'
    : cameraError ? 'Camera error — check the phone Logs tab'
    : stopped ? 'Camera stopped'
    : rebinding ? 'Rebinding… waiting for camera'
    : 'Waiting for camera frames…';
  // Overlay only when we are NOT showing live frames.
  const showOverlay = isError || rebinding || stopped || offline || cameraError;

  const layout = serverStatus?.aspectRatio || 'auto';

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

  // MJPEG pixels arrive fully transformed (rotation and mirror) from Android.
  const rotatorStyle: any = {
    transform: 'translate(-50%, -50%)',
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
  const h264Primary = (serverStatus?.activeStreamMode || serverStatus?.streamMode) === 'h264';
  const h264Active = h264Primary && lifecycle === 'STREAMING';
  const previewSessionKey = [
    timestamp,
    serverStatus?.pipelineGeneration ?? serverStatus?.snapshot?.generation ?? 0,
    serverStatus?.activeStreamMode || serverStatus?.streamMode || '',
    serverStatus?.encodedWidth || 0,
    serverStatus?.encodedHeight || 0,
    serverStatus?.selectedFps || serverStatus?.fps || 0,
  ].join(':');

  return (
    <div className="preview-wrapper animate-fade">
      <div className={`preview-stage ${boxClass}`} ref={boxRef}>
        {!h264Primary && <div className="stream-rotator" style={rotatorStyle}>
          <img
            ref={imgRef}
            src={mjpegUrl}
            className={`preview-img ${effectiveFit === 'fit' ? 'fit-contain' : 'fit-cover'}`}
            onError={handleError}
            onLoad={handleLoad}
            alt="Live Stream"
            style={{ opacity: isError ? 0 : 1 }}
          />
        </div>}

        {h264Active ? (
          // Every producer generation gets a fresh renderer. A stale READY flag,
          // sequence cursor or texture can never survive a rebind/codec switch.
          <Nv12RingPreview key={previewSessionKey} fitMode={effectiveFit} />
        ) : showOverlay && (
          <div className="preview-overlay">
            {rebinding ? <RefreshCw size={48} opacity={0.6} className="animate-spin" /> : <CameraOff size={48} opacity={0.5} />}
            <div>{statusMsg}</div>
            {!rebinding && (
              <button className="btn btn-secondary" onClick={reloadPreview}>
                <RefreshCw size={16} /> Retry Now
              </button>
            )}
          </div>
        )}

        {(h264Active || !showOverlay) && (
          <div className="vf-actions">
            <button className="btn btn--sm" onClick={reloadPreview}>
              <RefreshCw size={12} /> Reload
            </button>
          </div>
        )}

        {/* Viewfinder readout: what is actually being encoded, on the glass
            where the operator is already looking. */}
        {(h264Active || !showOverlay) && (
          <div className="vf-hud">
            <b>{serverStatus?.encodedWidth || '—'}×{serverStatus?.encodedHeight || '—'}</b>
            <i>/</i>
            <b>{serverStatus?.snapshot?.actual?.encodedFps || serverStatus?.snapshot?.selected?.fps || serverStatus?.fps || '—'}</b> fps
            <i>/</i>
            <b>{(serverStatus?.activeStreamMode || serverStatus?.streamMode || '—').toString().toUpperCase()}</b>
            {serverStatus?.mirror && <><i>/</i>MIRROR</>}
            {Number(serverStatus?.rotationDegrees) > 0 && <><i>/</i>{serverStatus.rotationDegrees}°</>}
          </div>
        )}

      </div>
    </div>
  );
}
