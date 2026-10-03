import { desktopInvoke } from './desktopBridge.ts';

export interface SavedPhone {
  phoneId: string;
  name: string;
  lastHost: string;
  port: number;
  pairedAt: number;
}

export interface PairingInvitation {
  id: string;
  name: string;
  code: string;
  qrSvg: string;
  expiresAt: number;
  hosts: string[];
  port: number;
}

export interface PairingStatus {
  state: 'waiting' | 'paired' | 'expired' | 'cancelled';
  phone?: SavedPhone;
  error?: string;
}

export interface PairedConnection {
  baseUrl: string;
  token: string;
  phoneId: string;
  name: string;
  port: number;
}

interface PairingOperations {
  start(): Promise<PairingInvitation>;
  status(id: string): Promise<PairingStatus>;
  cancel(id: string): Promise<void>;
}

export const pairingApi = {
  start: () => desktopInvoke<PairingInvitation>('pairing_start'),
  status: (id: string) => desktopInvoke<PairingStatus>('pairing_status', { id }),
  cancel: (id: string) => desktopInvoke<void>('pairing_cancel', { id }),
  list: () => desktopInvoke<SavedPhone[]>('pairing_list'),
  forget: (phoneId: string) => desktopInvoke<void>('pairing_forget', { phoneId }),
  connect: (phoneId: string) => desktopInvoke<PairedConnection>('pairing_connect', { phoneId }),
};

export type PairingView =
  | { state: 'idle' | 'starting' | 'expired' | 'cancelled' }
  | { state: 'waiting'; invitation: PairingInvitation }
  | { state: 'paired'; phone?: SavedPhone }
  | { state: 'error'; error: string };

export function formatPairingCode(code: string): string {
  return `${code.slice(0, 4)} ${code.slice(4)}`;
}
export function pairingSecondsRemaining(invitation: PairingInvitation, now: number): number {
  return Math.max(0, Math.ceil((invitation.expiresAt - now) / 1000));
}

/** Owns only the temporary pairing listener; never starts or stops capture. */
export class PairingSession {
  private api: PairingOperations;
  private notify: (view: PairingView) => void;
  private now: () => number;
  private view: PairingView = { state: 'idle' };
  private generation = 0;
  private pollingGeneration: number | undefined;
  private listenerId: string | undefined;
  private closed = false;

  constructor(api: PairingOperations, notify: (view: PairingView) => void, now = Date.now) {
    this.api = api;
    this.notify = notify;
    this.now = now;
  }

  private publish(view: PairingView): void {
    this.view = view;
    if (!this.closed) this.notify(view);
  }

  async start(): Promise<void> {
    if (this.closed || this.view.state === 'starting' || this.view.state === 'waiting') return;
    const generation = ++this.generation;
    this.publish({ state: 'starting' });
    try {
      const previousId = this.listenerId;
      this.listenerId = undefined;
      if (previousId) await this.api.cancel(previousId);
      if (this.closed || generation !== this.generation) return;
      const invitation = await this.api.start();
      if (this.closed || generation !== this.generation) {
        // The listener may have finished starting after the panel was closed.
        await this.api.cancel(invitation.id);
        return;
      }
      this.listenerId = invitation.id;
      this.publish({ state: 'waiting', invitation });
    } catch {
      if (!this.closed && generation === this.generation) {
        this.publish({ state: 'error', error: 'Could not create an invitation. Check your private LAN connection and try again.' });
      }
    }
  }

  async poll(): Promise<void> {
    if (this.closed || this.view.state !== 'waiting') return;
    const { invitation } = this.view;
    const generation = this.generation;
    // Expiry must still happen when an earlier status request is pending.
    if (pairingSecondsRemaining(invitation, this.now()) === 0) {
      ++this.generation;
      this.listenerId = undefined;
      this.publish({ state: 'expired' });
      await this.api.cancel(invitation.id).catch(() => undefined);
      return;
    }
    if (this.pollingGeneration === generation) return;
    this.pollingGeneration = generation;
    try {
      const result = await this.api.status(invitation.id);
      if (this.closed || generation !== this.generation) return;
      if (result.state === 'waiting') return;
      ++this.generation;
      this.publish(result.state === 'paired'
        ? { state: 'paired', phone: result.phone }
        : { state: result.state });
      // After pairing, preserve the bounded cached ACK retry window. The backend
      // expires it; closing this panel or explicitly connecting also cancels it.
      if (result.state !== 'paired') {
        this.listenerId = undefined;
        await this.api.cancel(invitation.id).catch(() => undefined);
      }
    } catch {
      if (this.closed || generation !== this.generation) return;
      ++this.generation;
      this.listenerId = undefined;
      this.publish({ state: 'error', error: 'Could not check pairing. Create a new invitation to try again.' });
      await this.api.cancel(invitation.id).catch(() => undefined);
    } finally {
      if (this.pollingGeneration === generation) this.pollingGeneration = undefined;
    }
  }

  async cancel(): Promise<void> {
    const id = this.listenerId;
    this.listenerId = undefined;
    const generation = ++this.generation;
    this.publish({ state: 'cancelled' });
    if (!id) return;
    try {
      await this.api.cancel(id);
    } catch {
      if (!this.closed && generation === this.generation) {
        this.publish({ state: 'error', error: 'Could not confirm cancellation. The invitation will expire automatically after two minutes.' });
      }
    }
  }

  close(): void {
    this.closed = true;
    void this.cancel();
  }
}
