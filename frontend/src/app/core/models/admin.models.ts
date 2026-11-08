export interface ProviderOption {
  id: string;
  label: string;
  apiKeyConfigured: boolean;
  requiresApiKey: boolean;
  apiKeyEnvVar: string | null;
  supportsBaseUrl: boolean;
  defaultBaseUrl: string;
  defaultModel: string;
  suggestedModels: string[];
}

export interface LlmSettings {
  provider: string;
  model: string;
  baseUrl: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface LlmHistoryEntry {
  provider: string;
  model: string;
  baseUrl: string | null;
  prevProvider: string | null;
  prevModel: string | null;
  changedBy: string;
  changedAt: string;
}

export interface LlmAdminView {
  current: LlmSettings;
  providers: ProviderOption[];
  history: LlmHistoryEntry[];
}

export interface LlmSettingsRequest {
  provider: string;
  model: string;
  baseUrl: string | null;
}

export interface BudgetSettings {
  monthlyLimitUsd: number;
  warnPercent: number;
  hardStop: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
}

export interface ModelSpend {
  provider: string;
  model: string;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  estimatedCalls: number;
  fallbackPricedCalls: number;
}

export interface LlmPrice {
  provider: string;
  modelPrefix: string;
  inputPerMtok: number;
  outputPerMtok: number;
  updatedBy: string;
  updatedAt: string;
}

export interface BudgetStatus {
  month: string;
  settings: BudgetSettings;
  spentUsd: number;
  remainingUsd: number;
  percentUsed: number;
  state: 'OK' | 'WARNING' | 'EXCEEDED';
  byModel: ModelSpend[];
  daily: { day: string; costUsd: number }[];
  prices: LlmPrice[];
}

export interface PriceRequest {
  provider: string;
  modelPrefix: string;
  inputPerMtok: number;
  outputPerMtok: number;
}

export interface LlmTestResult {
  ok: boolean;
  latencyMs: number;
  reply: string | null;
  error: string | null;
}
