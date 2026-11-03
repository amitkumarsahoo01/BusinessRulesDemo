import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ExecutionDetail, ExecutionSearchFilter, ExecutionSummary, PageResult } from '../models/audit.models';

@Injectable({ providedIn: 'root' })
export class ExecutionsApiService {
  constructor(private readonly http: HttpClient) {}

  search(filter: ExecutionSearchFilter): Observable<PageResult<ExecutionSummary>> {
    const params: Record<string, string> = {};
    Object.entries(filter).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        params[key] = String(value);
      }
    });
    return this.http.get<PageResult<ExecutionSummary>>('/api/executions', { params });
  }

  getById(id: string): Observable<ExecutionDetail> {
    return this.http.get<ExecutionDetail>(`/api/executions/${id}`);
  }
}
