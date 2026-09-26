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
    <div className="sheet animate-fade">
      <div className="sheet__bar">
        <h2 className="legend" style={{ flex: 'none' }}>Session log</h2>
        <span className="sheet__path mono">{path || 'No session file yet — connect to start one.'}</span>
        <button className="btn btn--sm" onClick={refresh}><RefreshCw size={12} /> Refresh</button>
        <button className="btn btn--sm" onClick={() => setAuto(a => !a)}>
          <span className={`lamp ${auto ? 'lamp--on' : ''}`} /> Auto
        </button>
        <button className="btn btn--sm" onClick={() => navigator.clipboard.writeText(text)}><Copy size={12} /> Copy</button>
        <button className="btn btn--sm" onClick={() => logTestMarker('END', 'manual marker from Logs view').then(refresh)}>Mark</button>
        <button className="btn btn--sm" onClick={openLogsFolder}><FolderOpen size={12} /> Folder</button>
        <button className="btn btn--sm" onClick={() => clearLog().then(refresh)}><Trash2 size={12} /> Clear</button>
        <button className="btn btn--sm" onClick={onClose}><X size={12} /> Close</button>
      </div>
      <pre className="sheet__body">{text || 'No log entries yet.'}</pre>
    </div>
  );
}
