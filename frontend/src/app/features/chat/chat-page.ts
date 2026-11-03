import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ChatApiService } from '../../core/api/chat-api.service';
import { ChatMessage, ToolCallEntry } from '../../core/models/chat.models';
import { MarkdownView } from '../../shared/markdown-view/markdown-view';

@Component({
  selector: 'app-chat-page',
  imports: [FormsModule, MarkdownView],
  templateUrl: './chat-page.html',
  styleUrl: './chat-page.scss',
})
export class ChatPage implements OnInit {
  readonly conversationId = signal<string | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly streamingText = signal('');
  readonly sending = signal(false);
  readonly error = signal<string | null>(null);

  readonly pinnedModelKey = signal<string | null>(null);
  readonly pinnedExecutionId = signal<string | null>(null);

  draft = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly chatApi: ChatApiService,
  ) {}

  get suggestedQuestions(): string[] {
    if (this.pinnedExecutionId()) {
      return ['Why did this execution come out the way it did?', 'What inputs drove this outcome?'];
    }
    if (this.pinnedModelKey()) {
      return [
        `Explain the ${this.pinnedModelKey()} model in plain English.`,
        'Are there any gaps or overlaps in its decision tables?',
      ];
    }
    return [
      'Explain the loan-eligibility model in plain English.',
      'What changed between loan-eligibility 1.0.0 and 1.1.0, and how many past cases would flip?',
      'Are there gaps or overlaps in Risk Category?',
    ];
  }

  ngOnInit(): void {
    const params = this.route.snapshot.queryParamMap;
    const modelKey = params.get('modelKey');
    const executionId = params.get('executionId');
    this.pinnedModelKey.set(modelKey);
    this.pinnedExecutionId.set(executionId);

    this.chatApi
      .startConversation({ pinnedModelKey: modelKey, pinnedVersion: null, pinnedExecutionId: executionId })
      .subscribe({
        next: ({ conversationId }) => {
          this.conversationId.set(conversationId);
          this.refreshHistory();
        },
        error: () => this.error.set('Could not start a conversation.'),
      });
  }

  ask(question: string): void {
    this.draft = question;
    this.send();
  }

  send(): void {
    const text = this.draft.trim();
    const conversationId = this.conversationId();
    if (!text || !conversationId || this.sending()) {
      return;
    }
    this.draft = '';
    this.error.set(null);
    this.sending.set(true);
    this.streamingText.set('');
    // Optimistic bubble so the user's message appears immediately; replaced by the persisted
    // version (and the real assistant turn, with its tool-call log) once streaming finishes.
    this.messages.update((msgs) => [
      ...msgs,
      { role: 'USER', content: text, toolCallsJson: null, at: new Date().toISOString() },
    ]);

    this.streamAnswer(conversationId, text);
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

  parseToolCalls(message: ChatMessage): ToolCallEntry[] {
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
