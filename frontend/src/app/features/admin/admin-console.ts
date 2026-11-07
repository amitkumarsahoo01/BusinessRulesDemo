import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AdminApiService } from '../../core/api/admin-api.service';
import { AuthService } from '../../core/auth/auth.service';
import { LlmAdminView, LlmSettingsRequest, LlmTestResult, ProviderOption } from '../../core/models/admin.models';

/** Admin-only: choose which LLM answers Ask AI / Ask Bob. Keys stay in server env vars. */
@Component({
  selector: 'app-admin-console',
  imports: [FormsModule, DatePipe],
  templateUrl: './admin-console.html',
  styleUrl: './admin-console.scss',
})
export class AdminConsole {
  private readonly api = inject(AdminApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly view = signal<LlmAdminView | null>(null);
  readonly loading = signal(true);
  readonly loadError = signal<string | null>(null);

  readonly provider = signal('');
  model = '';
  baseUrl = '';

  readonly saving = signal(false);
  readonly testing = signal(false);
  readonly testResult = signal<LlmTestResult | null>(null);
  readonly formError = signal<string | null>(null);
  readonly savedMessage = signal<string | null>(null);

  readonly selected = computed<ProviderOption | undefined>(() =>
    this.view()?.providers.find((p) => p.id === this.provider()),
  );

  readonly usable = computed(() => {
    const p = this.selected();
    return !!p && (!p.requiresApiKey || p.apiKeyConfigured);
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.getLlm().subscribe({
      next: (view) => {
        this.apply(view);
        this.loading.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.loading.set(false);
        this.loadError.set(this.describe(e));
      },
    });
  }

  private apply(view: LlmAdminView): void {
    this.view.set(view);
    this.provider.set(view.current.provider);
    this.model = view.current.model;
    this.baseUrl = view.current.baseUrl ?? '';
  }

  selectProvider(option: ProviderOption): void {
    if (option.id === this.provider()) {
      return;
    }
    this.provider.set(option.id);
    const current = this.view()?.current;
    // Switching back restores the saved values; otherwise start from the provider's defaults.
    this.model = current?.provider === option.id ? current.model : option.defaultModel;
    this.baseUrl = current?.provider === option.id ? (current.baseUrl ?? '') : '';
    this.testResult.set(null);
    this.formError.set(null);
    this.savedMessage.set(null);
  }

  isDirty(): boolean {
    const current = this.view()?.current;
    return (
      !!current &&
      (current.provider !== this.provider() ||
        current.model !== this.model.trim() ||
        (current.baseUrl ?? '') !== this.baseUrl.trim())
    );
  }

  private request(): LlmSettingsRequest {
    return {
      provider: this.provider(),
      model: this.model.trim(),
      baseUrl: this.selected()?.supportsBaseUrl && this.baseUrl.trim() ? this.baseUrl.trim() : null,
    };
  }

  test(): void {
    this.testing.set(true);
    this.testResult.set(null);
    this.formError.set(null);
    this.api.testLlm(this.request()).subscribe({
      next: (result) => {
        this.testing.set(false);
        this.testResult.set(result);
      },
      error: (e: HttpErrorResponse) => {
        this.testing.set(false);
        this.formError.set(this.describe(e));
      },
    });
  }

  save(): void {
    this.saving.set(true);
    this.formError.set(null);
    this.savedMessage.set(null);
    this.api.saveLlm(this.request()).subscribe({
      next: (view) => {
        this.saving.set(false);
        this.apply(view);
        this.savedMessage.set(`Now answering with ${view.current.provider} / ${view.current.model}. No restart needed.`);
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describe(e));
      },
    });
  }

  reset(): void {
    const view = this.view();
    if (view) {
      this.apply(view);
      this.testResult.set(null);
      this.formError.set(null);
    }
  }

  signInAgain(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  providerLabel(id: string | null): string {
    return this.view()?.providers.find((p) => p.id === id)?.label ?? id ?? '—';
  }

  private describe(e: HttpErrorResponse): string {
    if (e.status === 401) return 'Your session has expired. Sign in again to use the admin console.';
    if (e.status === 403) return 'The admin console is only available to administrators.';
    return (e.error && (e.error.detail as string)) || 'Something went wrong. Please try again.';
  }
}
