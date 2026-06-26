import { useState, useEffect, useCallback } from 'react';
import { Play, Square, Settings2, Sliders, RefreshCw, RotateCw, ZoomIn, ZoomOut } from 'lucide-react';

interface ControlPanelProps {
  baseUrl: string;
  fitMode: string;
  setFitMode: (mode: string) => void;
}

export default function ControlPanel({ baseUrl, fitMode, setFitMode }: ControlPanelProps) {
  const [cameras, setCameras] = useState<any[]>([]);
  const [settings, setSettings] = useState({
    cameraId: '0',
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

  const fetchStatus = useCallback(() => {
    fetch(`${baseUrl}/api/camera/status`)
      .then(res => res.json())
      .then(data => {
        const status = data.status || data;
        if (status) {
          setSettings(prev => ({
            ...prev,
            cameraId: status.cameraId || prev.cameraId,
            width: status.width || prev.width,
            height: status.height || prev.height,
            fps: status.fps || prev.fps,
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
    
    // Fetch initial settings to sync state
    fetchStatus();
  }, [baseUrl, fetchStatus]);

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
        if (key === 'cameraId') {
          setTimeout(fetchStatus, 600); // Reload bounds and orientation after camera switches
        }
      }
    } catch (err) {
      console.error('Failed to update setting', err);
    } finally {
      setIsSyncing(false);
    }
  };

  const startStream = () => fetch(`${baseUrl}/api/stream/start`, { method: 'POST' });
  const stopStream = () => fetch(`${baseUrl}/api/stream/stop`, { method: 'POST' });

  const handleRotate = async () => {
    const aspect = settings.aspectRatio || '16:9';
    const rot = settings.displayRotation || '0';
    let currentMode = 'landscape';
    if (aspect === '9:16' && rot === '90') currentMode = 'portrait_cw';
    else if (aspect === '9:16' && rot === '270') currentMode = 'portrait_ccw';
    else if (rot === '180') currentMode = 'upside_down';

    const map: any = {
      'landscape': 'portrait_cw',
      'portrait_cw': 'upside_down',
      'upside_down': 'portrait_ccw',
      'portrait_ccw': 'landscape'
    };
    const nextMode = map[currentMode] || 'portrait_cw';
    
    let nextAspect = '16:9';
    let nextRot = '0';
    if (nextMode === 'portrait_cw') { nextAspect = '9:16'; nextRot = '90'; }
    else if (nextMode === 'portrait_ccw') { nextAspect = '9:16'; nextRot = '270'; }
    else if (nextMode === 'upside_down') { nextAspect = '16:9'; nextRot = '180'; }

    const newSettings = { ...settings, aspectRatio: nextAspect, displayRotation: nextRot };
    setSettings(newSettings);
    setIsSyncing(true);

    try {
      await fetch(`${baseUrl}/api/settings`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ aspectRatio: nextAspect, displayRotation: nextRot })
      });
    } catch (err) {
      console.error('Failed to rotate', err);
    } finally {
      setIsSyncing(false);
    }
  };

  return (
    <div className="control-panel glass-panel animate-fade">
      <div className="control-group" style={{ borderBottom: '1px solid var(--surface-border)' }}>
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Play size={16} /> Stream Control
        </h3>
        <div style={{ display: 'flex', gap: 12 }}>
          <button className="btn btn-primary" style={{ flex: 1 }} onClick={startStream}>
            <Play size={16} /> Start
          </button>
          <button className="btn btn-danger" style={{ flex: 1 }} onClick={stopStream}>
            <Square size={16} /> Stop
          </button>
        </div>
      </div>

      <div className="control-group" style={{ borderBottom: '1px solid var(--surface-border)' }}>
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Settings2 size={16} /> Camera Config
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
          <label>Resolution</label>
          <select 
            className="input-control"
            value={`${settings.width}x${settings.height}`}
            onChange={(e) => {
              const [w, h] = e.target.value.split('x');
              updateSetting('width', parseInt(w));
              updateSetting('height', parseInt(h));
            }}
          >
            <option value="1920x1080">1920 x 1080</option>
            <option value="1280x720">1280 x 720</option>
            <option value="640x480">640 x 480</option>
          </select>
        </div>

        <div className="control-item">
          <label>Target FPS</label>
          <select 
            className="input-control"
            value={settings.fps}
            onChange={(e) => updateSetting('fps', parseInt(e.target.value))}
          >
            <option value="15">15 FPS</option>
            <option value="30">30 FPS (Standard)</option>
            <option value="60">60 FPS (High Performance)</option>
          </select>
        </div>
      </div>

      <div className="control-group">
        <h3 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Sliders size={16} /> Image Controls
        </h3>

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

        <div className="control-item">
          <label>Desktop Fit Mode</label>
          <select 
            className="input-control"
            value={fitMode}
            onChange={(e) => setFitMode(e.target.value)}
          >
            <option value="fill">Fill (Cover, no black bars)</option>
            <option value="fit">Fit (Contain, full frame)</option>
          </select>
        </div>

        <div className="control-item">
          <label>JPEG Quality: {settings.jpegQuality}%</label>
          <input 
            type="range" 
            min="10" max="100" step="5"
            value={settings.jpegQuality}
            onChange={(e) => updateSetting('jpegQuality', parseInt(e.target.value))}
          />
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
            <RefreshCw size={12} className="animate-spin" style={{ animation: 'pulse 1s infinite' }} /> Syncing settings...
          </div>
        )}
      </div>
    </div>
  );
}
