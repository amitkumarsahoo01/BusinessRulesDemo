import { Injectable, signal } from '@angular/core';

/**
 * Lets any page open the Ask Bob popup (e.g. "Ask Bob about this execution" on an audit record).
 * Bob already pins the current page's model or execution as context, so callers just ask it to open.
 */
@Injectable({ providedIn: 'root' })
export class AskBobLauncher {
  private readonly requests = signal(0);
  /** Increments on every open request; AskBob reacts to changes. */
  readonly openRequests = this.requests.asReadonly();

  open(): void {
    this.requests.update((n) => n + 1);
  }
}
