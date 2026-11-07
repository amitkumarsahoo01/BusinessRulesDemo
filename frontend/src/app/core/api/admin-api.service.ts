import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { LlmAdminView, LlmSettingsRequest, LlmTestResult } from '../models/admin.models';

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
}
