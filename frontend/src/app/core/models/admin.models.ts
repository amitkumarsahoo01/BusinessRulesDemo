export interface ProviderOption {
  id: string;
  label: string;
  apiKeyConfigured: boolean;
  requiresApiKey: boolean;
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

export interface LlmTestResult {
  ok: boolean;
  latencyMs: number;
  reply: string | null;
  error: string | null;
}
