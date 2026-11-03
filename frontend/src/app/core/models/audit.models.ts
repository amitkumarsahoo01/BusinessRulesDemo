export interface TraceStep {
  seq: number;
  nodeType: 'DECISION' | 'DECISION_TABLE' | 'BKM' | 'CONTEXT_ENTRY';
  nodeName: string;
  matchedRules: number[] | null;
  inputs: Record<string, unknown> | null;
  result: unknown;
  messages: string[] | null;
  durationMs: number | null;
}

export interface EvaluateResponse {
  executionId: string | null;
  modelVersion: string;
  outputs: Record<string, unknown>;
  status: 'SUCCEEDED' | 'FAILED';
  trace: TraceStep[] | null;
}

export interface ExecutionSummary {
  id: string;
  modelKey: string;
  version: string;
  caller: string;
  status: string;
  durationMs: number;
  executedAt: string;
}

export interface ExecutionDetail {
  id: string;
  modelKey: string;
  version: string;
  correlationId: string | null;
  caller: string;
  status: string;
  durationMs: number;
  rowHash: string;
  executedAt: string;
  inputs: Record<string, unknown>;
  outputs: Record<string, unknown>;
  trace: TraceStep[];
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface ExecutionSearchFilter {
  modelKey?: string;
  version?: string;
  caller?: string;
  status?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}
