import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateTestCaseRequest,
  DecisionDiff,
  DecisionSummary,
  DecisionTable,
  ImportResult,
  ModelGraph,
  ModelSummary,
  ModelVersion,
  RuleChangeAudit,
  TestCase,
  TestRunResult,
  UpdateDraftRequest,
  ValidationResult,
} from '../models/rules.models';

@Injectable({ providedIn: 'root' })
export class ModelsApiService {
  constructor(private readonly http: HttpClient) {}

  listModels(): Observable<ModelSummary[]> {
    return this.http.get<ModelSummary[]>('/api/models');
  }

  listVersions(modelKey: string): Observable<ModelVersion[]> {
    return this.http.get<ModelVersion[]>(`/api/models/${modelKey}/versions`);
  }

  getVersion(modelKey: string, id: string): Observable<ModelVersion> {
    return this.http.get<ModelVersion>(`/api/models/${modelKey}/drafts/${id}`);
  }

  listDecisions(modelKey: string, versionId: string): Observable<DecisionSummary[]> {
    return this.http.get<DecisionSummary[]>(`/api/models/${modelKey}/drafts/${versionId}/decisions`);
  }

  readDecisionTable(modelKey: string, versionId: string, decisionName: string): Observable<DecisionTable> {
    return this.http.get<DecisionTable>(
      `/api/models/${modelKey}/drafts/${versionId}/decisions/${encodeURIComponent(decisionName)}`,
    );
  }

  getGraph(modelKey: string, versionId: string): Observable<ModelGraph> {
    return this.http.get<ModelGraph>(`/api/models/${modelKey}/drafts/${versionId}/graph`);
  }

  importDmn(file: File, modelKey?: string): Observable<ImportResult> {
    const body = new FormData();
    body.append('file', file, file.name);
    if (modelKey?.trim()) {
      body.append('modelKey', modelKey.trim());
    }
    return this.http.post<ImportResult>('/api/models/import', body);
  }

  history(modelKey: string): Observable<RuleChangeAudit[]> {
    return this.http.get<RuleChangeAudit[]>(`/api/models/${modelKey}/history`);
  }

  diff(modelKey: string, fromVersion: string, toVersion: string): Observable<DecisionDiff[]> {
    return this.http.get<DecisionDiff[]>(
      `/api/models/${modelKey}/versions/${fromVersion}/diff/${toVersion}`,
    );
  }

  saveDraft(modelKey: string, request: UpdateDraftRequest): Observable<ModelVersion> {
    return this.http.post<ModelVersion>(`/api/models/${modelKey}/drafts`, request);
  }

  validate(modelKey: string, versionId: string): Observable<ValidationResult> {
    return this.http.post<ValidationResult>(
      `/api/models/${modelKey}/drafts/${versionId}/validate`,
      {},
    );
  }

  runTests(modelKey: string, versionId: string): Observable<TestRunResult> {
    return this.http.post<TestRunResult>(
      `/api/models/${modelKey}/drafts/${versionId}/tests/run`,
      {},
    );
  }

  approve(modelKey: string, versionId: string): Observable<ModelVersion> {
    return this.http.post<ModelVersion>(`/api/models/${modelKey}/drafts/${versionId}/approve`, {});
  }

  publish(modelKey: string, versionId: string): Observable<ModelVersion> {
    return this.http.post<ModelVersion>(`/api/models/${modelKey}/drafts/${versionId}/publish`, {});
  }

  rollback(modelKey: string, toVersion: string): Observable<ModelVersion> {
    return this.http.post<ModelVersion>(
      `/api/models/${modelKey}/rollback?toVersion=${encodeURIComponent(toVersion)}`,
      {},
    );
  }

  listTestCases(modelKey: string): Observable<TestCase[]> {
    return this.http.get<TestCase[]>(`/api/models/${modelKey}/test-cases`);
  }

  createTestCase(modelKey: string, request: CreateTestCaseRequest): Observable<TestCase> {
    return this.http.post<TestCase>(`/api/models/${modelKey}/test-cases`, request);
  }
}
