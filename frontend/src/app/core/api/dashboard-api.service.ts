import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ExecutionStats, RuleSummary } from '../models/dashboard.models';

@Injectable({ providedIn: 'root' })
export class DashboardApiService {
  constructor(private readonly http: HttpClient) {}

  executions(days: number, modelKey?: string | null): Observable<ExecutionStats> {
    const params: Record<string, string> = { days: String(days) };
    if (modelKey) {
      params['modelKey'] = modelKey;
    }
    return this.http.get<ExecutionStats>('/api/dashboard/executions', { params });
  }

  rules(days: number): Observable<RuleSummary[]> {
    return this.http.get<RuleSummary[]>('/api/dashboard/rules', { params: { days: String(days) } });
  }
}
