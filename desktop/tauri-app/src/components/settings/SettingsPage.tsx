import type { ReactNode } from 'react';

/** Standard settings page: title, one-line description, then sections. */
export function SettingsPage({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <div className="settings-page">
      <header className="settings-page__head">
        <h2 className="settings-page__title">{title}</h2>
        {description && <p className="settings-page__description">{description}</p>}
      </header>
      <div className="settings-page__body">{children}</div>
    </div>
  );
}

/** A titled card of related settings. */
export function SettingsSection({ title, description, children, aside }: { title: string; description?: ReactNode; children: ReactNode; aside?: ReactNode }) {
  return (
    <section className="settings-section">
      <header className="settings-section__head">
        <div>
          <h3 className="settings-section__title">{title}</h3>
          {description && <p className="settings-section__description">{description}</p>}
        </div>
        {aside}
      </header>
      <div className="settings-section__body">{children}</div>
    </section>
  );
}

/** Label on the left, control on the right. */
export function SettingsRow({ label, description, children }: { label: ReactNode; description?: ReactNode; children: ReactNode }) {
  return (
    <div className="settings-row">
      <div className="settings-row__text">
        <div className="settings-row__label">{label}</div>
        {description && <div className="settings-row__description">{description}</div>}
      </div>
      <div className="settings-row__control">{children}</div>
    </div>
  );
}
