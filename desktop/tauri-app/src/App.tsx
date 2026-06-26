import { useState, useEffect } from 'react';
import { Camera, Unplug, Zap } from 'lucide-react';
import Preview from './components/Preview';
import ControlPanel from './components/ControlPanel';
import './App.css';

interface ConnectionScreenProps {
  onConnect: (url: string) => void;
}

function ConnectionScreen({ onConnect }: ConnectionScreenProps) {
  const [url, setUrl] = useState('http://127.0.0.1:8080');
  const [isConnecting, setIsConnecting] = useState(false);
  const [error, setError] = useState('');

  const handleConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsConnecting(true);
    setError('');
    try {
      const formattedUrl = url.endsWith('/') ? url.slice(0, -1) : url;
      const res = await fetch(`${formattedUrl}/health`);
      if (!res.ok) throw new Error('Server returned error');
      const text = await res.text();
      if (text === 'OK' || text.includes('ok') || text.includes('OK')) {
        onConnect(formattedUrl);
      } else {
        throw new Error('Invalid health response');
      }
    } catch (err) {
      setError('Connection failed. Ensure Android server is running and ADB is forwarded.');
    } finally {
      setIsConnecting(false);
    }
  };

  return (
    <div className="connection-screen">
      <div className="connection-card glass-panel animate-fade">
        <div className="brand-hero">
          <Camera size={64} />
          <h1>OpenCamBridge</h1>
          <p>Connect to Android Camera Server</p>
        </div>
        
        <form onSubmit={handleConnect} style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 16 }}>
          {error && <div className="error-banner">{error}</div>}
          
          <div style={{ textAlign: 'left' }}>
            <label style={{ display: 'block', marginBottom: 8, fontSize: '0.85rem', color: 'var(--text-secondary)' }}>Server URL</label>
            <input 
              type="text" 
              className="input-control" 
              value={url} 
              onChange={(e) => setUrl(e.target.value)} 
              placeholder="http://127.0.0.1:8080"
              required
            />
          </div>
          
          <button type="submit" className="btn btn-primary" style={{ width: '100%', padding: '14px', marginTop: 8 }} disabled={isConnecting}>
            {isConnecting ? <Zap className="animate-spin" style={{ animation: 'pulse 1s infinite' }} /> : <Unplug size={18} />}
            {isConnecting ? 'Connecting...' : 'Connect to Camera'}
          </button>
        </form>
      </div>
    </div>
  );
}

export default function App() {
  const [baseUrl, setBaseUrl] = useState('');
  const [isConnected, setIsConnected] = useState(false);
  const [serverStatus, setServerStatus] = useState<any>(null);
  const [fitMode, setFitMode] = useState('fill');

  // Polling loop for status
  useEffect(() => {
    if (!isConnected || !baseUrl) return;
    
    let active = true;
    const poll = async () => {
      try {
        const res = await fetch(`${baseUrl}/api/camera/status`);
        if (!res.ok) throw new Error('Offline');
        const data = await res.json();
        const status = data.status || data;
        if (active) setServerStatus(status);
      } catch (err) {
        if (active) {
          setServerStatus((prev: any) => ({ ...prev, lifecycleState: 'OFFLINE', lastError: 'Server disconnected' }));
        }
      }
      if (active) setTimeout(poll, 2000);
    };
    poll();
    return () => { active = false; };
  }, [isConnected, baseUrl]);

  if (!isConnected) {
    return <ConnectionScreen onConnect={(url) => { setBaseUrl(url); setIsConnected(true); }} />;
  }

  const stateClass = serverStatus?.lifecycleState?.toLowerCase() || 'stopped';
  const isError = serverStatus?.lifecycleState === 'ERROR' || serverStatus?.lifecycleState === 'OFFLINE';

  return (
    <div className="app-container animate-fade">
      <header className="header">
        <div className="header-brand">
          <Camera size={24} /> OpenCamBridge
        </div>
        
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <div className="status-badge">
            <div className={`status-dot ${stateClass}`}></div>
            <span style={{ textTransform: 'uppercase', letterSpacing: 1 }}>{serverStatus?.lifecycleState || 'UNKNOWN'}</span>
          </div>
          <button className="btn btn-secondary" onClick={() => setIsConnected(false)} style={{ padding: '6px 12px', fontSize: '0.8rem' }}>
            <Unplug size={14} /> Disconnect
          </button>
        </div>
      </header>

      <main className="main-content">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, height: '100%', overflow: 'hidden' }}>
          {isError && serverStatus?.lastError && (
            <div className="error-banner animate-fade" style={{ margin: 0 }}>
              <strong>Camera Error:</strong> {serverStatus.lastError}
            </div>
          )}
          <Preview baseUrl={baseUrl} fitMode={fitMode} serverStatus={serverStatus} />
        </div>
        
        <ControlPanel baseUrl={baseUrl} fitMode={fitMode} setFitMode={setFitMode} />
      </main>
    </div>
  );
}
