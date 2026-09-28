import { useState, useEffect } from 'react';
import { CameraOff, RefreshCw } from 'lucide-react';
import H264Preview from './H264Preview';
import MjpegPreview from './MjpegPreview';

interface PreviewProps { baseUrl: string; token?: string; fitMode: string; serverStatus: any }

/** Full source only. Output framing belongs to the native compositor. Renderers
 * own transport recovery: MJPEG counters never restart a healthy WebCodecs session. */
export default function Preview({ baseUrl, token, fitMode, serverStatus }: PreviewProps) {
  const [reload, setReload] = useState(0);
  useEffect(() => {
    const requested = () => setReload(n => n + 1);
    window.addEventListener('reload-preview', requested);
    return () => window.removeEventListener('reload-preview', requested);
  }, []);
  const lifecycle = serverStatus?.lifecycleState || 'UNKNOWN';
  const live = lifecycle === 'STREAMING';
  const h264 = (serverStatus?.activeStreamMode || serverStatus?.streamMode) === 'h264';
  const session = [reload, serverStatus?.pipelineGeneration ?? serverStatus?.snapshot?.generation ?? 0,
    h264, serverStatus?.encodedWidth || 0, serverStatus?.encodedHeight || 0,
    serverStatus?.selectedFps || serverStatus?.fps || 0].join(':');
  return <div className="preview-wrapper">
    <div className="preview-stage" style={{ width: '100%', height: '100%' }}>
      {live ? h264
        ? <H264Preview key={session} baseUrl={baseUrl} token={token} fitMode={fitMode} />
        : <MjpegPreview key={session} baseUrl={baseUrl} token={token} fitMode={fitMode} />
        : <div className="preview-overlay"><CameraOff size={48} opacity={0.5} />
          <div>{['STARTING', 'RECONFIGURING', 'RECOVERING'].includes(lifecycle)
            ? 'Waiting for camera frames…' : 'Phone stopped or disconnected / Start again from the phone'}</div>
        </div>}
      {live && <div className="vf-actions"><button className="btn btn--sm" onClick={() => setReload(n => n + 1)}>
        <RefreshCw size={12} /> Reload
      </button></div>}
    </div>
  </div>;
}
