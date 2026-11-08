import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  BudgetSettings,
  BudgetStatus,
  LlmAdminView,
  LlmSettingsRequest,
  LlmTestResult,
  PriceRequest,
} from '../models/admin.models';

/** Admin-only endpoints; the server checks the bearer token (added by the auth interceptor). */
@Injectable({ providedIn: 'root' })
export class AdminApiService {
  constructor(private readonly http: HttpClient) {}

  getLlm(): Observable<LlmAdminView> {
    return this.http.get<LlmAdminView>('/api/ai/admin/llm');
  }

  saveLlm(request: LlmSettingsRequest): Observable<LlmAdminView> {
    return this.http.put<LlmAdminView>('/api/ai/admin/llm', request);
  }

  testLlm(request: LlmSettingsRequest): Observable<LlmTestResult> {
    return this.http.post<LlmTestResult>('/api/ai/admin/llm/test', request);
  }

  getBudget(): Observable<BudgetStatus> {
    return this.http.get<BudgetStatus>('/api/ai/admin/llm/budget');
  }

  saveBudget(settings: Pick<BudgetSettings, 'monthlyLimitUsd' | 'warnPercent' | 'hardStop'>): Observable<BudgetStatus> {
    return this.http.put<BudgetStatus>('/api/ai/admin/llm/budget', settings);
  }

  savePrice(price: PriceRequest): Observable<BudgetStatus> {
    return this.http.put<BudgetStatus>('/api/ai/admin/llm/budget/prices', price);
  }

  deletePrice(provider: string, modelPrefix: string): Observable<BudgetStatus> {
    return this.http.delete<BudgetStatus>('/api/ai/admin/llm/budget/prices', { params: { provider, modelPrefix } });
  }
}
