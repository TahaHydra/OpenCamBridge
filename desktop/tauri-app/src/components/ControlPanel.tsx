import { useState, useEffect, useCallback } from 'react';
import { Play, Square, Settings2, Sliders, RefreshCw, RotateCw, ZoomIn, ZoomOut, Monitor, Video, ShieldAlert } from 'lucide-react';
import { connectAndSetupObs, ObsStatus } from '../services/obs';
import { invoke } from '@tauri-apps/api/core';

interface VirtualCamMetrics {
  source: string;
  profile: string;
  source_width: number;
  source_height: number;
  output_width: number;
  output_height: number;
  fps_target: number;
  http_jpeg_fps: number;
  decoded_fps: number;
  written_fps: number;
  dropped_jpegs: number;
  jpeg_queue_len: number;
  decode_ms_avg: number;
  resize_ms_avg: number;
  write_ms_avg: number;
  total_pipeline_ms: number;
  bytes_per_sec: number;
  pixel_format: string;
  last_error: string | null;
}

interface VirtualCamState {
  running: boolean;
  host_running: boolean;
  registered: boolean;
  metrics: VirtualCamMetrics | null;
  producer_path?: string;
  producer_exists?: boolean;
  producer_pid?: number;
  last_error?: string;
  last_metrics_time?: number;
}

interface ControlPanelProps {
  baseUrl: string;
  fitMode: string;
  setFitMode: (mode: string) => void;
  onEnterObsMode?: () => void;
}

export default function ControlPanel({ baseUrl, fitMode, onEnterObsMode }: ControlPanelProps) {
  const [cameras, setCameras] = useState<any[]>([]);
  const [settings, setSettings] = useState({
    cameraId: '0',
    profile: 'balanced',
    width: 1280,
    height: 720,
    fps: 30,
    jpegQuality: 85,
    displayRotation: '0',
    aspectRatio: '16:9',
    mirror: false,
    torchEnabled: false,
    linearZoom: 0.0
  });
  const [isSyncing, setIsSyncing] = useState(false);

  const [obsPassword, setObsPassword] = useState('');
  const [obsMode, setObsMode] = useState<'browser' | 'window'>('browser');
  const [obsStatus, setObsStatus] = useState<ObsStatus | null>(null);
  const [isObsConnecting, setIsObsConnecting] = useState(false);

  const [vcamState, setVcamState] = useState<VirtualCamState | null>(null);
  const [isVcamRegistering, setIsVcamRegistering] = useState(false);
  const [vcamMessage, setVcamMessage] = useState('');
  const [androidStreamStatus, setAndroidStreamStatus] = useState('unknown');
  const [now, setNow] = useState(Date.now() / 1000);

  const handleStartObs = async () => {
    if (obsMode === 'window' && onEnterObsMode) {
      onEnterObsMode();
      setIsObsConnecting(true);
      setObsStatus({ connected: false, message: 'Waiting for Clean Feed transition...' });
      await new Promise(resolve => setTimeout(resolve, 500));
    } else {
      setIsObsConnecting(true);
      setObsStatus(null);
    }

    const queryParams = new URLSearchParams();
    queryParams.set('fit', fitMode === 'fill' ? 'cover' : 'contain');
    queryParams.set('mirror', settings.mirror ? 'true' : 'false');
    let rot = settings.displayRotation;
    if (rot === 'auto' || !rot) rot = '0';
    queryParams.set('rotate', rot);

    const base = baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
    const obsUrl = `${base}/obs?${queryParams.toString()}`;

    const success = await connectAndSetupObs(obsPassword, obsUrl, obsMode, (status) => setObsStatus(status));
    setIsObsConnecting(false);
    if (obsMode === 'browser' && success && onEnterObsMode) {
      onEnterObsMode();
    }
  };

  const fetchStatus = useCallback(() => {
    fetch(`${baseUrl}/api/camera/status`)
      .then(res => res.json())
      .then(data => {
        const status = data.status || data;
        if (status) {
          setSettings(prev => ({
            ...prev,
            cameraId: status.cameraId || prev.cameraId,
            displayRotation: status.displayRotation || prev.displayRotation,
            aspectRatio: status.aspectRatio || prev.aspectRatio,
            mirror: status.mirror || prev.mirror,
            torchEnabled: status.torchEnabled || prev.torchEnabled,
            linearZoom: status.linearZoom !== undefined ? status.linearZoom : prev.linearZoom
          }));
        }
      })
      .catch(console.error);
  }, [baseUrl]);

  useEffect(() => {
    fetch(`${baseUrl}/api/camera/list`)
      .then(res => res.json())
      .then(data => setCameras(Array.isArray(data) ? data : data.cameras || []))
      .catch(console.error);
    
    fetchStatus();

    const interval = setInterval(() => {
      setNow(Date.now() / 1000);
      invoke<VirtualCamState>('get_virtual_camera_status')
        .then(setVcamState)
        .catch(console.error);
      
      fetch(`${baseUrl}/health`)
        .then(res => {
          if (res.ok) {
            setAndroidStreamStatus('running');
          } else {
            setAndroidStreamStatus('error');
          }
        })
        .catch(() => setAndroidStreamStatus('error'));

    }, 1000);

    return () => clearInterval(interval);
  }, [baseUrl, fetchStatus]);

  const handleRegisterVcam = async () => {
    setIsVcamRegistering(true);
    setVcamMessage('Registering...');
    try {
      const msg = await invoke<string>('register_virtual_camera_backend');
      setVcamMessage(msg);
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      setVcamMessage(e.toString());
    }
    setIsVcamRegistering(false);
  };

  const startStream = () => fetch(`${baseUrl}/api/stream/start`, { method: 'POST' });
  const stopStream = () => fetch(`${baseUrl}/api/stream/stop`, { method: 'POST' });

  const handleStartProducer = async (s: any) => {
    const targetUrl = `${baseUrl}/stream.mjpeg`;
    console.log('[Tauri UI] Calling start_virtual_camera_feeder with', {
      url: targetUrl, width: s.width, height: s.height, fps: s.fps, quality: s.jpegQuality
    });
    try {
      await invoke('start_virtual_camera_feeder', { 
        url: targetUrl,
        width: s.width,
        height: s.height,
        fps: s.fps,
        quality: s.jpegQuality
      });
      console.log('[Tauri UI] start_virtual_camera_feeder completed');
    } catch (e: any) {
      console.error('[Tauri UI] start_virtual_camera_feeder failed:', e);
      setVcamMessage(`Failed to start producer: ${e.toString()}`);
    }
  };

  const handleStartNativeCamera = async () => {
    setVcamMessage('Starting pipeline...');
    try {
      if (!vcamState?.host_running) {
        await invoke('start_virtual_camera_host');
      }
      if (!vcamState?.running) {
        await startStream();
        await handleStartProducer(settings);
      }
      setVcamMessage('');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
      setTimeout(() => window.dispatchEvent(new CustomEvent('reload-preview')), 1000);
    } catch (e: any) {
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const handleStopNativeCamera = async () => {
    try {
      await invoke('stop_virtual_camera_feeder');
      await invoke('stop_virtual_camera_host');
      await stopStream();
      setVcamMessage('Stopped native pipeline.');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const handleStartFeedOnly = async () => {
    setVcamMessage('Starting feed...');
    try {
      await startStream();
      await handleStartProducer(settings);
      setVcamMessage('');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
      setTimeout(() => window.dispatchEvent(new CustomEvent('reload-preview')), 1000);
    } catch (e: any) {
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const handleStopFeedOnly = async () => {
    try {
      await invoke('stop_virtual_camera_feeder');
      await stopStream();
      setVcamMessage('Stopped feed.');
      invoke<VirtualCamState>('get_virtual_camera_status').then(setVcamState);
    } catch (e: any) {
      setVcamMessage(`Error: ${e.toString()}`);
    }
  };

  const updateSetting = async (key: string, value: any) => {
    const newSettings = { ...settings, [key]: value };
    setSettings(newSettings);
    setIsSyncing(true);

    try {
      if (key === 'torchEnabled') {
        await fetch(`${baseUrl}/api/camera/torch`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: value })
        });
      } else if (key === 'linearZoom') {
        await fetch(`${baseUrl}/api/camera/zoom`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ linearZoom: value })
        });
      } else {
        await fetch(`${baseUrl}/api/settings`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ [key]: value })
        });
      }
    } catch (err) {
      console.error('Failed to update setting', err);
    } finally {
      setIsSyncing(false);
    }
  };

  const updateProfile = async (profile: string) => {
    let width = 1280; let height = 720; let fps = 30; let jpegQuality = 85;
    if (profile === 'low-latency') { width = 640; height = 480; fps = 30; jpegQuality = 70; }
    else if (profile === 'quality') { width = 1920; height = 1080; fps = 30; jpegQuality = 90; }
    else if (profile === 'experimental-1080p60') { width = 1920; height = 1080; fps = 60; jpegQuality = 85; }

    const newSettings = { ...settings, profile, width, height, fps, jpegQuality };
    setSettings(newSettings);
    setIsSyncing(true);

    try {
      await fetch(`${baseUrl}/api/settings`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ width, height, fps, jpegQuality })
      });
      if (vcamState?.running) {
        await invoke('stop_virtual_camera_feeder');
        await handleStartProducer(newSettings);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setIsSyncing(false);
    }
  };

  const handleRotate = async () => {
    const aspect = settings.aspectRatio || '16:9';
    const rot = settings.displayRotation || '0';
    let currentMode = 'landscape';
    if (aspect === '9:16' && rot === '90') currentMode = 'portrait_cw';
    else if (aspect === '9:16' && rot === '270') currentMode = 'portrait_ccw';
    else if (rot === '180') currentMode = 'upside_down';

    const map: any = { 'landscape': 'portrait_cw', 'portrait_cw': 'upside_down', 'upside_down': 'portrait_ccw', 'portrait_ccw': 'landscape' };
    const nextMode = map[currentMode] || 'portrait_cw';
    
    let nextAspect = '16:9'; let nextRot = '0';
    if (nextMode === 'portrait_cw') { nextAspect = '9:16'; nextRot = '90'; }
    else if (nextMode === 'portrait_ccw') { nextAspect = '9:16'; nextRot = '270'; }
    else if (nextMode === 'upside_down') { nextAspect = '16:9'; nextRot = '180'; }

    updateSetting('aspectRatio', nextAspect);
    updateSetting('displayRotation', nextRot);
  };

  return (
    <div className="control-panel glass-panel animate-fade" style={{ display: 'flex', flexDirection: 'column', gap: 24, padding: 24 }}>
      
      {/* NATIVE WINDOWS CAMERA SECTION */}
      <div className="control-group" style={{ background: 'rgba(30, 40, 50, 0.4)', borderRadius: 12, padding: 16, border: '1px solid rgba(100, 150, 255, 0.2)' }}>
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16, color: '#4dabf7' }}>
          <Monitor size={18} /> Native Windows Camera
        </h3>
        
        {/* Status Grid */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 16, fontSize: '0.85rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Camera Host:</span>
            <span style={{ color: vcamState?.host_running ? '#51cf66' : '#ff6b6b' }}>{vcamState?.host_running ? 'Running' : 'Stopped'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Android Stream:</span>
            <span style={{ color: androidStreamStatus === 'running' ? '#51cf66' : '#ff6b6b' }}>{androidStreamStatus === 'running' ? 'Running' : 'Stopped'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Producer:</span>
            <span style={{ color: vcamState?.running ? '#51cf66' : '#ff6b6b' }}>{vcamState?.running ? 'Running' : 'Stopped'}</span>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ color: 'var(--text-secondary)' }}>Profile:</span>
            <span style={{ color: '#fff', textTransform: 'capitalize' }}>{settings.profile}</span>
          </div>
        </div>

        {/* Buttons */}
        {vcamState && !vcamState.registered ? (
          <div style={{ marginBottom: 16, background: 'rgba(255, 179, 0, 0.1)', padding: 12, borderRadius: 6, border: '1px solid rgba(255, 179, 0, 0.3)' }}>
            <p style={{ fontSize: '0.85rem', color: '#ffb300', marginBottom: 12 }}>
              <ShieldAlert size={14} style={{ display: 'inline', verticalAlign: 'text-bottom', marginRight: 4 }} />
              Camera COM Object is not registered.
            </p>
            <button className="btn btn-primary" style={{ width: '100%' }} onClick={handleRegisterVcam} disabled={isVcamRegistering}>
              {isVcamRegistering ? 'Registering...' : 'Register Camera Backend'}
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginBottom: 16 }}>
            <div>
              <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: 6 }}>Phone Camera Stream:</div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <button className="btn btn-secondary" onClick={handleStartFeedOnly} disabled={vcamState?.running}>
                  <Play size={14} style={{ marginRight: 6 }} /> Start
                </button>
                <button className="btn btn-secondary" onClick={handleStopFeedOnly} disabled={!vcamState?.running}>
                  <Square size={14} style={{ marginRight: 6 }} /> Stop
                </button>
              </div>
            </div>

            <div>
              <div style={{ fontSize: '0.8rem', color: '#888', marginBottom: 6 }}>Desktop Virtual Camera:</div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <button className="btn btn-secondary" onClick={() => invoke('start_virtual_camera_host')} disabled={vcamState?.host_running}>
                  <Play size={14} style={{ marginRight: 6 }} /> Start
                </button>
                <button className="btn btn-secondary" onClick={() => invoke('stop_virtual_camera_host')} disabled={!vcamState?.host_running}>
                  <Square size={14} style={{ marginRight: 6 }} /> Stop
                </button>
              </div>
            </div>

            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12, marginTop: 4, paddingTop: 8, borderTop: '1px solid rgba(255,255,255,0.05)' }}>
              <button 
                style={{ background: 'none', border: 'none', color: '#4dabf7', fontSize: '0.75rem', cursor: (vcamState?.running && vcamState?.host_running) ? 'default' : 'pointer', opacity: (vcamState?.running && vcamState?.host_running) ? 0.5 : 1 }} 
                onClick={handleStartNativeCamera} 
                disabled={vcamState?.running && vcamState?.host_running}
              >
                Start All
              </button>
              <button 
                style={{ background: 'none', border: 'none', color: '#ff6b6b', fontSize: '0.75rem', cursor: (!vcamState?.running && !vcamState?.host_running) ? 'default' : 'pointer', opacity: (!vcamState?.running && !vcamState?.host_running) ? 0.5 : 1 }} 
                onClick={handleStopNativeCamera} 
                disabled={!vcamState?.running && !vcamState?.host_running}
              >
                Stop All
              </button>
            </div>
          </div>
        )}

        {vcamMessage && (
          <p style={{ fontSize: '0.8rem', color: '#aaa', marginBottom: 12, fontStyle: 'italic' }}>{vcamMessage}</p>
        )}

        {/* Producer Metrics */}
        {vcamState && (
          <div style={{ background: '#0a0a0a', padding: 12, borderRadius: 8, border: '1px solid #222', fontFamily: 'monospace', fontSize: '0.75rem', color: '#51cf66' }}>
            
            {/* Extended Status */}
            <div style={{ marginBottom: 8, paddingBottom: 8, borderBottom: '1px solid #222' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888' }}>
                <span>Producer Running:</span>
                <span style={{ color: vcamState.running ? '#51cf66' : '#ff6b6b' }}>{vcamState.running ? 'Yes' : 'No'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Producer Exists:</span>
                <span style={{ color: vcamState.producer_exists ? '#51cf66' : '#ff6b6b' }}>{vcamState.producer_exists ? 'Yes' : 'No'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Producer PID:</span>
                <span style={{ color: '#fff' }}>{vcamState.producer_pid || 'None'}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#888', marginTop: 4 }}>
                <span>Executable:</span>
                <span 
                  style={{ color: '#4dabf7', textDecoration: 'underline', cursor: 'pointer', textAlign: 'right', wordBreak: 'break-all', maxWidth: '70%' }}
                  onClick={() => vcamState.producer_path && navigator.clipboard.writeText(vcamState.producer_path)}
                  title={vcamState.producer_path ? `${vcamState.producer_path} (Click to copy)` : 'Unknown'}
                >
                  {vcamState.producer_path ? vcamState.producer_path.split('\\').pop() : 'Unknown'}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', color: '#ff6b6b', marginTop: 4 }}>
                <span>Last Error:</span>
                <span style={{ textAlign: 'right', wordBreak: 'break-all', maxWidth: '70%' }}>{vcamState.last_error || 'None'}</span>
              </div>
            </div>

            {vcamState.metrics ? (
              <>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>FPS (In/Out):</span>
                    <span>{vcamState.metrics.decoded_fps} / {vcamState.metrics.written_fps}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Latency Est:</span>
                    <span>{vcamState.metrics.total_pipeline_ms} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Decode Time:</span>
                    <span>{vcamState.metrics.decode_ms_avg} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Resize Time:</span>
                    <span>{vcamState.metrics.resize_ms_avg} ms</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Dropped JPEGs:</span>
                    <span style={{ color: vcamState.metrics.dropped_jpegs > 0 ? '#ff6b6b' : 'inherit' }}>{vcamState.metrics.dropped_jpegs}</span>
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Queue Len:</span>
                    <span>{vcamState.metrics.jpeg_queue_len}</span>
                  </div>
                </div>
                {vcamState.metrics.source_width !== vcamState.metrics.output_width && (
                  <div style={{ marginTop: 8, paddingTop: 8, borderTop: '1px solid #222', display: 'flex', justifyContent: 'space-between' }}>
                    <span style={{ color: '#888' }}>Resizing:</span>
                    <span>{vcamState.metrics.source_width}x{vcamState.metrics.source_height} &rarr; {vcamState.metrics.output_width}x{vcamState.metrics.output_height}</span>
                  </div>
                )}
                {vcamState.last_metrics_time && (
                  <div style={{ marginTop: 8, fontSize: '0.65rem', color: (now - vcamState.last_metrics_time > 3) ? '#ffb300' : '#444', textAlign: 'right' }}>
                    Last update: {Math.max(0, Math.floor(now - vcamState.last_metrics_time))}s ago
                  </div>
                )}
              </>
            ) : (
              <div style={{ color: '#888', textAlign: 'center', padding: '8px 0' }}>Waiting for metrics...</div>
            )}
          </div>
        )}
      </div>

      <div className="control-group">
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          <Settings2 size={16} /> Performance Profiles
        </h3>
        
        <div className="control-item">
          <label>Target Profile</label>
          <select 
            className="input-control"
            value={settings.profile}
            onChange={(e) => updateProfile(e.target.value)}
          >
            <option value="low-latency">Low Latency (640x480 @ 30fps, Q70)</option>
            <option value="balanced">Balanced (1280x720 @ 30fps, Q85)</option>
            <option value="quality">Quality (1920x1080 @ 30fps, Q90)</option>
            <option value="experimental-1080p60">Experimental (1080p @ 60fps, Q85)</option>
          </select>
        </div>
      </div>

      <div className="control-group">
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
          <Sliders size={16} /> Image Controls
        </h3>

        <div className="control-item">
          <label>Camera Lens</label>
          <select 
            className="input-control" 
            value={settings.cameraId} 
            onChange={(e) => updateSetting('cameraId', e.target.value)}
          >
            {cameras.map(c => (
              <option key={c.id} value={c.id}>
                {c.label || `${c.facing?.charAt(0).toUpperCase() + c.facing?.slice(1)} Camera (${c.id})`}
              </option>
            ))}
            {cameras.length === 0 && <option value="0">Default Camera</option>}
          </select>
        </div>

        <div className="control-item">
          <label style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span>Zoom Level</span>
            <span>{((settings.linearZoom || 0) * 100).toFixed(0)}%</span>
          </label>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 4 }}>
            <button className="btn btn-secondary" style={{ padding: '6px' }} onClick={() => updateSetting('linearZoom', Math.max(0, (settings.linearZoom || 0) - 0.1))}>
              <ZoomOut size={16} />
            </button>
            <input 
              type="range" 
              min="0" max="100" step="1"
              style={{ flex: 1 }}
              value={((settings.linearZoom || 0) * 100)}
              onChange={(e) => updateSetting('linearZoom', parseInt(e.target.value) / 100.0)}
            />
            <button className="btn btn-secondary" style={{ padding: '6px' }} onClick={() => updateSetting('linearZoom', Math.min(1.0, (settings.linearZoom || 0) + 0.1))}>
              <ZoomIn size={16} />
            </button>
          </div>
          {settings.linearZoom > 0 && (
            <button className="btn btn-secondary" style={{ width: '100%', marginTop: 8 }} onClick={() => updateSetting('linearZoom', 0.0)}>
              Reset Zoom
            </button>
          )}
        </div>

        <div className="control-item" style={{ marginTop: 20 }}>
          <button className="btn btn-secondary" style={{ width: '100%' }} onClick={handleRotate}>
            <RotateCw size={16} /> Rotate Output 90°
          </button>
        </div>

        <div className="control-row" style={{ marginTop: 20 }}>
          <label style={{ fontSize: '0.9rem' }}>Mirror Image</label>
          <label className="switch">
            <input 
              type="checkbox" 
              checked={settings.mirror} 
              onChange={(e) => updateSetting('mirror', e.target.checked)}
            />
            <span className="slider"></span>
          </label>
        </div>

        <div className="control-row" style={{ marginTop: 12 }}>
          <label style={{ fontSize: '0.9rem' }}>Flashlight (Torch)</label>
          <label className="switch">
            <input 
              type="checkbox" 
              checked={settings.torchEnabled} 
              onChange={(e) => updateSetting('torchEnabled', e.target.checked)}
            />
            <span className="slider"></span>
          </label>
        </div>

        {isSyncing && (
          <div style={{ marginTop: 16, fontSize: '0.8rem', color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center' }}>
            <RefreshCw size={12} className="animate-spin" /> Syncing settings...
          </div>
        )}
      </div>

      {/* OBS FALLBACK SECTION */}
      <div style={{ borderTop: '1px solid var(--surface-border)', paddingTop: 16 }}>
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12, color: '#aaa' }}>
          <Video size={16} /> OBS Fallback Mode
        </h3>
        
        <div className="control-item">
          <select 
            className="input-control" 
            value={obsMode} 
            onChange={(e) => setObsMode(e.target.value as 'browser' | 'window')}
            style={{ marginBottom: 8, width: '100%', cursor: 'pointer' }}
          >
            <option value="browser">Mode: Browser Source (Recommended)</option>
            <option value="window">Mode: Window Capture</option>
          </select>
          <input 
            type="password" 
            className="input-control" 
            placeholder="OBS WebSocket Password (optional)"
            value={obsPassword}
            onChange={(e) => setObsPassword(e.target.value)}
            style={{ marginBottom: 8 }}
          />
          <button className="btn btn-secondary" style={{ width: '100%', display: 'flex', justifyContent: 'center', gap: 8 }} onClick={handleStartObs} disabled={isObsConnecting}>
            {isObsConnecting ? <RefreshCw size={16} className="animate-spin" /> : <Monitor size={16} />}
            {isObsConnecting ? 'Connecting...' : 'Start OBS WebSocket Integration'}
          </button>
        </div>

        {obsStatus && (
          <div style={{ marginTop: 12, padding: 12, background: obsStatus.error ? 'rgba(255,50,50,0.1)' : 'rgba(50,255,50,0.1)', borderRadius: 6, border: `1px solid ${obsStatus.error ? 'rgba(255,50,50,0.3)' : 'rgba(50,255,50,0.3)'}` }}>
            <strong style={{ display: 'block', fontSize: '0.85rem', color: obsStatus.error ? '#ff6b6b' : '#51cf66' }}>{obsStatus.message}</strong>
            {obsStatus.error && <p style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: 4, lineHeight: 1.4 }}>{obsStatus.error}</p>}
          </div>
        )}

        <div style={{ marginTop: 16, borderTop: '1px solid var(--surface-border)', paddingTop: 16 }}>
          <button className="btn" style={{ width: '100%', background: 'var(--surface-light)', color: 'var(--text-primary)', display: 'flex', justifyContent: 'center', gap: 8 }} onClick={onEnterObsMode}>
            Enter Clean Feed (Manual Mode)
          </button>
          <p style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', textAlign: 'center', marginTop: 8, lineHeight: 1.4 }}>
            Only needed if Native Camera is blocked.
          </p>
        </div>
      </div>
    </div>
  );
}
