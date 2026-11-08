import { DatePipe, DecimalPipe, NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminApiService } from '../../core/api/admin-api.service';
import { BudgetStatus, LlmPrice } from '../../core/models/admin.models';

const PROVIDERS = ['anthropic', 'openai', 'gemini'];

/** Admin console: this month's LLM spend across all providers, the budget that caps it, and the price table. */
@Component({
  selector: 'app-llm-budget-panel',
  imports: [FormsModule, DecimalPipe, DatePipe, NgTemplateOutlet],
  templateUrl: './llm-budget-panel.html',
  styleUrl: './llm-budget-panel.scss',
})
export class LlmBudgetPanel {
  private readonly api = inject(AdminApiService);

  readonly providers = PROVIDERS;
  readonly status = signal<BudgetStatus | null>(null);
  readonly error = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  readonly busy = signal(false);

  limit = 0;
  warnPercent = 80;
  hardStop = true;

  /** Row being edited (provider|prefix), or 'new' for the add form. */
  readonly editing = signal<string | null>(null);
  draft: LlmPrice = this.blankPrice();

  readonly barPercent = computed(() => Math.min(100, this.status()?.percentUsed ?? 0));
  readonly maxDaily = computed(() => Math.max(0.000001, ...(this.status()?.daily.map((d) => d.costUsd) ?? [0])));

  constructor() {
    this.load();
  }

  load(): void {
    this.api.getBudget().subscribe({
      next: (s) => this.apply(s),
      error: (e: HttpErrorResponse) => this.error.set(this.describe(e)),
    });
  }

  private apply(s: BudgetStatus): void {
    this.status.set(s);
    this.limit = s.settings.monthlyLimitUsd;
    this.warnPercent = s.settings.warnPercent;
    this.hardStop = s.settings.hardStop;
  }

  budgetDirty(): boolean {
    const s = this.status()?.settings;
    return !!s && (s.monthlyLimitUsd !== this.limit || s.warnPercent !== this.warnPercent || s.hardStop !== this.hardStop);
  }

  saveBudget(): void {
    this.run(this.api.saveBudget({ monthlyLimitUsd: this.limit, warnPercent: this.warnPercent, hardStop: this.hardStop }),
      'Budget saved. It applies to the next chat message.');
  }

  key(p: LlmPrice): string {
    return `${p.provider}|${p.modelPrefix}`;
  }

  editPrice(p: LlmPrice): void {
    this.draft = { ...p };
    this.editing.set(this.key(p));
  }

  addPrice(): void {
    this.draft = this.blankPrice();
    this.editing.set('new');
  }

  cancelEdit(): void {
    this.editing.set(null);
  }

  savePrice(): void {
    const { provider, modelPrefix, inputPerMtok, outputPerMtok } = this.draft;
    this.run(this.api.savePrice({ provider, modelPrefix: modelPrefix.trim(), inputPerMtok, outputPerMtok }),
      `Price for ${modelPrefix.trim()} saved. Calls already recorded keep the cost they had.`, () => this.editing.set(null));
  }

  deletePrice(p: LlmPrice): void {
    this.run(this.api.deletePrice(p.provider, p.modelPrefix),
      `Removed ${p.modelPrefix}. Its models are now charged at the provider's highest listed price.`);
  }

  private run(call: ReturnType<AdminApiService['getBudget']>, success: string, after?: () => void): void {
    this.busy.set(true);
    this.error.set(null);
    this.message.set(null);
    call.subscribe({
      next: (s) => {
        this.busy.set(false);
        this.apply(s);
        this.message.set(success);
        after?.();
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(this.describe(e));
      },
    });
  }

  private blankPrice(): LlmPrice {
    return { provider: 'gemini', modelPrefix: '', inputPerMtok: 0, outputPerMtok: 0, updatedBy: '', updatedAt: '' };
  }

  private describe(e: HttpErrorResponse): string {
    if (e.status === 401) return 'Your session has expired. Sign in again.';
    if (e.status === 403) return 'Only administrators can manage the AI budget.';
    return (e.error && (e.error.detail as string)) || 'Something went wrong. Please try again.';
  }
}
