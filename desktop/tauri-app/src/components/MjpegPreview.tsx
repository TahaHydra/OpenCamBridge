import { useEffect, useRef, useState } from 'react';
import { phoneStreamFetch } from '../services/api';
import { MjpegFrames } from '../services/mjpegFrames';

/** Native HTTP keeps LAN JPEG preview independent of WebView CSP and CORS. */
export default function MjpegPreview({ baseUrl, token, fitMode }: { baseUrl: string; token?: string; fitMode: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let lastBytes = performance.now();
    let connection: AbortController | undefined;
    const stopConnection = () => { connection?.abort(); void reader?.cancel().catch(() => {}); };
    const watchdog = setInterval(() => { if (performance.now() - lastBytes > 5000) stopConnection(); }, 1000);
    const connect = async () => {
      connection = new AbortController();
      lastBytes = performance.now();
      try {
        const response = await phoneStreamFetch(new URL('/stream.mjpeg', baseUrl).toString(), token, { signal: connection.signal });
        if (abort.signal.aborted) { void response.body?.cancel(); return; }
        if (!response.ok || !response.body) throw new Error(`MJPEG HTTP ${response.status}`);
        reader = response.body.getReader();
        const parser = new MjpegFrames();
        while (!abort.signal.aborted) {
          const { value, done } = await reader.read();
          if (done) throw new Error('Phone stream closed');
          lastBytes = performance.now();
          const jpeg = parser.push(value);
          if (!jpeg) continue;
          const frame = await createImageBitmap(new Blob([new Uint8Array(jpeg)], { type: 'image/jpeg' }));
          try {
            const canvas = ref.current;
            if (!canvas || abort.signal.aborted) return;
            if (canvas.width !== frame.width || canvas.height !== frame.height) { canvas.width = frame.width; canvas.height = frame.height; }
            const ctx = canvas.getContext('2d', { alpha: false });
            if (!ctx) throw new Error('JPEG renderer unavailable');
            ctx.drawImage(frame, 0, 0);
            setError('');
          } finally { frame.close(); }
        }
      } catch (e) {
        if (!abort.signal.aborted) { setError(`Reconnecting MJPEG preview… ${String(e)}`); retry = setTimeout(() => void connect(), 1000); }
      } finally { stopConnection(); }
    };
    void connect();
    return () => { abort.abort(); stopConnection(); clearInterval(watchdog); clearTimeout(retry); };
  }, [baseUrl, token]);
  return <><canvas ref={ref} className={`preview-img ${fitMode === 'fit' ? 'fit-contain' : 'fit-cover'}`} />{error && <div className="preview-overlay">{error}</div>}</>;
}
