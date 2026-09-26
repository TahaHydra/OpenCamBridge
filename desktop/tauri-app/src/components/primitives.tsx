import {
  useEffect,
  useId,
  useRef,
  type ButtonHTMLAttributes,
  type ReactNode,
  type RefObject,
} from 'react';
import { createPortal } from 'react-dom';
import { Loader2, X } from 'lucide-react';
import type { Tone } from '../state/status';

/**
 * The redesign's control vocabulary. Every screen builds from these so sizes,
 * spacing, focus rings and colour semantics stay identical everywhere:
 * accent = primary action / selection, green = healthy, yellow = warning,
 * red = error or destructive. Styling lives in styles/components.css.
 */

const cx = (...names: (string | false | null | undefined)[]) => names.filter(Boolean).join(' ');

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  variant = 'secondary',
  size = 'md',
  block,
  icon,
  loading,
  className,
  children,
  disabled,
  type = 'button',
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: 'md' | 'sm' | 'lg';
  block?: boolean;
  icon?: ReactNode;
  loading?: boolean;
}) {
  return (
    <button
      type={type}
      className={cx('ui-button', `ui-button--${variant}`, size !== 'md' && `ui-button--${size}`, block && 'ui-button--block', className)}
      disabled={disabled || loading}
      {...rest}
    >
      {loading ? <Loader2 size={size === 'sm' ? 13 : 15} className="ui-spin" /> : icon}
      {children}
    </button>
  );
}

export function IconButton({
  label,
  icon,
  active,
  size = 'md',
  className,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { label: string; icon: ReactNode; active?: boolean; size?: 'md' | 'sm' }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={active}
      className={cx('ui-icon-button', size === 'sm' && 'ui-icon-button--sm', active && 'is-active', className)}
      {...rest}
    >
      {icon}
    </button>
  );
}

export function Switch({
  checked,
  onChange,
  disabled,
  label,
}: {
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
  label: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className={cx('ui-switch', checked && 'is-on')}
      onClick={() => onChange(!checked)}
    >
      <span className="ui-switch__thumb" />
    </button>
  );
}

export function SwitchRow({
  label,
  description,
  checked,
  onChange,
  disabled,
  icon,
}: {
  label: ReactNode;
  description?: ReactNode;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
  icon?: ReactNode;
}) {
  const text = typeof label === 'string' ? label : 'Toggle';
  return (
    <div className={cx('ui-switch-row', disabled && 'is-disabled')}>
      {icon && <span className="ui-switch-row__icon">{icon}</span>}
      <div className="ui-switch-row__text">
        <div className="ui-switch-row__label">{label}</div>
        {description && <div className="ui-switch-row__description">{description}</div>}
      </div>
      <Switch checked={checked} onChange={onChange} disabled={disabled} label={text} />
    </div>
  );
}

export interface SegmentOption<T extends string | number> {
  value: T;
  label: ReactNode;
  icon?: ReactNode;
  disabled?: boolean;
  title?: string;
}

export function Segmented<T extends string | number>({
  options,
  value,
  onChange,
  disabled,
  size = 'md',
  label,
}: {
  options: SegmentOption<T>[];
  value: T;
  onChange: (value: T) => void;
  disabled?: boolean;
  size?: 'md' | 'sm';
  label: string;
}) {
  return (
    <div className={cx('ui-segmented', size === 'sm' && 'ui-segmented--sm', disabled && 'is-disabled')} role="radiogroup" aria-label={label}>
      {options.map(option => {
        const selected = option.value === value;
        return (
          <button
            key={String(option.value)}
            type="button"
            role="radio"
            aria-checked={selected}
            title={option.title}
            disabled={disabled || option.disabled}
            className={cx('ui-segmented__option', selected && 'is-selected')}
            onClick={() => { if (!selected) onChange(option.value); }}
          >
            {option.icon}
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

export interface SelectOption<T extends string | number> {
  value: T;
  label: string;
  disabled?: boolean;
}

export function Select<T extends string | number>({
  value,
  options,
  onChange,
  disabled,
  placeholder,
  label,
}: {
  value: T | '';
  options: SelectOption<T>[];
  onChange: (value: T) => void;
  disabled?: boolean;
  placeholder?: string;
  label: string;
}) {
  const numeric = options.length > 0 && typeof options[0].value === 'number';
  const known = options.some(option => option.value === value);
  return (
    <div className={cx('ui-select', disabled && 'is-disabled')}>
      <select
        aria-label={label}
        value={known ? String(value) : ''}
        disabled={disabled || options.length === 0}
        onChange={event => {
          const raw = event.target.value;
          onChange((numeric ? Number(raw) : raw) as T);
        }}
      >
        {(!known || options.length === 0) && (
          <option value="" disabled>{options.length === 0 ? (placeholder || 'Unavailable') : (placeholder || 'Select…')}</option>
        )}
        {options.map(option => (
          <option key={String(option.value)} value={String(option.value)} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

export function Field({
  label,
  hint,
  trailing,
  children,
  htmlFor,
}: {
  label: ReactNode;
  hint?: ReactNode;
  trailing?: ReactNode;
  children: ReactNode;
  htmlFor?: string;
}) {
  return (
    <div className="ui-field">
      <div className="ui-field__head">
        <label className="ui-field__label" htmlFor={htmlFor}>{label}</label>
        {trailing && <span className="ui-field__trailing">{trailing}</span>}
      </div>
      {children}
      {hint && <div className="ui-field__hint">{hint}</div>}
    </div>
  );
}

export function TextInput({
  value,
  onChange,
  placeholder,
  type = 'text',
  label,
  id,
  disabled,
  mono,
  onEnter,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  type?: 'text' | 'password' | 'number' | 'url';
  label: string;
  id?: string;
  disabled?: boolean;
  mono?: boolean;
  onEnter?: () => void;
}) {
  return (
    <input
      id={id}
      className={cx('ui-input', mono && 'ui-input--mono')}
      aria-label={label}
      type={type}
      value={value}
      placeholder={placeholder}
      disabled={disabled}
      spellCheck={false}
      autoComplete="off"
      onChange={event => onChange(event.target.value)}
      onKeyDown={event => { if (event.key === 'Enter' && onEnter) onEnter(); }}
    />
  );
}

export function Slider({
  value,
  min,
  max,
  step = 1,
  onChange,
  disabled,
  label,
}: {
  value: number;
  min: number;
  max: number;
  step?: number;
  onChange: (value: number) => void;
  disabled?: boolean;
  label: string;
}) {
  const percent = max > min ? ((value - min) / (max - min)) * 100 : 0;
  return (
    <input
      type="range"
      className="ui-slider"
      aria-label={label}
      min={min}
      max={max}
      step={step}
      value={value}
      disabled={disabled}
      style={{ '--fill': `${Math.min(100, Math.max(0, percent))}%` } as React.CSSProperties}
      onChange={event => onChange(Number(event.target.value))}
    />
  );
}

export function StatusDot({ tone, title }: { tone: Tone; title?: string }) {
  return <span className={cx('ui-dot', `ui-dot--${tone}`)} title={title} aria-hidden={title ? undefined : true} />;
}

export function Badge({ tone = 'idle', children, dot = true }: { tone?: Tone; children: ReactNode; dot?: boolean }) {
  return (
    <span className={cx('ui-badge', `ui-badge--${tone}`)}>
      {dot && <StatusDot tone={tone} />}
      {children}
    </span>
  );
}

export function Spinner({ size = 16 }: { size?: number }) {
  return <Loader2 size={size} className="ui-spin" aria-label="Loading" />;
}

export function Callout({
  tone = 'accent',
  icon,
  title,
  children,
  action,
}: {
  tone?: 'accent' | 'warn' | 'danger' | 'ok';
  icon?: ReactNode;
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className={cx('ui-callout', `ui-callout--${tone}`)} role={tone === 'danger' ? 'alert' : undefined}>
      {icon && <span className="ui-callout__icon">{icon}</span>}
      <div className="ui-callout__body">
        {title && <div className="ui-callout__title">{title}</div>}
        {children && <div className="ui-callout__text">{children}</div>}
        {action && <div className="ui-callout__action">{action}</div>}
      </div>
    </div>
  );
}

/** A labelled group inside a rail or settings page. */
export function Group({
  title,
  aside,
  children,
  className,
}: {
  title?: ReactNode;
  aside?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cx('ui-group', className)}>
      {(title || aside) && (
        <header className="ui-group__head">
          {title && <h3 className="ui-group__title">{title}</h3>}
          {aside && <span className="ui-group__aside">{aside}</span>}
        </header>
      )}
      <div className="ui-group__body">{children}</div>
    </section>
  );
}

/** One label/value line of telemetry. Tabular figures keep columns steady. */
export function MetricRow({
  label,
  value,
  unit,
  tone,
  title,
}: {
  label: ReactNode;
  value: ReactNode;
  unit?: string;
  tone?: 'ok' | 'warn' | 'danger' | 'muted';
  title?: string;
}) {
  return (
    <div className="ui-metric" title={title}>
      <span className="ui-metric__label">{label}</span>
      <span className={cx('ui-metric__value', tone && `is-${tone}`)}>
        {value}
        {unit && <small>{unit}</small>}
      </span>
    </div>
  );
}

/** Closes on outside click and Escape. Anchored below its wrapper. */
export function Popover({
  open,
  onClose,
  anchorRef,
  align = 'start',
  width,
  children,
  label,
}: {
  open: boolean;
  onClose: () => void;
  anchorRef: RefObject<HTMLElement | null>;
  align?: 'start' | 'end' | 'center';
  width?: number;
  children: ReactNode;
  label: string;
}) {
  const panelRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      const target = event.target as Node;
      if (panelRef.current?.contains(target) || anchorRef.current?.contains(target)) return;
      onClose();
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
      }
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey, true);
    };
  }, [open, onClose, anchorRef]);
  if (!open) return null;
  return (
    <div
      ref={panelRef}
      role="dialog"
      aria-label={label}
      className={cx('ui-popover', `ui-popover--${align}`)}
      style={width ? { width } : undefined}
    >
      {children}
    </div>
  );
}

export function MenuItem({
  children,
  description,
  icon,
  trailing,
  selected,
  disabled,
  danger,
  onSelect,
  title,
}: {
  children: ReactNode;
  description?: ReactNode;
  icon?: ReactNode;
  trailing?: ReactNode;
  selected?: boolean;
  disabled?: boolean;
  danger?: boolean;
  onSelect?: () => void;
  title?: string;
}) {
  return (
    <button
      type="button"
      role="menuitemradio"
      aria-checked={!!selected}
      title={title}
      disabled={disabled}
      className={cx('ui-menu-item', selected && 'is-selected', danger && 'is-danger')}
      onClick={onSelect}
    >
      {icon && <span className="ui-menu-item__icon">{icon}</span>}
      <span className="ui-menu-item__text">
        <span className="ui-menu-item__label">{children}</span>
        {description && <span className="ui-menu-item__description">{description}</span>}
      </span>
      {trailing && <span className="ui-menu-item__trailing">{trailing}</span>}
    </button>
  );
}

export function MenuSeparator() {
  return <div className="ui-menu-separator" role="separator" />;
}

/** Centered dialog over a dimmed window. Escape and the backdrop close it. */
export function Modal({
  open,
  onClose,
  title,
  children,
  size = 'md',
  className,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  className?: string;
}) {
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  // Parents pass fresh callbacks on every render (the camera state polls every
  // second); focus handling must run once per opening, not once per render,
  // or it would pull focus out of whatever field the user is typing in.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    dialogRef.current?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onCloseRef.current();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      previous?.focus?.();
    };
  }, [open]);
  if (!open) return null;
  return createPortal(
    <div className="ui-modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose(); }}>
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={cx('ui-modal', `ui-modal--${size}`, className)}
      >
        <h2 id={titleId} className="sr-only">{title}</h2>
        {children}
      </div>
    </div>,
    document.body,
  );
}

export function ModalClose({ onClose }: { onClose: () => void }) {
  return <IconButton label="Close" icon={<X size={16} />} onClick={onClose} className="ui-modal__close" />;
}
