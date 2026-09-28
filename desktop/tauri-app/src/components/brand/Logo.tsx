import originalLogo from '../../assets/opencambridge-original.png';

/** Original packaged OpenCamBridge artwork, shared with the executable icon. */
export function LogoMark({ size = 24 }: { size?: number }) {
  return (
    <img src={originalLogo} width={size} height={size} alt="" aria-hidden="true" className="logo-mark" />
  );
}

export function Wordmark() {
  return (
    <span className="wordmark">
      Open<b>Cam</b>Bridge
    </span>
  );
}
