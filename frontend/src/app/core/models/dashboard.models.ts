export interface DailyBucket {
  date: string; // yyyy-MM-dd (UTC)
  total: number;
  failed: number;
  byModel: Record<string, number>;
}

export interface ModelBreakdown {
  modelKey: string;
  total: number;
  failed: number;
  avgDurationMs: number;
}

export interface OutcomeBreakdown {
  modelKey: string;
  output: string;
  values: { value: string; count: number }[];
}

export interface ExecutionStats {
  from: string;
  to: string;
  days: number;
  total: number;
  succeeded: number;
  failed: number;
  avgDurationMs: number;
  p95DurationMs: number;
  previousPeriodTotal: number;
  daily: DailyBucket[];
  byModel: ModelBreakdown[];
  outcomes: OutcomeBreakdown[];
}

export interface DecisionCard {
  name: string;
  kind: 'DECISION_TABLE' | 'LITERAL_EXPRESSION';
  hitPolicy: string | null;
  ruleCount: number | null;
  outputType: string | null;
  requires: string[];
}

export interface RuleSummary {
  modelKey: string;
  name: string;
  owner: string;
  liveVersion: string | null;
  publishedAt: string | null;
  approvedBy: string | null;
  changeNote: string | null;
  inProgressVersion: string | null;
  inProgressStatus: string | null;
  versionCount: number;
  inputs: { name: string; typeRef: string | null }[];
  decisions: DecisionCard[];
  executionsInPeriod: number;
  failedInPeriod: number;
  lastExecutedAt: string | null;
}
