import { signal } from '@angular/core';
import { ChatApiService } from '../../core/api/chat-api.service';
import { ChatMessage, ToolCallEntry } from '../../core/models/chat.models';

export interface ChatContext {
  modelKey: string | null;
  executionId: string | null;
}

/**
 * One conversation with the assistant: start, stream answers, keep history. Shared by the full
 * Ask AI page and the "Ask Bob" popup so both behave identically. Plain class, not a service:
 * each host owns its own conversation.
 */
export class ChatSession {
  readonly conversationId = signal<string | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly streamingText = signal('');
  readonly sending = signal(false);
  readonly error = signal<string | null>(null);
  readonly context = signal<ChatContext>({ modelKey: null, executionId: null });

  constructor(private readonly chatApi: ChatApiService) {}

  start(context: ChatContext): void {
    this.context.set(context);
    this.conversationId.set(null);
    this.messages.set([]);
    this.error.set(null);
    this.chatApi
      .startConversation({ pinnedModelKey: context.modelKey, pinnedVersion: null, pinnedExecutionId: context.executionId })
      .subscribe({
        next: ({ conversationId }) => {
          this.conversationId.set(conversationId);
          this.refreshHistory();
        },
        error: () => this.error.set('Could not start a conversation.'),
      });
  }

  suggestedQuestions(): string[] {
    const { modelKey, executionId } = this.context();
    if (executionId) {
      return ['Why did this execution come out the way it did?', 'What inputs drove this outcome?'];
    }
    if (modelKey) {
      return [`Explain the ${modelKey} model in plain English.`, 'Are there any gaps or overlaps in its decision tables?'];
    }
    return [
      'Explain the loan-eligibility model in plain English.',
      'What changed between loan-eligibility 1.0.0 and 1.1.0, and how many past cases would flip?',
      'Are there gaps or overlaps in Risk Category?',
    ];
  }

  /** Returns false when the message could not be sent (empty, no conversation yet, or busy). */
  send(raw: string): boolean {
    const text = raw.trim();
    const conversationId = this.conversationId();
    if (!text || !conversationId || this.sending()) {
      return false;
    }
    this.error.set(null);
    this.sending.set(true);
    this.streamingText.set('');
    // Optimistic bubble so the user's message appears immediately; replaced by the persisted
    // version (and the real assistant turn, with its tool-call log) once streaming finishes.
    this.messages.update((msgs) => [
      ...msgs,
      { role: 'USER', content: text, toolCallsJson: null, at: new Date().toISOString() },
    ]);
    void this.streamAnswer(conversationId, text);
    return true;
  }

  private async streamAnswer(conversationId: string, text: string): Promise<void> {
    try {
      for await (const chunk of this.chatApi.streamMessage(conversationId, text)) {
        this.streamingText.update((s) => s + chunk);
      }
    } catch {
      this.error.set('The assistant could not respond. Please try again.');
    } finally {
      this.sending.set(false);
      this.streamingText.set('');
      this.refreshHistory();
    }
  }

  private refreshHistory(): void {
    const conversationId = this.conversationId();
    if (!conversationId) {
      return;
    }
    this.chatApi.history(conversationId).subscribe((messages) => this.messages.set(messages));
  }

  static parseToolCalls(message: ChatMessage): ToolCallEntry[] {
    if (!message.toolCallsJson) {
      return [];
    }
    try {
      return JSON.parse(message.toolCallsJson) as ToolCallEntry[];
    } catch {
      return [];
    }
  }
}
