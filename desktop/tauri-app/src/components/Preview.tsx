import { useState, useEffect, useRef } from 'react';
import { CameraOff, RefreshCw } from 'lucide-react';

interface PreviewProps {
  baseUrl: string;
  fitMode: string;
  serverStatus: any;
}

export default function Preview({ baseUrl, fitMode, serverStatus }: PreviewProps) {
  const [timestamp, setTimestamp] = useState(Date.now());
  const [isError, setIsError] = useState(false);
  const imgRef = useRef<HTMLImageElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);
  const [boxSize, setBoxSize] = useState({ w: 0, h: 0 });

  const mjpegUrl = `${baseUrl}/stream.mjpeg?ts=${timestamp}`;

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
    setIsError(false);
  };

  const reloadPreview = () => {
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

  const rot = parseInt(serverStatus?.displayRotation || '0') || 0;
  const layout = serverStatus?.aspectRatio || '16:9';
  const mirror = serverStatus?.mirror || false;

  let boxClass = 'layout-landscape';
  if (layout === '9:16') boxClass = 'layout-portrait';
  else if (layout === '1:1') boxClass = 'layout-square';

  const rotatorStyle: any = {
    transform: `translate(-50%, -50%) rotate(${rot}deg) scaleX(${mirror ? -1 : 1})`,
  };
  if (rot === 90 || rot === 270) {
    rotatorStyle.width = boxSize.h ? `${boxSize.h}px` : '100%';
    rotatorStyle.height = boxSize.w ? `${boxSize.w}px` : '100%';
  } else {
    rotatorStyle.width = boxSize.w ? `${boxSize.w}px` : '100%';
    rotatorStyle.height = boxSize.h ? `${boxSize.h}px` : '100%';
  }

  return (
    <div className="preview-wrapper animate-fade">
      <div className={`preview-stage ${boxClass}`} ref={boxRef}>
        <div className="stream-rotator" style={rotatorStyle}>
          <img
            ref={imgRef}
            src={mjpegUrl}
            className={`preview-img ${fitMode === 'fit' ? 'fit-contain' : 'fit-cover'}`}
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
      </div>
    </div>
  );
}
