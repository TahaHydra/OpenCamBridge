import { useState, useEffect, useCallback } from 'react';
import { X, Copy, Trash2, FolderOpen, RefreshCw } from 'lucide-react';
import { readTail, clearLog, openLogsFolder, getLogPath, logTestMarker } from '../services/logging';

interface LogsViewProps {
  onClose: () => void;
}

// Persistent session-log viewer. Reads the on-disk session file
// (C:\ProgramData\OpenCamBridge\logs) so what you copy/send is exactly what
// was recorded, not just the in-memory diagnostics.
export default function LogsView({ onClose }: LogsViewProps) {
  const [text, setText] = useState('');
  const [path, setPath] = useState('');
  const [auto, setAuto] = useState(true);

  const refresh = useCallback(async () => {
    setText(await readTail(600));
    setPath(await getLogPath());
  }, []);

  useEffect(() => { refresh(); }, [refresh]);
  useEffect(() => {
    if (!auto) return;
    const id = setInterval(refresh, 2000);
    return () => clearInterval(id);
  }, [auto, refresh]);

  return (
    <div style={{ position: 'fixed', inset: 0, zIndex: 9998, background: 'rgba(0,0,0,0.85)', display: 'flex', flexDirection: 'column', padding: 20 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <strong style={{ color: '#4dabf7', fontSize: '1rem' }}>Session Logs</strong>
        <span style={{ color: '#666', fontSize: '0.72rem', wordBreak: 'break-all', flex: 1 }}>{path || 'No session file yet — connect to start one.'}</span>
        <button className="btn btn-secondary" style={{ padding: '4px 10px', fontSize: '0.75rem' }} onClick={refresh}><RefreshCw size={13} /> Refresh</button>
        <button className="btn btn-secondary" style={{ padding: '4px 10px', fontSize: '0.75rem' }} onClick={() => setAuto(a => !a)}>{auto ? 'Auto: ON' : 'Auto: OFF'}</button>
        <button className="btn btn-secondary" style={{ padding: '4px 10px', fontSize: '0.75rem' }} onClick={() => navigator.clipboard.writeText(text)}><Copy size={13} /> Copy</button>
        <button className="btn btn-secondary" style={{ padding: '4px 10px', fontSize: '0.75rem' }} onClick={() => logTestMarker('END', 'manual marker from Logs view').then(refresh)}>Mark</button>
        <button className="btn btn-secondary" style={{ padding: '4px 10px', fontSize: '0.75rem' }} onClick={openLogsFolder}><FolderOpen size={13} /> Folder</button>
        <button className="btn btn-secondary" style={{ padding: '4px 10px', fontSize: '0.75rem' }} onClick={() => clearLog().then(refresh)}><Trash2 size={13} /> Clear</button>
        <button className="btn btn-secondary" style={{ padding: '4px 10px', fontSize: '0.75rem' }} onClick={onClose}><X size={13} /> Close</button>
      </div>
      <pre style={{ flex: 1, overflow: 'auto', background: '#0a0a0a', border: '1px solid #222', borderRadius: 8, padding: 12, margin: 0, fontFamily: 'monospace', fontSize: '0.72rem', color: '#9aa', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
        {text || 'No log entries yet.'}
      </pre>
    </div>
  );
}
