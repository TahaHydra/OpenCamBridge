/** The OpenCamBridge mark: a lens in the accent tile. */
export function LogoMark({ size = 24 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" className="logo-mark">
      <rect x="1" y="1" width="22" height="22" rx="6.5" fill="var(--accent)" />
      <circle cx="12" cy="12" r="5.4" fill="none" stroke="#fff" strokeWidth="2" />
      <circle cx="12" cy="12" r="1.9" fill="#fff" />
      <circle cx="17.6" cy="6.4" r="1.1" fill="#fff" opacity="0.85" />
    </svg>
  );
}

export function Wordmark() {
  return (
    <span className="wordmark">
      Open<b>Cam</b>Bridge
    </span>
  );
}
