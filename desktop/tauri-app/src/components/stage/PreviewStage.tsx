import type { ReactNode } from 'react';
import { AlertTriangle, CameraOff, MonitorOff, RefreshCw, Smartphone, X } from 'lucide-react';
import Preview from '../Preview';
import { Button, IconButton, Spinner } from '../primitives';
import type { CameraController } from '../../state/useCameraController';

/**
 * The container the camera preview lives in.
 *
 * This component owns the product-level states around the picture — phone
 * unreachable, camera stopped, preview paused, a change being applied — and
 * the local-only presentation options (Fit/Fill, mirror preview). It does NOT
 * render video itself: `<Preview>` (and the H.264 renderer behind it) is the
 * isolated preview implementation, mounted unchanged in the viewport slot with
 * its original props. A replacement renderer only has to honour the same
 * contract: fill `.viewer__viewport`, render the picture as `.preview-img`
 * (canvas/img/video) and respect `fitMode`.
 */
export default function PreviewStage({
  controller,
  fitMode,
  mirrorPreview,
  previewEnabled,
  onEnablePreview,
  compact,
}: {
  controller: CameraController;
  fitMode: 'fit' | 'fill';
  mirrorPreview: boolean;
  previewEnabled: boolean;
  onEnablePreview: () => void;
  /** Clean feed: no chrome, no messages over the picture. */
  compact?: boolean;
}) {
  const { phoneState, serverStatus, baseUrl, token, transport } = controller;

  let content: ReactNode;
  if (!previewEnabled) {
    content = (
      <StageMessage
        icon={<MonitorOff size={28} />}
        title="Preview is paused"
        text="The camera keeps running and apps still receive video. Only this window stopped decoding."
        action={<Button variant="primary" onClick={onEnablePreview}>Resume preview</Button>}
      />
    );
  } else if (phoneState === 'connecting') {
    content = <StageMessage icon={<Spinner size={26} />} title="Connecting to your phone…" />;
  } else if (phoneState === 'offline') {
    content = (
      <StageMessage
        icon={<Smartphone size={28} />}
        title="Phone not reachable"
        text={transport === 'USB'
          ? 'Open OpenCamBridge on the phone and tap Start. Check that the USB cable is connected — the desktop reconnects on its own.'
          : 'Open OpenCamBridge on the phone and tap Start. Keep both devices on the same network — the desktop reconnects on its own.'}
      />
    );
  } else if (phoneState === 'stopped') {
    content = (
      <StageMessage
        icon={<CameraOff size={28} />}
        title="The phone camera is off"
        text="The phone is connected and waiting. Start the camera to see the picture."
        action={<Button variant="primary" onClick={() => void controller.startPhoneCamera()}>Start phone camera</Button>}
      />
    );
  } else if (phoneState === 'failed') {
    content = (
      <StageMessage
        tone="danger"
        icon={<AlertTriangle size={28} />}
        title="The camera stopped with an error"
        text={serverStatus?.lastError || 'The phone reported a camera failure.'}
        action={<Button icon={<RefreshCw size={14} />} onClick={() => void controller.recoverPhoneCamera()}>Try again</Button>}
      />
    );
  } else {
    content = <Preview baseUrl={baseUrl} token={token} fitMode={fitMode} serverStatus={serverStatus} />;
  }

  return (
    <div className={`viewer${compact ? ' viewer--compact' : ''}`}>
      <div className={`viewer__viewport${mirrorPreview ? ' is-mirrored' : ''}`}>{content}</div>

      {!compact && controller.isLive && !controller.isSyncing && phoneState === 'streaming' && previewEnabled && (
        <div className="viewer__live" title="An app is reading OpenCamBridge Camera">
          <span className="viewer__live-dot" /> Live in apps
        </div>
      )}

      {!compact && controller.isSyncing && (
        <div className="viewer__pill" role="status">
          <Spinner size={13} /> {controller.syncLabel || 'Applying…'}
        </div>
      )}

      {!compact && controller.message && (
        <div className="viewer__toast" role="status">
          <span className="viewer__toast-text">{controller.message}</span>
          <IconButton size="sm" label="Dismiss" icon={<X size={14} />} onClick={controller.dismissMessage} />
        </div>
      )}
    </div>
  );
}

function StageMessage({
  icon,
  title,
  text,
  action,
  tone,
}: {
  icon: ReactNode;
  title: string;
  text?: string;
  action?: ReactNode;
  tone?: 'danger';
}) {
  return (
    <div className={`viewer-message${tone ? ` viewer-message--${tone}` : ''}`}>
      <div className="viewer-message__icon">{icon}</div>
      <div className="viewer-message__title">{title}</div>
      {text && <p className="viewer-message__text">{text}</p>}
      {action && <div className="viewer-message__action">{action}</div>}
    </div>
  );
}
