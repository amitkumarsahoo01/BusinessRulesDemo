import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { BudgetStatus } from '../../core/models/admin.models';
import { LlmBudgetPanel } from './llm-budget-panel';

function status(overrides: Partial<BudgetStatus> = {}): BudgetStatus {
  return {
    month: '2026-11',
    settings: { monthlyLimitUsd: 25, warnPercent: 80, hardStop: true, updatedBy: null, updatedAt: null },
    spentUsd: 21.5,
    remainingUsd: 3.5,
    percentUsed: 86,
    state: 'WARNING',
    byModel: [
      { provider: 'gemini', model: 'gemini-2.5-flash', calls: 40, inputTokens: 500000, outputTokens: 20000,
        costUsd: 0.2, estimatedCalls: 0, fallbackPricedCalls: 0 },
      { provider: 'anthropic', model: 'claude-opus-5-5', calls: 12, inputTokens: 400000, outputTokens: 900000,
        costUsd: 21.3, estimatedCalls: 2, fallbackPricedCalls: 0 },
    ],
    daily: [{ day: '2026-11-06', costUsd: 21.5 }],
    prices: [{ provider: 'gemini', modelPrefix: 'gemini-2.5-flash', inputPerMtok: 0.3, outputPerMtok: 2.5, updatedBy: 'seed', updatedAt: '' }],
    ...overrides,
  };
}

describe('LlmBudgetPanel', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LlmBudgetPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('shows spend against the budget with a per-model breakdown', () => {
    const fixture = TestBed.createComponent(LlmBudgetPanel);
    fixture.detectChanges();
    httpMock.expectOne('/api/ai/admin/llm/budget').flush(status());
    fixture.detectChanges();

    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('$21.50');
    expect(el.textContent).toContain('of $25.00 in 2026-11');
    expect(el.querySelector('[data-testid=budget-state]')?.textContent).toContain('Above 80%');
    expect(el.textContent).toContain('claude-opus-5-5');
    expect(el.textContent).toContain('~est');
  });

  it('says chat is paused once a hard-stop budget is used up', () => {
    const fixture = TestBed.createComponent(LlmBudgetPanel);
    fixture.detectChanges();
    httpMock.expectOne('/api/ai/admin/llm/budget').flush(status({ state: 'EXCEEDED', spentUsd: 25.2, percentUsed: 100.8 }));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid=budget-state]')?.textContent)
      .toContain('chat paused');
  });

  it('saves a new budget and a new price', () => {
    const fixture = TestBed.createComponent(LlmBudgetPanel);
    fixture.detectChanges();
    httpMock.expectOne('/api/ai/admin/llm/budget').flush(status());
    const panel = fixture.componentInstance;

    panel.limit = 50;
    panel.saveBudget();
    const put = httpMock.expectOne('/api/ai/admin/llm/budget');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ monthlyLimitUsd: 50, warnPercent: 80, hardStop: true });
    put.flush(status({ settings: { ...status().settings, monthlyLimitUsd: 50 } }));

    panel.addPrice();
    panel.draft = { ...panel.draft, provider: 'gemini', modelPrefix: ' gemini-3-flash ', inputPerMtok: 0.5, outputPerMtok: 3 };
    panel.savePrice();
    const price = httpMock.expectOne('/api/ai/admin/llm/budget/prices');
    expect(price.request.body).toEqual({ provider: 'gemini', modelPrefix: 'gemini-3-flash', inputPerMtok: 0.5, outputPerMtok: 3 });
    price.flush(status());
    expect(panel.editing()).toBeNull();
  });
});
