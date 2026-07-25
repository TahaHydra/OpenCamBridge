import type { ReactNode } from 'react';

export type Health = 'idle' | 'ok' | 'degraded' | 'down' | 'live' | 'busy';

const LAMP_CLASS: Record<Health, string> = {
  idle: '',
  ok: 'lamp--on',
  degraded: 'lamp--warn',
  down: 'lamp--fail',
  live: 'lamp--live',
  busy: 'lamp--busy',
};

/** An indicator lamp. Colour carries the same meaning it does on a mixer:
 *  red = on air, green = ready, amber = degraded or transitioning, dark = off. */
export function Lamp({ state, title }: { state: Health; title?: string }) {
  return <span className={`lamp ${LAMP_CLASS[state]}`} title={title} />;
}

/** A header readout: silkscreen label over a tabular value. */
export function Meter({
  label,
  value,
  unit,
  tone = 'idle',
  title,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  tone?: 'idle' | 'ok' | 'warn' | 'fail' | 'plain';
  title?: string;
}) {
  const toneClass =
    tone === 'ok' ? 'is-ready' : tone === 'warn' ? 'is-warn' : tone === 'fail' ? 'is-fail' : tone === 'idle' ? 'is-idle' : '';
  return (
    <div className="meter" title={title}>
      <span className="meter__label">{label}</span>
      <span className={`meter__value ${toneClass}`}>
        {value}
        {unit && <small>{unit}</small>}
      </span>
    </div>
  );
}

/** A titled panel section with a silkscreen legend. */
export function Section({
  legend,
  icon,
  aside,
  children,
}: {
  legend: string;
  icon?: ReactNode;
  aside?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="section">
      <h3 className="legend">
        {icon}
        {legend}
        {aside}
      </h3>
      {children}
    </div>
  );
}

/** One row of raw telemetry: key on the left, tabular value on the right. */
export function Tel({
  k,
  v,
  tone,
  title,
  onClick,
}: {
  k: ReactNode;
  v: ReactNode;
  tone?: 'ready' | 'warn' | 'fail' | 'muted' | 'link';
  title?: string;
  onClick?: () => void;
}) {
  return (
    <div className="tel">
      <span className="tel__k">{k}</span>
      <span
        className={`tel__v${tone ? ` is-${tone}` : ''}`}
        title={title}
        onClick={onClick}
      >
        {v}
      </span>
    </div>
  );
}

/** A collapsible inset well for dense diagnostics. */
export function Well({
  legend,
  icon,
  actions,
  flush,
  children,
}: {
  legend: string;
  icon?: ReactNode;
  actions?: ReactNode;
  flush?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="well">
      <div className="well__head">
        <h4 className="legend">
          {icon}
          {legend}
        </h4>
        {actions && <div className="well__head-actions">{actions}</div>}
      </div>
      <div className={`well__body${flush ? ' well__body--flush' : ''}`}>{children}</div>
    </div>
  );
}

export function Notice({
  kind = 'info',
  icon,
  title,
  children,
}: {
  kind?: 'info' | 'warn' | 'fail';
  icon?: ReactNode;
  title?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className={`notice notice--${kind}`}>
      {icon}
      <div className="notice__body">
        {title && <div className="notice__title">{title}</div>}
        {children}
      </div>
    </div>
  );
}

export function ToggleRow({
  title,
  note,
  checked,
  onChange,
  right,
}: {
  title: ReactNode;
  note?: ReactNode;
  checked?: boolean;
  onChange?: (value: boolean) => void;
  right?: ReactNode;
}) {
  return (
    <div className="toggle-row">
      <div className="toggle-row__text">
        <div className="toggle-row__title">{title}</div>
        {note && <div className="toggle-row__note">{note}</div>}
      </div>
      {right ?? (
        <label className="switch">
          <input type="checkbox" checked={!!checked} onChange={e => onChange?.(e.target.checked)} />
          <span className="slider" />
        </label>
      )}
    </div>
  );
}
