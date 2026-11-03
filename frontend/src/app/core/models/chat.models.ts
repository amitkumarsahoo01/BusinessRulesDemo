export interface StartConversationRequest {
  pinnedModelKey: string | null;
  pinnedVersion: string | null;
  pinnedExecutionId: string | null;
}

export interface ChatMessage {
  role: 'USER' | 'ASSISTANT';
  content: string;
  toolCallsJson: string | null;
  at: string;
}

export interface ToolCallEntry {
  tool: string;
  argsSummary: string;
}
