import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { EvaluateResponse } from '../models/audit.models';

@Injectable({ providedIn: 'root' })
export class DecisionsApiService {
  constructor(private readonly http: HttpClient) {}

  evaluate(modelKey: string, inputs: Record<string, unknown>, version?: string): Observable<EvaluateResponse> {
    const params = new URLSearchParams({ trace: 'true' });
    if (version) {
      params.set('version', version);
    }
    return this.http.post<EvaluateResponse>(
      `/api/decisions/${modelKey}/evaluate?${params.toString()}`,
      inputs,
    );
  }

  simulate(modelKey: string, inputs: Record<string, unknown>, version?: string): Observable<EvaluateResponse> {
    const params = new URLSearchParams({ trace: 'true' });
    if (version) {
      params.set('version', version);
    }
    return this.http.post<EvaluateResponse>(
      `/api/decisions/${modelKey}/simulate?${params.toString()}`,
      inputs,
    );
  }
}
