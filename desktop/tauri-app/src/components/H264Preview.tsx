import { useEffect, useRef, useState } from 'react';
import { fetch as nativeFetch } from '@tauri-apps/plugin-http';
import { isTauriRuntime } from '../services/desktopBridge';
import { EMPTY_PREVIEW_DIAGNOSTICS, publishPreviewDiagnostics, PREVIEW_FALLBACK_EVENT } from '../services/previewDiagnostics';
import Nv12RingPreview from './Nv12RingPreview';

export default function H264Preview({ baseUrl, token, fitMode }: { baseUrl: string; token?: string; fitMode: string }) {
  const surfaceRef = useRef<HTMLDivElement>(null);
  const [fallback, setFallback] = useState('');
  const [message, setMessage] = useState('Starting full-resolution H.264 preview…');
  const [session, setSession] = useState(0);
  const failures = useRef(0);

  useEffect(() => {
    window.dispatchEvent(new CustomEvent(PREVIEW_FALLBACK_EVENT, { detail: Boolean(fallback) }));
    return () => { window.dispatchEvent(new CustomEvent(PREVIEW_FALLBACK_EVENT, { detail: false })); };
  }, [fallback]);

  useEffect(() => {
    if (fallback) return;
    const surface = surfaceRef.current;
    const canvas = document.createElement('canvas');
    canvas.className = `preview-img ${fitMode === 'fit' ? 'fit-contain' : 'fit-cover'}`;
    canvas.style.width = '100%'; canvas.style.height = '100%';
    if (!surface || !canvas.transferControlToOffscreen) { setFallback('Native preview: this WebView lacks OffscreenCanvas.'); return; }
    surface.appendChild(canvas);
    const abort = new AbortController();
    let worker: Worker | null = null;
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    let disposed = false;
    let retry = 0;
    let watchdog = 0;
    let lastBytesAt = performance.now();
    let settle: (() => void) | undefined;
    let rejectPending: ((error: Error) => void) | undefined;
    let totalDisplayed = 0;
    let totalBytes = 0;
    let chunks = 0;
    const dispose = () => {
      abort.abort();
      void reader?.cancel().catch(() => {});
      worker?.terminate();
      window.clearInterval(watchdog);
      rejectPending?.(new Error('Preview closed'));
      settle = undefined; rejectPending = undefined;
    };
    const onFailure = (error: unknown, unsupported = false) => {
      if (disposed) return;
      disposed = true;
      dispose();
      const reason = error instanceof Error ? error.message : String(error);
      publishPreviewDiagnostics({ ...EMPTY_PREVIEW_DIAGNOSTICS, renderer: 'webcodecs', lastError: reason });
      if (unsupported || ++failures.current >= 3) setFallback(`Native compatibility preview: ${reason}`);
      else {
        setMessage('Reconnecting H.264 preview…');
        retry = window.setTimeout(() => setSession(value => value + 1), 750);
      }
    };
    const waitForWorker = () => new Promise<void>((resolve, reject) => {
      settle = resolve; rejectPending = reject;
    });
    async function start() {
      worker = new Worker(new URL('../services/h264Preview.worker.ts', import.meta.url), { type: 'module' });
      worker.onerror = event => onFailure(new Error(event.message || 'Preview worker failed'), true);
      worker.onmessage = ({ data }) => {
        if (disposed) return;
        if (data.type === 'ready' || data.type === 'consumed') { settle?.(); settle = undefined; rejectPending = undefined; }
        else if (data.type === 'error') onFailure(new Error(data.message), data.unsupported);
        else if (data.type === 'stats') {
          totalDisplayed += data.displayed;
          if (data.displayed > 0) { failures.current = 0; setMessage(''); }
          publishPreviewDiagnostics({ ...EMPTY_PREVIEW_DIAGNOSTICS, renderer: 'webcodecs', ready: totalDisplayed > 0,
            nonEmptyResponses: chunks, ipcPayloadBytes: totalBytes, parsedWidth: data.width, parsedHeight: data.height,
            rendererDisplayCount: totalDisplayed, lastDisplayedSequence: totalDisplayed,
            previewReceivedFps: data.decodedFps, previewDisplayedFps: data.displayedFps,
            previewSkippedSequences: data.skipped, previewUploadMs: data.drawMs, decodeMs: data.decodeMs,
            queuedFrames: data.queue, decoderQueue: data.decoderQueue, bufferMs: data.bufferMs,
            sourceFps: data.sourceFps, colorMatrix: data.colorMatrix, colorRange: data.colorRange });
        }
      };
      watchdog = window.setInterval(() => {
        if (performance.now() - lastBytesAt > 8000) onFailure(new Error('Preview stream timed out'));
      }, 1000);
      const ready = waitForWorker();
      const offscreen = canvas.transferControlToOffscreen();
      worker.postMessage({ type: 'init', canvas: offscreen }, [offscreen]);
      await ready;
      if (disposed) return;
      const url = new URL('/stream.ocb2', baseUrl);
      const request = {
        signal: abort.signal, headers: new Headers(token ? { 'X-OpenCamBridge-Token': token } : {}),
        maxRedirections: 0, connectTimeout: 5000,
      };
      const response = await (isTauriRuntime() ? nativeFetch : fetch)(url.toString(), request);
      if (disposed) { await response.body?.cancel(); return; }
      if (!response.ok || !response.body) { await response.body?.cancel(); throw new Error(`Preview HTTP ${response.status}`); }
      reader = response.body.getReader();
      while (!disposed) {
        const { value, done } = await reader.read();
        if (done) throw new Error('Preview stream closed');
        lastBytesAt = performance.now(); totalBytes += value.byteLength; chunks++;
        // One compressed chunk in flight. No raw-frame IPC and no unbounded
        // postMessage queue if decoding stalls or the window is occluded.
        const consumed = waitForWorker();
        const bytes = value.byteOffset === 0 && value.byteLength === value.buffer.byteLength ? value : value.slice();
        worker.postMessage({ type: 'chunk', bytes: bytes.buffer }, [bytes.buffer]);
        await consumed;
      }
    }
    void start().catch(onFailure);
    return () => { disposed = true; dispose(); canvas.remove(); window.clearTimeout(retry); publishPreviewDiagnostics({ ...EMPTY_PREVIEW_DIAGNOSTICS }); };
  }, [baseUrl, token, session, fallback]);

  useEffect(() => {
    const canvas = surfaceRef.current?.querySelector('canvas');
    if (canvas) canvas.className = `preview-img ${fitMode === 'fit' ? 'fit-contain' : 'fit-cover'}`;
  }, [fitMode]);

  if (fallback) return <><Nv12RingPreview fitMode={fitMode} /><div className="vf-hud vf-hud--notice" title={fallback}>Compatibility preview · 960px maximum</div></>;
  // Create a canvas per effect, including React StrictMode's setup/cleanup probe.
  return <><div ref={surfaceRef} style={{ width: '100%', height: '100%' }} />
    {message && <div className="preview-overlay">{message}</div>}</>;
}
