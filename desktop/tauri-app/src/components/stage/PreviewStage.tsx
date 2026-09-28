import { useRef, type ReactNode } from 'react';
import { AlertTriangle, CameraOff, MonitorOff, RefreshCw, Smartphone, X } from 'lucide-react';
import Preview from '../Preview';
import { Button, IconButton, Spinner } from '../primitives';
import type { CameraController } from '../../state/useCameraController';
import CropOverlay from './CropOverlay';
import { useOutputFraming } from '../../state/useOutputFraming';
import { setPreferences } from '../../state/preferences';
import { centeredCrop } from '../../services/outputFraming.js';

/**
 * The container the camera preview lives in.
 *
 * This component owns the product-level states around the picture — phone
 * unreachable, camera stopped, preview paused, a change being applied — and
 * the local presentation options (Fit/Fill, mirror preview). The output editor
 * overlays the full source and sends independent native framing settings. It
 * does not render video itself: `<Preview>` owns the picture. A replacement
 * renderer only has to honour the same
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
  const viewport = useRef<HTMLDivElement>(null);
  const source = controller.sourceDimensions;
  const { framing, editing, error: framingError } = useOutputFraming(source, controller.producerRunning);
  const showEditor = editing && !compact && previewEnabled && phoneState === 'streaming';
  const ring = controller.vcamState?.metrics?.ring;
  const outputAspect = ring && ring.negotiated_width > 0 && ring.negotiated_height > 0
    ? ring.negotiated_width / ring.negotiated_height : 16 / 9;
  const crop = framing.mode === 'fill' ? centeredCrop(source.width, source.height, outputAspect) : framing.crop;

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
        title="Phone stopped or disconnected"
        text={transport === 'USB'
          ? 'Start again from the phone. The desktop reconnects automatically. Check that the USB cable is connected.'
          : 'Start again from the phone. The desktop reconnects automatically. Keep both devices on the same network.'}
      />
    );
  } else if (phoneState === 'stopped') {
    content = (
      <StageMessage
        icon={<CameraOff size={28} />}
        title="The phone camera is off"
        text="The phone is connected and waiting. Start the phone camera to see the picture."
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
    content = <Preview baseUrl={baseUrl} token={token} fitMode={showEditor ? 'fit' : fitMode} serverStatus={serverStatus} />;
  }

  return (
    <div className={`viewer${compact ? ' viewer--compact' : ''}`}>
      <div ref={viewport} className={`viewer__viewport${mirrorPreview ? ' is-mirrored' : ''}`}>
        {content}
        {showEditor && <CropOverlay viewport={viewport} source={source} crop={crop} outputAspect={outputAspect} showAppTrim={framing.mode !== 'fit'} mirrored={mirrorPreview}
          onChange={next => setPreferences({ outputFraming: { preset: 'custom', mode: 'custom', crop: next } })} />}
      </div>
      {!compact && framingError && <div className="framing-error" role="alert">{framingError}</div>}

      {!compact && controller.isLive && !controller.isSyncing && phoneState === 'streaming' && previewEnabled && (
        <div className="viewer__live" title="An app is using the virtual camera">
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
